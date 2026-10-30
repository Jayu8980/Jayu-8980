"""Transkun 2.0 (Yan & Duan, "Skipping the Frame-Level", NeurIPS 2021 / Transkun V2, MIT licence)
for on-device use: accuracy on the evaluation set, ONNX export and reference outputs.

The network runs in ONNX Runtime; what ONNX cannot hold is done the way the app does it, and
step 3 below checks that decomposition against the official pipeline before any Kotlin exists:
  - front end (6 windows, real FFT, mel filters, log): frontend.bin holds the windows and filters
  - transkun_core.onnx: 16 s of features -> per-key context ctx, as 9 outputs of 10 keys each, so the app
    reads one small chunk at a time while the rest stays in ONNX Runtime's native memory
  - transkun_score.onnx: ctx of some keys -> interval scores S[b, e] = (q_e . k_b) * |e - b|, begin-major,
    and the single-frame scores diag (added on S's diagonal by the decoder)
  - transkun_heads.onnx: interval features -> velocity logits and onset/offset refinement
  - semi-CRF Viterbi decoding, segment stitching and overlap resolution: plain code

Usage: python export_and_eval.py <eval_dir> <out_dir>
Prints TKEVAL (official pipeline vs truth) and TKPORT (decomposed pipeline vs official) lines.
Writes out_dir/transkun_*.onnx, frontend.bin, reference_tk/*.json, eval44k/*.f32.
"""
import csv, glob, json, math, os, sys, time
import numpy as np
import soundfile as sf
import torch
import onnx
import onnxruntime as ort
import moduleconf
import transkun
from scipy.signal import resample_poly

torch.set_grad_enabled(False)
eval_dir, out = sys.argv[1], sys.argv[2]
os.makedirs(os.path.join(out, "reference_tk"), exist_ok=True)
os.makedirs(os.path.join(out, "eval44k"), exist_ok=True)

pkg = os.path.dirname(transkun.__file__)
conf = moduleconf.parseFromFile(os.path.join(pkg, "pretrained/2.0.conf"))
model = conf["Model"].module.TransKun(conf=conf["Model"].config)
ck = torch.load(os.path.join(pkg, "pretrained/2.0.pt"), map_location="cpu")
model.load_state_dict(ck["best_state_dict"] if "best_state_dict" in ck else ck["state_dict"], strict=False)
model.eval()
FS, HOP, WIN = model.fs, model.hopSize, model.windowSize
SEG = math.ceil(model.segmentSizeInSecond * FS)
NFRAME = math.ceil(SEG / HOP) + 1
PITCHES = model.targetMIDIPitch  # [-64, -67] (pedals) + 21..108
print(f"TKINFO fs {FS} hop {HOP} win {WIN} frames/segment {NFRAME} symbols {len(PITCHES)}")


def f1(est, truth, tol=0.05):
    used = [False] * len(truth); tp = 0
    for (p, s, e) in sorted(est, key=lambda x: x[1]):
        for i, (tp_, ts, te) in enumerate(truth):
            if not used[i] and tp_ == p and abs(ts - s) <= tol:
                used[i] = True; tp += 1; break
    P = tp / max(1, len(est)); R = tp / max(1, len(truth))
    return P, R, (0.0 if P + R == 0 else 2 * P * R / (P + R))


def load44k(path):
    y, fs = sf.read(path, dtype="float32")
    if y.ndim > 1: y = y.mean(1)
    if fs != FS:
        g = math.gcd(fs, FS); y = resample_poly(y, FS // g, fs // g).astype(np.float32)
    return y

# ---- 1. Accuracy of the official pipeline ----
files = sorted(glob.glob(os.path.join(eval_dir, "*.flac")))
sums = {}
audio = {}
for f in files:
    name = os.path.basename(f)[:-5]; piece, cond = name.split("-")
    truth = [(int(r["pitch"]), float(r["start"]), float(r["end"])) for r in csv.DictReader(open(os.path.join(eval_dir, piece + ".csv")))]
    y = load44k(f); audio[name] = y
    y.astype("<f4").tofile(os.path.join(out, "eval44k", name + ".f32"))
    t0 = time.time()
    notes = model.transcribe(torch.from_numpy(y[:, None]))
    dt = time.time() - t0
    est = [(n.pitch, n.start, n.end) for n in notes if n.pitch > 0]
    json.dump([{"pitch": n.pitch, "start": n.start, "end": n.end, "velocity": int(n.velocity)} for n in notes if n.pitch > 0],
              open(os.path.join(out, "reference_tk", name + ".json"), "w"))
    r = f1(est, truth)
    s = sums.setdefault(cond, [0.0] * 4); s[0] += r[0]; s[1] += r[1]; s[2] += r[2]; s[3] += 1
    print(f"TKEVAL {name}: P {r[0]:.3f} R {r[1]:.3f} F1 {r[2]:.3f} ({len(est)} vs {len(truth)} notes, {dt:.1f} s for {len(y)/FS:.1f} s)", flush=True)
for c, s in sums.items():
    print(f"TKEVAL {c}: P {s[0]/s[3]:.3f} R {s[1]/s[3]:.3f} F1 {s[2]/s[3]:.3f}")

# ---- 2. Export ----
D = model.scorer.size
# Gradient checkpointing (a training-time memory saver) can't be traced: run the layers directly.
for mod in model.modules():
    if hasattr(mod, "useGradientCheckpoint"): mod.useGradientCheckpoint = False

CHUNK = 10
NCHUNK = math.ceil(len(PITCHES) / CHUNK)

class Core(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, feat):  # [1, T, 229, 6] -> NCHUNK x [<=CHUNK, T, D]
        ctx = self.m.backbone(feat, outputIndices=torch.tensor(self.m.targetMIDIPitch))[0]
        return tuple(ctx.split(CHUNK, dim=0))

class Score(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, ctx):  # [n, T, D] -> S [n, T(begin), T(end)], diag [n, T]
        q, k, diag = self.m.scorer.map(ctx).split([D, D, 1], dim=-1)
        q = q / math.sqrt(D)
        t = torch.arange(ctx.shape[1], dtype=torch.float32)
        return torch.matmul(k, q.transpose(1, 2)) * (t.unsqueeze(1) - t.unsqueeze(0)).abs(), diag[..., 0]

class Heads(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, attr):  # [N, 3D]
        return self.m.velocityPredictor(attr), self.m.refinedOFPredictor(attr)

nmel = model.framewiseFeatureExtractor.outputDim
nch = model.framewiseFeatureExtractor.nChannel
core_path = os.path.join(out, "transkun_core.onnx")
torch.onnx.export(Core(model).eval(), torch.zeros(1, NFRAME, nmel, nch), core_path, input_names=["features"],
                  output_names=[f"ctx{i}" for i in range(NCHUNK)], opset_version=17, do_constant_folding=True)
torch.onnx.export(Score(model).eval(), torch.zeros(CHUNK, NFRAME, D), os.path.join(out, "transkun_score.onnx"),
                  input_names=["ctx"], output_names=["score", "diag"], opset_version=17,
                  dynamic_axes={"ctx": {0: "n", 1: "t"}, "score": {0: "n", 1: "t", 2: "t"}, "diag": {0: "n", 1: "t"}})
torch.onnx.export(Heads(model).eval(), torch.zeros(5, 3 * D), os.path.join(out, "transkun_heads.onnx"),
                  input_names=["attr"], output_names=["velocity", "offsets"], opset_version=17,
                  dynamic_axes={"attr": {0: "n"}, "velocity": {0: "n"}, "offsets": {0: "n"}})
for p in ["transkun_core.onnx", "transkun_score.onnx", "transkun_heads.onnx"]:
    onnx.checker.check_model(os.path.join(out, p))
    print(f"TKINFO {p} {os.path.getsize(os.path.join(out, p)) / 1e6:.1f} MB")

# Front end constants: 6 windows (Hann + 5 learnt Gaussians) x WIN, then the mel filters (WIN/2+1) x nmel.
spec = model.framewiseFeatureExtractor.spectrogramExtractor
wins = torch.cat([spec.win.unsqueeze(0), spec.winGen.get().t()], dim=0).numpy().astype("<f4")
fb = model.framewiseFeatureExtractor.freq2mels.numpy().astype("<f4")
assert wins.shape == (nch, WIN) and fb.shape == (WIN // 2 + 1, nmel), (wins.shape, fb.shape)
with open(os.path.join(out, "frontend.bin"), "wb") as fh:
    np.array([FS, HOP, WIN, nch, nmel, NFRAME, len(PITCHES), D], dtype="<i4").tofile(fh)
    np.array(PITCHES, dtype="<i4").tofile(fh)
    wins.tofile(fh); fb.tofile(fh)
print(f"TKINFO frontend.bin {os.path.getsize(os.path.join(out, 'frontend.bin')) / 1e6:.1f} MB, mel eps {model.framewiseFeatureExtractor.eps}")

# ---- 3. The decomposed pipeline (what the app does), checked against the official one ----
so = ort.SessionOptions()
core = ort.InferenceSession(core_path, so)
score = ort.InferenceSession(os.path.join(out, "transkun_score.onnx"), so)
heads = ort.InferenceSession(os.path.join(out, "transkun_heads.onnx"), so)
EPS = model.framewiseFeatureExtractor.eps

def features(seg):
    pad = np.pad(seg, (WIN // 2, (NFRAME - 1) * HOP + WIN // 2 - len(seg)))
    frames = np.lib.stride_tricks.sliding_window_view(pad, WIN)[::HOP][:NFRAME].astype(np.float64)
    frames = (frames - frames.mean()) / (frames.std(ddof=1) + 1e-8)
    spec = np.fft.rfft(frames[:, None, :] * wins[None].astype(np.float64), norm="ortho")  # [T, 6, F]
    power = np.abs(spec) ** 2
    mel = power @ fb.astype(np.float64)  # [T, 6, mel]
    mel = (np.log(mel + EPS) - math.log(EPS)) / (-math.log(EPS))
    return mel.transpose(0, 2, 1)[None].astype(np.float32)  # [1, T, mel, 6]

def viterbi_backward(S, start):  # S[b, e] for one symbol, noise score 0
    T = S.shape[0]
    q = np.zeros(T); ptr = np.zeros(T, dtype=np.int64)  # ptr[b] = chosen end, -1 = skip
    d = np.diagonal(S)
    q[T - 1] = d[T - 1] * (d[T - 1] > 0)
    for b in range(T - 2, -1, -1):
        cand = q[b + 1:] + S[b, b + 1:]
        best = int(np.argmax(cand)); skip = q[b + 1]
        if skip >= cand[best]: ptr[b] = -1; v = skip  # torch max returns the first maximum: skip comes first
        else: ptr[b] = b + 1 + best; v = cand[best]
        q[b] = v + d[b] * (d[b] > 0)
    out_ = []; j = start
    while j < T - 1:
        if d[j] > 0: out_.append((j, j))
        if ptr[j] < 0: j += 1
        else: out_.append((j, int(ptr[j]))); j = int(ptr[j])
    if d[T - 1] > 0: out_.append((T - 1, T - 1))
    return out_

def cb_mean(logits):
    p = 1 / (1 + np.exp(-logits))
    outside = (p < 0.499) | (p > 0.501)
    cut = np.where(outside, p, 0.499)
    mus = cut / (2 * cut - 1) + 1 / (np.log1p(-cut) - np.log(cut))
    x = p - 0.5
    return np.where(outside, mus, 0.5 + (1 / 3 + 16 / 45 * x ** 2) * x)

def port(y):
    pad_t = model.segmentSizeInSecond - model.segmentHopSizeInSecond
    padn = math.ceil(pad_t * FS)
    x = np.pad(y, (padn, padn)); n = len(x)
    step = math.ceil(model.segmentHopSizeInSecond * FS / HOP) * HOP
    last_idx = round(SEG / HOP); frame_dur = HOP / FS
    start_pos = [math.floor(pad_t * FS / HOP)] * len(PITCHES)
    by_pitch = {}
    for i in range(0, n, step):
        seg = x[i:min(i + SEG, n)]
        seg = np.pad(seg, (0, SEG - len(seg)))
        begin = i / FS - pad_t
        chunks = core.run(None, {"features": features(seg)})
        ctx = np.concatenate(chunks, axis=0)
        events, last_p = [], []
        for j, pitch in enumerate(PITCHES):
            S, dg = score.run(None, {"ctx": ctx[j:j + 1]})
            S = S[0]; S[np.arange(NFRAME), np.arange(NFRAME)] += dg[0]
            iv = viterbi_backward(S, start_pos[j])
            cur_last = 0
            if iv:
                a = np.array([b for b, e in iv]); e_ = np.array([e for b, e in iv])
                attr = np.concatenate([ctx[j, a], ctx[j, e_], ctx[j, a] * ctx[j, e_]], axis=-1).astype(np.float32)
                vl, of = heads.run(None, {"attr": attr})
                vel = vl.argmax(-1); ofv = np.clip((cb_mean(of[:, :2]) - 0.5) / 0.99, -0.5, 0.5); ofp = of[:, 2:] > 0
                last_end = 0.0
                for k_, (b, e) in enumerate(iv):
                    s_ = (b + ofv[k_, 0]) * frame_dur; t_ = (e + ofv[k_, 1]) * frame_dur
                    has_on = b > 0 or ofp[k_, 0]; has_off = e < last_idx or ofp[k_, 1]
                    s_ = max(s_, last_end); t_ = max(t_, s_ + 1e-8); last_end = t_
                    events.append(dict(start=s_, end=t_, pitch=pitch, velocity=int(vel[k_]), on=bool(has_on), off=bool(has_off)))
                    if has_off: cur_last = e
            last_p.append(cur_last)
        start_pos = [max(k_ - step // HOP, 0) for k_ in last_p]
        events.sort(key=lambda z: (z["start"], z["end"], z["pitch"]))
        for ev in events:
            ev["start"] = max(ev["start"] + begin, 0); ev["end"] = max(ev["end"] + begin, ev["start"])
            lst = by_pitch.setdefault(ev["pitch"], [])
            if lst and ev["start"] < lst[-1]["end"]:
                if ev["on"]: lst[-1] = ev
                else: lst[-1]["off"] = ev["off"]; lst[-1]["end"] = max(ev["end"], lst[-1]["end"])
                continue
            if ev["on"]: lst.append(ev)
    for lst in by_pitch.values():
        if lst: lst[-1]["off"] = True
    notes = sorted([e for l in by_pitch.values() for e in l if e["off"]], key=lambda z: (z["start"], z["end"], z["pitch"]))
    last = {}
    for idx, ev in enumerate(notes):
        if ev["pitch"] in last and notes[last[ev["pitch"]]]["end"] > ev["start"]: notes[last[ev["pitch"]]]["end"] = ev["start"]
        last[ev["pitch"]] = idx
    return [e for e in notes if e["start"] < e["end"] and e["pitch"] > 0]

worst = 1.0
for name in sorted(audio)[:6]:
    t0 = time.time(); mine = port(audio[name]); dt = time.time() - t0
    ref = json.load(open(os.path.join(out, "reference_tk", name + ".json")))
    a = f1([(e["pitch"], e["start"], e["end"]) for e in mine], [(r["pitch"], r["start"], r["end"]) for r in ref], tol=0.01)[2]
    worst = min(worst, a)
    print(f"TKPORT {name}: decomposed vs official F1 {a:.3f} ({len(mine)} vs {len(ref)} notes, {dt:.1f} s)", flush=True)
print(f"TKPORT worst {worst:.3f}")
