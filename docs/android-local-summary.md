# Android local summary implementation

`LocalSummaryModels.kt` is the source of truth for pinned catalog metadata. `LocalSummaryModelStore` reuses the size/SHA-256 verified atomic downloader and holds its lock for inference, preventing deletion of a model being used. AppContainer shares the store. Selection is stored independently from ASR, and LOCAL never falls back to a cloud provider. No automatic model download occurs.

`LocalSummaryClient` opens one model per summary request, closes it after completion/error/cancellation, and opens a new session for the optional title. It uses the common prompt policy and existing staged transcript/notes persistence. A missing model or inference failure leaves the saved transcript and previous notes available. Metadata records LOCAL plus the chosen model ID.

`LlamaTextEngine` renders the verified single-turn, no-thinking format for Qwen3.5, Qwen3 Instruct, Gemma4 and Phi4 mini. JNI handles tokenization, 256-token prefill batches and single-token decoding; Kotlin checks cancellation between native calls. UTF-8 pieces are combined as bytes before decoding. EOS completes output; exhausting the budget is an error. CPU only, four threads, 8192-token context. No live tokens are written into notes.

Long summaries use tokenizer-measured chunks with prompt/output headroom, extract facts, then reduce up to six rounds before the final template. Every input character is covered; output quality is not guaranteed by coverage. Translation chunks are concatenated without reduction. Oversized prompts and nonshrinking reductions fail explicitly. Saved transcripts remain unchanged.

## Build

The first native build downloads a SHA-256-pinned llama.cpp archive from GitHub (revision in `local_llama.cmake`). Subsequent builds reuse it. It is compiled as a separate static dependency graph from vendored Whisper; the JNI library hides archive symbols and binds local symbols to prevent cross-version GGML interposition. Build needs network on the first run, Android NDK/CMake versions in app/build.gradle.kts, and JDK17. llama.cpp MIT notice is bundled in assets/licenses.

## Tests

Run `android/gradlew -p android testDebugUnitTest ktlintCheck assembleDebug assembleDebugAndroidTest` with ANDROID_HOME and JAVA_HOME set. JVM tests cover routing/key independence, all catalog hashes/revisions, corrupt downloads, chat envelopes, exact text coverage including Unicode, multi-chunk final synthesis and nonreducing translation. These tests do not prove model quality or native compatibility.

Opt-in device test uses synthetic meeting text only, no microphone/user meeting/cloud call. Install app/test APKs with `adb install -r` to retain data. Download a catalog model through Settings, then run:

```sh
adb shell am instrument -w -r \
  -e class com.github.meetingrecorder.LocalSummaryDeviceTest \
  -e localSummarySmoke true -e summaryModel qwen35-2b \
  com.github.meetingrecorder.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as com.github.meetingrecorder cat cache/local-summary-smoke.txt
```

Replace summaryModel with a catalog ID for other models. The test checks native load, Chinese meeting content, context reset for a subsequent title and resource cleanup. It is not a benchmark of factual fidelity, long meetings, all six model variants or thermal behavior. The default suite skips it unless explicitly enabled.

## Sources

- https://github.com/ggml-org/llama.cpp
- https://huggingface.co/Qwen/Qwen3.5-4B
- https://ai.google.dev/gemma/docs/core/model_card_4
- https://huggingface.co/microsoft/Phi-4-mini-instruct
- Exact quantized file URLs, revisions and sizes: LocalSummaryModels.kt

Android transcription and summary services use dropdown selectors. Settings show provider-specific fields only inside the selected transcription or summary service. Switching providers preserves hidden credentials and model choices; Gemini settings share the same saved key/model across both stages.
