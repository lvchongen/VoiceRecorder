"""Public fixture + synthetic-noise checks. pip install sherpa-onnx==1.13.8 numpy soundfile
Usage: python tools/benchmark_gtcrn.py MODEL.onnx NOISY.wav OUTPUT_DIR
Host RTF is not an Android performance claim; energy reduction is not an ASR accuracy score.
"""
import hashlib
import json
import platform
import sys
import time
from pathlib import Path
import numpy as np
import sherpa_onnx as sherpa
import soundfile as sf

model, fixture, destination = map(Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=True)
config = sherpa.OnlineSpeechDenoiserConfig(model=sherpa.OfflineSpeechDenoiserModelConfig(
    gtcrn=sherpa.OfflineSpeechDenoiserGtcrnModelConfig(model=str(model)), num_threads=1, provider="cpu"))

def run(samples):
    denoiser = sherpa.OnlineSpeechDenoiser(config)
    start = time.perf_counter()
    chunks = [np.asarray(denoiser(samples[i:i+320], 16000).samples) for i in range(0, len(samples), 320)]
    chunks.append(np.asarray(denoiser.flush().samples))
    output = np.concatenate(chunks)
    elapsed = time.perf_counter() - start
    assert len(output) == len(samples), (len(output), len(samples))
    assert np.isfinite(output).all()
    return output, elapsed

x, sr = sf.read(fixture, dtype="float32")
assert sr == 16000 and x.ndim == 1
y, elapsed = run(x)
assert np.mean((y-x)**2) > 1e-8
assert np.mean(y*y) > 1e-8
sf.write(destination / "noisy.wav", x, sr)
sf.write(destination / "enhanced.wav", y, sr)
# Long input uses bounded streaming frames, with a non-frame-aligned tail.
long = np.tile(x, 30)
_, long_elapsed = run(long)
rng = np.random.default_rng(7)
noise = rng.normal(0, 0.02, 16000*5+17).astype(np.float32)
filtered_noise, _ = run(noise)
silence, _ = run(np.zeros(16017, dtype=np.float32))
assert np.max(np.abs(silence)) < 0.001
attenuation = 10*np.log10(np.mean(noise[16000:]**2) / max(np.mean(filtered_noise[16000:]**2), 1e-20))
assert attenuation > 3, attenuation
report = {
    "platform": platform.platform(), "runtime": "sherpa-onnx 1.13.8 CPU, 1 thread",
    "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
    "fixture_seconds": len(x)/sr, "input_samples": len(x), "output_samples": len(y),
    "fixture_processing_seconds": elapsed, "fixture_rtf": elapsed/(len(x)/sr),
    "long_seconds": len(long)/sr, "long_processing_seconds": long_elapsed,
    "long_rtf": long_elapsed/(len(long)/sr), "synthetic_noise_attenuation_db": float(attenuation),
    "checks": ["exact sample count", "finite output", "nonzero speech fixture output", "silence", "synthetic noise reduction", "long streamed input"],
    "limits": "Mac test, not phone benchmark. Synthetic noise attenuation does not establish speech intelligibility or ASR improvement."
}
(destination/'results.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
