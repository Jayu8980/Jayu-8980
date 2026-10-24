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

# Fold each acoustic model's fc5 -> BatchNorm1d into one Linear: same maths, and it avoids an
# ONNX Runtime fusion (MatMulBnFusion) that breaks on this transposed layout.
def fold_fc5_bn5(am):
    bn, fc = am.bn5, am.fc5
    scale = bn.weight.data / torch.sqrt(bn.running_var.data + bn.eps)
    folded = torch.nn.Linear(fc.in_features, fc.out_features, bias=True)
    folded.weight.data = fc.weight.data * scale[:, None]
    folded.bias.data = bn.bias.data - bn.running_mean.data * scale
    am.fc5 = folded
    am.bn5 = torch.nn.Identity()

for am in (note.frame_model, note.reg_onset_model, note.reg_offset_model, note.velocity_model):
    fold_fc5_bn5(am)

class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, audio):
        d = self.m(audio)
        return d["reg_onset_output"], d["reg_offset_output"], d["frame_output"], d["velocity_output"]

fp32 = os.path.join(out, "piano_fp32.onnx")
with torch.no_grad():
    torch.onnx.export(Wrap(note), torch.zeros(1, SEG), fp32, input_names=["audio"],
                      output_names=["onset", "offset", "frame", "velocity"], opset_version=17, do_constant_folding=True)
onnx.checker.check_model(fp32)
int8 = os.path.join(out, "piano_int8.onnx")
quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8)
for p in (fp32, int8):
    print(f"MODEL {os.path.basename(p)} {os.path.getsize(p) / 1e6:.1f} MB")

# ---- 2. Check the ONNX graphs against PyTorch on one real segment ----
first = sorted(f for f in os.listdir(eval_dir) if f.endswith(".flac"))[0]
y, _ = librosa.load(os.path.join(eval_dir, first), sr=16000, mono=True)
seg = np.zeros((1, SEG), np.float32); seg[0, :min(SEG, len(y))] = y[:SEG]
with torch.no_grad():
    ref = [t.numpy() for t in Wrap(note)(torch.from_numpy(seg))]
    official = Regress_onset_offset_frame_velocity_CRNN(frames_per_second=100, classes_num=88)
    official.load_state_dict(ckpt["model"]["note_model"], strict=False); official.eval()
    off = [t.numpy() for t in Wrap(official)(torch.from_numpy(seg))]
    print("FOLD max abs diff vs official:", [float(np.abs(a - b).max()) for a, b in zip(ref, off)])
for p in (fp32, int8):
    s = ort.InferenceSession(p, providers=["CPUExecutionProvider"])
    t0 = time.time(); got = s.run(None, {"audio": seg}); dt = time.time() - t0
    errs = [float(np.abs(a - b).max()) for a, b in zip(ref, got)]
    print(f"PARITY {os.path.basename(p)} max abs diff onset/offset/frame/velocity {errs} ({dt:.2f} s per 10 s segment on this CPU)")

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
