# Android speech runtime

Official sherpa-onnx v1.13.8 AAR, unmodified:
https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

SHA-256: 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96

Gradle filters native packaging to arm64-v8a. Apache-2.0 sherpa and MIT ONNX Runtime license texts are in ../src/main/assets/licenses. Kotlin configuration/JNI classes are kept by ProGuard. Qwen Apache-2.0 weights are downloaded separately from the pinned revision in QwenModelStore.kt, never bundled in the APK.

## Bundled GTCRN enhancement model

- Model: https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx
- SHA-256: e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534
- Upstream: https://github.com/Xiaobin-Rong/gtcrn (MIT; copied to assets/licenses/GTCRN-MIT.txt).
- Runtime: existing sherpa-onnx 1.13.8 OnlineSpeechDenoiser, CPU with one thread. No extra native runtime added.
- Device-test fixture: https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/speech_with_noise.wav (androidTest assets only; excluded from the app APK).
