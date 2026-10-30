"""CREPE pitch tracker (Kim et al. 2018; torchcrepe by Max Morrison, MIT licence) for on-device use:
both sizes exported to ONNX (frames [N, 1024] in, pitch-bin probabilities [N, 360] out), checked
against PyTorch, and reference pitch tracks of a test signal from torchcrepe's own pipeline for the
Kotlin port to match.

Usage: python export_crepe.py <out_dir>
Writes: out_dir/crepe_tiny.onnx, crepe_full.onnx, reference/test16k.f32 and per size
        reference/<size>_pitch.f32 (Hz, NaN-free) and <size>_periodicity.f32.
"""
import os, sys
import numpy as np
import torch
import onnx
import onnxruntime as ort
import torchcrepe

torch.set_grad_enabled(False)
out = sys.argv[1]
os.makedirs(os.path.join(out, "reference"), exist_ok=True)

# A test signal at 16 kHz: a glide 220 -> 440 Hz (3 s, five harmonics), 0.5 s silence, 1 s at 300 Hz.
sr = 16000
t = np.arange(int(3 * sr)) / sr
f = 220 * 2 ** (t / 3)
phase = 2 * np.pi * np.cumsum(f) / sr
glide = sum(np.sin(h * phase) / h for h in range(1, 6))
t2 = np.arange(sr) / sr
steady = sum(np.sin(2 * np.pi * 300 * h * t2) / h for h in range(1, 6))
audio = np.concatenate([glide, np.zeros(sr // 2), steady]).astype(np.float32) * 0.3
audio.astype("<f4").tofile(os.path.join(out, "reference", "test16k.f32"))

class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
    def forward(self, frames):
        return self.m(frames)

for cap in ["tiny", "full"]:
    torchcrepe.load.model("cpu", cap)
    m = torchcrepe.infer.model.eval()
    path = os.path.join(out, f"crepe_{cap}.onnx")
    torch.onnx.export(Wrap(m), torch.zeros(4, 1024), path, input_names=["frames"], output_names=["probs"],
                      opset_version=17, do_constant_folding=True, dynamic_axes={"frames": {0: "n"}, "probs": {0: "n"}})
    onnx.checker.check_model(path)
    x = torch.randn(32, 1024)
    diff = np.abs(ort.InferenceSession(path).run(None, {"frames": x.numpy()})[0] - m(x).numpy()).max()
    print(f"CREPE {cap}: {os.path.getsize(path) / 1e6:.1f} MB, max |onnx - torch| {diff:.2e}")
    pitch, per = torchcrepe.predict(torch.from_numpy(audio)[None], sr, hop_length=160, fmin=50.0, fmax=2000.0, model=cap,
                                    decoder=torchcrepe.decode.viterbi, return_periodicity=True, batch_size=512, device="cpu")
    pitch.numpy()[0].astype("<f4").tofile(os.path.join(out, "reference", f"{cap}_pitch.f32"))
    per.numpy()[0].astype("<f4").tofile(os.path.join(out, "reference", f"{cap}_periodicity.f32"))
    print(f"CREPE {cap}: reference {pitch.shape[1]} frames")
