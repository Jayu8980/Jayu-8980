"""Export the high-resolution piano transcription note model (Kong et al. 2021, ByteDance,
Apache-2.0 code / CC BY 4.0 weights) to ONNX for on-device use, quantise it, and write
reference transcriptions of the evaluation set with the official PyTorch pipeline.

Usage: python export_and_reference.py <checkpoint.pth> <eval_dir> <out_dir>
Writes: out_dir/piano_fp32.onnx, out_dir/piano_int8.onnx, out_dir/reference/*.json,
        out_dir/eval22k/*.f32 (evaluation audio, mono 22050 Hz float32, for the Kotlin tests).
"""
import json, os, sys, time
import numpy as np
import torch
import librosa
import soundfile as sf
import onnx
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from piano_transcription_inference import PianoTranscription
from piano_transcription_inference.models import Regress_onset_offset_frame_velocity_CRNN

ckpt_path, eval_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
SEG = 16000 * 10

# ---- 1. Export the note model (waveform in, 4 frame-wise outputs out) ----
ckpt = torch.load(ckpt_path, map_location="cpu")
note = Regress_onset_offset_frame_velocity_CRNN(frames_per_second=100, classes_num=88)
note.load_state_dict(ckpt["model"]["note_model"], strict=False)
note.eval()

class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, audio):
        d = self.m(audio)
        return d["reg_onset_output"], d["reg_offset_output"], d["frame_output"], d["velocity_output"]

raw = os.path.join(out, "piano_raw.onnx")
with torch.no_grad():
    torch.onnx.export(Wrap(note).eval(), torch.zeros(1, SEG), raw, input_names=["audio"],
                      output_names=["onset", "offset", "frame", "velocity"], opset_version=17, do_constant_folding=True)
onnx.checker.check_model(raw)

def batchnorm_to_affine(path):
    """Rewrites each BatchNormalization that does not follow a Conv as Mul + Add with folded
    constants: exactly the same maths, and it removes the MatMul -> BatchNorm pattern that ONNX
    Runtime's MatMulBnFusion mishandles on this model (rank-3 input). Conv + BatchNorm stays, so
    the usual Conv-BN fusion still applies."""
    from onnx import helper, numpy_helper, shape_inference
    m = shape_inference.infer_shapes(onnx.load(path))
    g = m.graph
    inits = {i.name: i for i in g.initializer}
    producer = {o: n for n in g.node for o in n.output}
    ranks = {vi.name: len(vi.type.tensor_type.shape.dim) for vi in list(g.value_info) + list(g.input) + list(g.output)}
    nodes, changed = [], 0
    for n in g.node:
        src = producer.get(n.input[0]) if n.op_type == "BatchNormalization" else None
        if src is None or src.op_type == "Conv":
            nodes.append(n); continue
        eps = next((a.f for a in n.attribute if a.name == "epsilon"), 1e-5)
        gamma, beta, mean, var = (numpy_helper.to_array(inits[i]) for i in n.input[1:5])
        scale = gamma / np.sqrt(var + eps)
        shift = beta - mean * scale
        rank = ranks.get(n.input[0], 3)
        shape = [1, len(scale)] + [1] * (rank - 2)
        sn, bn_ = n.name + "_scale", n.name + "_shift"
        g.initializer.extend([numpy_helper.from_array(scale.reshape(shape).astype(np.float32), sn),
                              numpy_helper.from_array(shift.reshape(shape).astype(np.float32), bn_)])
        tmp = n.output[0] + "_scaled"
        nodes.append(helper.make_node("Mul", [n.input[0], sn], [tmp], name=n.name + "_mul"))
        nodes.append(helper.make_node("Add", [tmp, bn_], [n.output[0]], name=n.name + "_add"))
        changed += 1
    del g.node[:]
    g.node.extend(nodes)
    onnx.checker.check_model(m)
    onnx.save(m, path)
    print(f"BATCHNORM {changed} BatchNormalization nodes rewritten as Mul + Add")

batchnorm_to_affine(raw)

def optimize(src, dst):
    """Optimise offline; the app loads the result with optimisation off. EXTENDED, not ALL, so
    no CPU-specific layouts end up in the file."""
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    so.optimized_model_filepath = dst
    ort.InferenceSession(src, so, providers=["CPUExecutionProvider"])

fp32 = os.path.join(out, "piano_fp32.onnx")
optimize(raw, fp32)
# int8 for the big matrix products only: the CPU backend has no int8 version of the
# spectrogram's convolution, and GRUs are not dynamically quantised.
q = os.path.join(out, "piano_int8_raw.onnx")
quantize_dynamic(raw, q, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
int8 = os.path.join(out, "piano_int8.onnx")
optimize(q, int8)
for p in (raw, q):
    os.remove(p)
for p in (fp32, int8):
    print(f"MODEL {os.path.basename(p)} {os.path.getsize(p) / 1e6:.1f} MB")

# ---- 2. Check the ONNX graphs against PyTorch on one real segment ----
first = sorted(f for f in os.listdir(eval_dir) if f.endswith(".flac"))[0]
y, _ = librosa.load(os.path.join(eval_dir, first), sr=16000, mono=True)
seg = np.zeros((1, SEG), np.float32); seg[0, :min(SEG, len(y))] = y[:SEG]
# torch.onnx.export restores the wrapper's training flag on every submodule afterwards, so
# compute the reference in eval mode explicitly.
note.eval()
with torch.no_grad():
    ref = [t.numpy() for t in Wrap(note).eval()(torch.from_numpy(seg))]

no_opt = ort.SessionOptions()
no_opt.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
for p in (fp32, int8):
    s = ort.InferenceSession(p, no_opt, providers=["CPUExecutionProvider"])
    s.run(None, {"audio": seg})
    t0 = time.time(); got = s.run(None, {"audio": seg}); dt = time.time() - t0
    errs = [float(np.abs(a - b).max()) for a, b in zip(ref, got)]
    print(f"PARITY {os.path.basename(p)} max abs diff onset/offset/frame/velocity {errs} ({dt:.2f} s per 10 s segment on this CPU)")
    if p == fp32 and max(errs) > 1e-3:
        sys.exit("fp32 ONNX does not match PyTorch")

# ---- 3. Reference transcriptions with the official pipeline ----
tr = PianoTranscription(device="cpu", checkpoint_path=ckpt_path)
os.makedirs(os.path.join(out, "reference"), exist_ok=True)
os.makedirs(os.path.join(out, "eval22k"), exist_ok=True)
for f in sorted(os.listdir(eval_dir)):
    if not f.endswith(".flac"):
        continue
    name = f[:-5]
    y16, _ = librosa.load(os.path.join(eval_dir, f), sr=16000, mono=True)
    res = tr.transcribe(y16, None)
    notes = [{"pitch": int(e["midi_note"]), "start": float(e["onset_time"]), "end": float(e["offset_time"]), "velocity": int(e["velocity"])}
             for e in res["est_note_events"]]
    json.dump(notes, open(os.path.join(out, "reference", name + ".json"), "w"))
    y22, _ = librosa.load(os.path.join(eval_dir, f), sr=22050, mono=True)
    y22.astype("<f4").tofile(os.path.join(out, "eval22k", name + ".f32"))
    print(f"REFERENCE {name}: {len(notes)} notes")
