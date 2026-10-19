# Vendored whisper.cpp

Upstream: https://github.com/ggml-org/whisper.cpp
Tag: v1.9.4
Commit: 927cfce34f31707e17f2bff35c349632fb9e2c3a

Copied build inputs: CMakeLists.txt, LICENSE, cmake/, include/, src/, ggml/.
Upstream code is unmodified. MIT license is retained in whisper/LICENSE.
Models are separate downloads from https://huggingface.co/ggerganov/whisper.cpp.
The model catalog pins file size and SHA-256; replacing model artifacts requires reviewing and updating both.

This personal Android build targets arm64-v8a with ARMv8.2 FP16 and dot-product instructions (Snapdragon 8 Gen 3 supports these). It is not a universal APK for older ARM64 or 32-bit devices. GPU/NPU offload is not enabled.
