# Meeting Recorder Project (GEMINI.md)

## Git workflow — IMPORTANT

**Never push directly to `main`.** Always work on a feature branch and open a pull request so the GitHub Actions CI pipeline can run tests before merging.

1. Create a branch from the latest `main`:
   ```bash
   git checkout main && git pull
   git checkout -b <descriptive-branch-name>
   ```
2. Commit changes on the branch.
3. Push the branch and open a PR targeting `main`:
   ```bash
   git push -u origin <descriptive-branch-name>
   gh pr create --base main --title "..." --body "..."
   ```
4. Wait for CI to pass. If the CI run surfaces failures, or reviewers leave comments,
   validate each against the actual code — reviewers can be stale or wrong. Address the
   valid ones with commits on the same branch; reply to invalid/stale ones explaining why.
   Resolve the review threads you have handled (GraphQL `resolveReviewThread`), don't just
   reply.
5. **Never merge a PR — merging is always the user's decision and action**, even when CI
   is green and all review comments are addressed. Stop when the PR is ready and report
   its URL.
6. After the user merges, releases are tagged from `main` (never from a feature branch);
   the auto-release workflow handles this based on which directories changed
   (v* for Linux, android-* for Android).

**One PR per prompt:** create exactly one pull request per user request, even when the
work is large. Use multiple commits on the same branch for reviewability instead of
fanning out into many small PRs — only split when the user explicitly asks.

This applies to all agents (Claude, Gemini, etc.) — no direct pushes to `main`, and no
merges, under any circumstances.

---

## Keep documentation in sync — IMPORTANT

Whenever a change affects user-facing behavior, features, architecture, commands, conventions, or test boundaries, update the relevant docs **in the same PR** so they never drift from the code:

- `README.md` — user-facing features, setup, and workflows (Linux and Android sections)
- `CLAUDE.md` and `GEMINI.md` — architecture, commands, conventions, and test-coverage boundaries

Before opening a PR, re-read these three files and reconcile anything the change made inaccurate (new screens/services, renamed flows, new settings, new tests, changed defaults). Treat doc updates as part of "done," not a follow-up.

---

## Keep tests meaningful — IMPORTANT

For every change, add or update tests when doing so is meaningful — treat it as part of "done," not a follow-up. "Meaningful" means the test would actually catch a regression in the behavior you changed:

- New or changed logic with a testable contract (parsing, decisions, data transforms, repository/IO, API request/response handling) → add or update unit tests that cover the new behavior and its edge cases.
- Fixing a bug → add a test that fails without the fix, so it can't silently regress.
- When the meaningful logic is tangled with hard-to-test platform code (Android ViewModels/Compose, GTK UI), **extract the pure logic into a standalone function and test that** — this is the established pattern (e.g. `RecordingStopDecision.kt` + `RecordingStopDecisionTest`, `GenerateActionDecision.kt` + `GenerateActionDecisionTest`).
- Run the relevant suite before opening a PR: `pytest` (Linux) and/or `./gradlew test` (Android).

Skip new tests only when a change genuinely has no testable behavior (docs, comments, pure formatting, trivial constant tweaks) — and say so briefly rather than silently omitting them.

---

## Never break user space — IMPORTANT

Backward compatibility is not optional. Every change must satisfy **both** of these:

- **Existing installs keep working.** A user who already has an older version installed must be able to upgrade without their setup breaking — don't invalidate existing config, stored API keys, on-disk recordings/metadata, or packaging state. When a format or default has to change, ship a migration or a compatible fallback rather than a breaking change.
- **Clean installs still work.** The change must also install and run correctly on a fresh system with no prior version present.

If a change genuinely cannot preserve compatibility, call it out explicitly and provide a migration path — never silently break an existing installation.

---

## Project Overview

This repository is a monorepo containing two applications: a Linux desktop applet and a native Android app. Both applications record audio, transcribe it, and generate structured notes using Google Gemini. The apps share the same storage format, allowing recordings to be accessed from both platforms.

### Technologies

*   **Linux App:**
    *   **Language:** Python
    *   **UI:** GTK4 + libadwaita (`Adw.Application`/`Adw.ApplicationWindow`, preference-row settings, toasts, dark-mode; async `Gtk.AlertDialog`/`Gtk.FileDialog` instead of blocking `run()`).
    *   **Base dependencies (`linux/requirements.txt`):** `google-genai`, `setproctitle` — Gemini-only, minimal.
    *   **Opt-in local engines (installed on demand from Settings → Models):** `faster-whisper` (NVIDIA/CPU) installed via pip; `whisper.cpp` built from source with the detected GPU backend (AMD ROCm/Vulkan, Apple Metal, NVIDIA CUDA, or CPU).
    *   **System tray:** a pure-DBus StatusNotifierItem (`org.kde.StatusNotifierItem` + `com.canonical.dbusmenu`) built on `Gio.DBusConnection` — no GTK widgets and no extra dependency (Gio ships with PyGObject). Left-click focuses the window where the SNI host delivers `Activate`, otherwise opens the menu. GNOME needs the AppIndicator/KStatusNotifierItem extension to provide the SNI host. The tray shows branded per-state artwork (idle microphone / record-dot / pause / processing) bundled in `assets/tray/` and sent as a raw ARGB `IconPixmap` so it renders on every host and from source.
    *   **App icon:** the launcher/window icon ships in `assets/icons/hicolor/` (scalable SVG + PNG sizes, named `meeting-recorder` — the `Icon=` key) and is installed into the hicolor theme by the install scripts and the Debian/RPM/Arch packaging; `ui/window_app.py:_setup_app_icon()` also registers the bundled tree on the GTK icon-theme search path and sets it as the default icon so it resolves from source. The installed desktop file is named after the application id (`io.github.dipakmdhrm.MeetingRecorder.desktop`, with `StartupWMClass`) so the GNOME/Wayland shell maps a running window to it and shows the app icon rather than a generic one.
*   **Android App:**
    *   **Language:** Kotlin
    *   **UI:** Jetpack Compose
    *   **Dependencies:** `androidx.compose`, `androidx.lifecycle`, `androidx.navigation`, `okhttp`, `coroutines`, `kotlinx-serialization-json` (all JSON handling — meeting.json and the Gemini wire format — uses `kotlinx.serialization` DTOs, not `org.json`)

### Architecture

> **Authoritative architecture documentation lives in `CLAUDE.md`** (Linux architecture,
> Android architecture, and test-coverage boundaries) and is kept in sync with the code
> on every PR. The summary below is intentionally brief — when it disagrees with
> CLAUDE.md, CLAUDE.md is right.

The project is structured as a monorepo with two main directories:

*   `linux/`: Contains the source code, tests, and packaging scripts for the Linux desktop app.
*   `android/`: Contains the source code, tests, and Gradle build files for the Android app.

**Linux runs as two processes (daemon/UI split):** a GTK-free **daemon** (`--daemon`, `daemon/`) owns the recording engine, jobs, pipeline, call detection and tray; the GTK **window** (`--window`, `ui/window_app.py`) is spawned as a child on demand and renders a snapshot fetched over the `io.github.dipakmdhrm.MeetingRecorder.Engine` D-Bus interface. Launching with no flag is **client** mode (`client.py`): ensure the daemon is up, then open a window. `__main__.py` dispatches via `core/run_mode.py`. By default the window hides on close and stays resident for instant reopen (pure policy `core/window_close.py:resolve_close_action`); an opt-in **Low memory mode** setting instead exits the window on close so GTK is loaded only while visible (~20 MB idle in tray vs. ~100 MB). A spawned window never outlives its daemon: it watches the Engine bus name and exits when the daemon quits/crashes (pure `core/daemon_watch.py:should_exit_on_owner_change`), and the daemon `force_exit`s its window child on quit — so a hidden window can't linger and double up on the next daemon's `PresentWindow`. Two more short-lived child roles keep heavy/long work out of the daemon: `--process` (one AI transcription+summarization job) and `--install` (one model/engine install), both spawned and tracked by the daemon and streamed back over D-Bus, so they survive the window closing and don't bloat the daemon. See CLAUDE.md for the full description.

Both apps use Google Gemini for transcription and summarization. The Linux app also supports local transcription with Whisper (`faster-whisper`, NVIDIA/CPU) or whisper.cpp (built from source for AMD/Apple/NVIDIA/CPU GPU acceleration) and local summarization with Ollama. These local engines are not in the base install — they are installed on demand from Settings → Models, keeping a fresh install Gemini-only. The Linux app runs on both x86_64 and arm64.

The Android app records in a foreground service so capture survives interruptions, warns (instead of transcribing) when the OS silences the mic mid-call, and keeps failed/crashed recordings in the library so they can be re-processed. Transcript and notes can be generated, or notes regenerated, directly from a meeting's detail screen, and external audio files can be imported via "Use Existing Recording."

## Building and Running

### Linux App

**Running from Source:**

1.  Create a Python virtual environment:
    ```bash
    python3 -m venv .venv --system-site-packages
    ```
2.  Install dependencies:
    ```bash
    .venv/bin/pip install -r linux/requirements.txt
    ```
3.  Run the application:
    ```bash
    PYTHONPATH=linux/src python3 -m meeting_recorder
    ```

**Running Tests:**

1.  Install pytest:
    ```bash
    pip install pytest
    ```
2.  Run the tests:
    ```bash
    pytest
    ```

### Android App

**Building a Debug APK:**

1.  Navigate to the `android` directory:
    ```bash
    cd android
    ```
2.  Run the `assembleDebug` Gradle task:
    ```bash
    ./gradlew assembleDebug
    ```
    The APK will be located at `android/app/build/outputs/apk/debug/app-debug.apk`.

**Running Unit Tests:**

1.  Navigate to the `android` directory:
    ```bash
    cd android
    ```
2.  Run the `test` Gradle task:
    ```bash
    ./gradlew test
    ```

## Development Conventions

### Continuous Integration

The project uses GitHub Actions for CI. The CI pipeline, defined in `.github/workflows/ci.yml`, runs the following checks on every pull request to the `main` branch:

*   **Unit Tests:** Runs Python unit tests for the Linux app on Python 3.10 and 3.12.
*   **Package Build Smoke Tests:** Builds `.deb`, `.rpm`, and `.pkg.tar.zst` packages for the Linux app to verify the packaging toolchain.
*   **Android Debug Build:** Compiles the Android app and runs its unit tests.

### Release Process

The repository has two release workflows defined in `.github/workflows/`:

*   `release.yml`: Triggered by tags matching `v*` (e.g., `v1.2.0`). It builds and releases the Linux packages to a GitHub Release.
*   `release-android.yml`: Triggered by tags matching `android-*` (e.g., `android-1.0.0`). It builds and releases a signed Android APK to a GitHub Release.

## Android transcription and summary providers

`TranscriptionClient` and `SummaryClient` separate the two stages; provider selectors read persisted settings. Existing preferences default to Gemini, while the new summary language defaults to paired Chinese/English. Alibaba + DeepSeek requires no Gemini key. Keys and regional base URL are separate preferences. Saved prompt overrides remain intact; language policy is prepended to summary prompts.

`AliTranscriptionClient` sends sequential WAV segments to the Qwen3-ASR OpenAI-compatible endpoint. `AndroidAudioChunks` uses MediaExtractor/MediaCodec (raw PCM bypasses decoding); `PcmChunker` builds bounded PCM16 WAV segments without dropping boundary samples. These are segment timestamps, not speaker labels. Fixed boundaries may affect recognition accuracy. `DeepSeekClient` receives text only. Empty or truncated outputs fail instead of being treated as complete.

`AliTranscriptionClientTest`, `PcmChunkerTest`, `DeepSeekClientTest`, `SummaryProviderTest` and `MeetingProcessorTest` cover protocol, key requirements, framing, provider separation and source-versus-summary persistence. Native decoder execution requires device testing; selected Compose behavior is covered by Robolectric. Launcher icons use vector adaptive layers with an Android 13 monochrome layer.

## Android native Whisper and Markdown

`WhisperTranscriptionClient` uses the JNI bridge in `app/src/main/cpp/whisper_jni.cpp`; the pinned upstream sources and license are under `vendor/`. `WhisperModelStore` serializes model operations/inference, verifies size/SHA-256 before installing, and never backs up model binaries. Catalog/default selection live in `WhisperModels.kt`/`Config`. AppContainer owns the shared store. ARM64 native CPU target requires ARMv8.2 FP16/dotprod; no GPU/NPU path is enabled. Cancellation signals an atomic native abort flag; freeing a context waits until inference has returned.

`MarkdownRenderer` is the common rendering seam for `MeetingDetailScreen` and Robolectric tests. It uses Markwon core, tables and task lists. Source copying retains Markdown; no network image plugin or WebView. Regression tests require the Android 34 Robolectric runtime and use a plain Application to avoid unrelated storage initialization. Pure JVM tests cover model download integrity, 16 kHz conversion and key requirements; actual native inference and thermal performance need device validation.

Theme mode is a persisted StateFlow owned by shared Config; Settings updates it immediately and MainActivity observes it for Compose palette and system bar icons. MarkdownRenderer is recreated with the matching native context theme and source is rebound when text color changes. ThemePreferencesTest verifies immediate state, persistence and preservation of existing keys.

## Android Qwen3-ASR and Chinese settings

Qwen3-ASR 0.6B INT8 appears as a radio-selectable model inside the existing Whisper settings list, with download/delete actions. The legacy QWEN provider is automatically migrated to WHISPER plus the Qwen model ID; files and keys remain intact. SelectedTranscriptionClient dispatches that ID to the Qwen engine. Existing provider selections and keys are preserved. The six model/tokenizer files (~941 MiB) are pinned to a Hugging Face revision and each verified by byte length/SHA-256. Completed files are reused after interrupted downloads; partial files are removed. Models live under noBackupFilesDir. `QwenModelStore` serializes download/delete/inference.

The official sherpa-onnx 1.13.8 Android AAR provides ARM64 CPU inference (up to four threads); no NPU acceleration. `QwenTranscriptionClient` decodes bounded 20-second segments after mono/16kHz conversion, preserving original-language text and segment timestamps. Fixed boundaries may affect words. Cancellation takes effect between native segments, not within a native decode; the decoder is released only after native work ends. Tests cover package integrity/retry, segmentation, resource cleanup, empty output and key independence. Actual Android native inference, mixed-language accuracy and thermal performance still require device testing.

Settings labels/options are Chinese (service names/model identifiers retain their original spelling). Removed explanatory local-key/upload-routing paragraphs. New dependency provenance is in `android/app/libs/README.md`; license texts are bundled under assets/licenses.

## Android processing checkpoints

`MeetingProcessor.transcribeAndSummarize` atomically saves transcript.md before summarization and notes.md before returning for optional title generation. Callers pass the destination meeting directory; `onTranscriptSaved` publishes the durable transcript to detail UI immediately. Notes-only generation saves before title generation. Imported audio and completed stages survive downstream failures; only incomplete copies are removed. Detail progress/errors sit above all tabs, with existing content readable and generation actions disabled during processing. Tasks remain ViewModel-scoped: checkpoints survive restart, but active inference does not resume automatically after process death.

## Android real-time recording denoising

The optional Chinese setting “录音实时降噪” defaults off; DPDFNet8 with a fixed 6 dB suppression limit is the default model, and GTCRN remains selectable. Existing MediaRecorder recording remains the off path. When enabled, `RealtimeRecorder` captures mono PCM16 at 16 kHz, encodes the original as recording.m4a, and sends copied 20 ms frames to a bounded `DenoisingBranch`. The selected model runs on a separate worker via the existing sherpa-onnx 1.13.8 JNI runtime. DPDFNet8 uses four CPU threads and aligned suppression limiting; GTCRN uses one CPU thread. `AacPcmSink` encodes each stream independently; timestamps derive from sample counts. A successful flush and exact input/output sample count precede atomic publication of recording-denoised.m4a from a partial file. Any enhancement failure or queue saturation discards that enhanced file and preserves the original; a saved warning appears in Audio. Detail playback selects either version, while automatic processing prefers the completed enhanced file. Changing playback version alone does not regenerate the transcript.

Recording service start/stop run on one serialized executor, and its partial wake lock is held only during recording/finalization. This helps locked-screen capture but is not protection against forced termination or manufacturer restrictions. No ASR/download background worker is added. Two AAC files use extra storage; enhanced mode captures 16 kHz speech audio rather than the normal 44.1 kHz path.

`DenoisingBranchTest` covers buffer ownership, flush/tail preservation, sample loss, failures, bounded-queue overload and file fallback. `RealtimeDenoisingDeviceTest` exercises the bundled model/JNI, AAC encoding and Android decode using a public noisy fixture; it must run on an ARM64 device via connectedDebugAndroidTest. Compilation alone does not validate native execution. `tools/benchmark_gtcrn.py` provides a host-native streaming fixture/noise benchmark; host RTF and synthetic-noise attenuation are not phone or ASR benchmarks. Microphone capture, real-time throughput, playback switching, screen lock and battery/thermal behavior require device testing.

Device validation on 2026-10-19: PKG110/API 35 passed public-fixture JNI/AAC round-trip, sustained streaming and NoiseSuppressor capability probes. Optional `MicrophoneDenoisingDeviceTest` requires explicit instrumentation argument `microphoneTest=true`; otherwise it skips without recording. Public-sample tests do not establish microphone/locked-screen behavior. Device reports live in deliverables/denoise-tests.

## Android manual processing after recording

Stopping a recording finalizes audio and meeting metadata, removes the recording lock, and shows AudioSaved with a library shortcut. It never invokes ASR or summarization. Recording does not require processing credentials. The former processing-countdown UI/state is removed; its legacy preference remains harmless and is ignored. Detail-screen generate actions explicitly start processing later. Existing import-and-process remains a user-initiated operation. RecordingStopDecisionTest and MeetingProcessorTest cover save-only dispatch, library visibility, audio preservation and zero network requests.


## Android summary templates and history

`data/SummaryOptions.kt` defines six meeting types, three detail levels and the shared cloud/local prompt builder. `Config.summaryTemplate` / `summaryDetail` default to GENERAL/STANDARD; existing language and custom prompts remain intact. Source line IDs are added in memory, never written into transcript.md. Mechanical reference validation rejects out-of-range P IDs and absent bracketed HH:MM:SS timestamps; do not describe this as factual verification.

`MeetingDetailViewModel` immediately saves generated notes through SummaryHistory.adopt for both notes-only and audio-first paths, updates visible notes and metadata, and archives the old version automatically. No generated preview/adoption step. Preview state is used only for explicitly browsing historical versions; restore/close controls use separate full-width rows. Generation or archive failure preserves prior notes. SummaryHistory snapshots retain source/options/provider/model/prompt and remain compatible with Linux.

`SummaryOptionsEditor` uses existing Material 3 dropdowns. TranscriptTimeline.kt sorts timestamped utterances stably for reading, preserves untimed text, and never fabricates timestamps. Normal Notes/Transcript reading hides generated [P#] markers without modifying stored history/source. History evidence can still show numbered source. SummaryPreviewTest covers automatic save and failure protection; SummaryControlsTest covers options/history controls. Per user instruction, this usability update is built and installed without running tests.

The optional local speaker pipeline is documented below; existing Gemini-only speaker labels are not independent verified voiceprints.


CI and Android release setup explicitly accept SDK/preview licenses and install the exact app NDK/CMake versions before Gradle configuration. Keep those versions aligned with app/build.gradle.kts. Speaker profiles support multiple samples per person; official multi-enrollment support is not evidence of illness robustness without evaluation.


## Android local speakers and voice profiles

`speakers/` owns local diarization, embeddings, meeting-local A/B/C and automatic cross-meeting voice profiles. Models prepare in an application-scoped background job at startup, and retry after recording save. AppContainer queues nonblocking analysis/enrollment after audio save and wraps ASR when models are ready. Missing models never block recording/transcription. SpeakerTranscriptionClient retries the original complete recording only for typed NoSpeechDetectedException; analysis failures also fall back to ordinary ASR. Cancellation/auth/network failures are not treated as empty speech.

`SpeakerService` decodes bounded chunks twice (analysis then turn ASR), never buffers an entire long recording. AudioChunk.startMilliseconds preserves fractional chunk offsets; legacy startSeconds consumers are unchanged. Native work is checked for cancellation before/after bounded windows and released before ASR. Overlapping detected turns become unassigned; short/quiet/clipped samples cannot enroll. Cross-window stitching uses heuristic cosine score 0.65 and candidate margin 0.08, not calibrated identity guarantees. A local cluster cannot consume a label already used by another cluster in the same window. Cache checks audio SHA, pipeline version and embedding model ID; changing model/stitching semantics requires changing PIPELINE_VERSION.

`SpeakerRepository` stores embeddings and 2–6-second clips in noBackupFilesDir; public speakers.json contains only an analysis ID. autoEnroll matches compatible profiles at cosine >=0.8 with margin >=0.1, excluding IDs already used in this meeting; unmatched eligible voices become numbered unnamed profiles. Matching is heuristic, not an identity guarantee. Cache processing is idempotent; deleted linked profiles are not recreated from that cache. Independent profile clips survive meeting deletion. Profile updates append samples; JSON writes and copy rollback remain atomic. Rename/delete never rewrite old transcripts/notes.

UI: no extra recording/save buttons or speaker tab. Meeting overflow opens voiceprint management; Settings retains its Voiceprints tab. VoiceprintPanel exposes direct-recording add/update, audition, rename, sample removal and deletion. Adding records first and asks for a name before creating a profile. Enrollment is automatic, without per-person confirmation or checkboxes. SpeakerViewModel owns IO/cancellation/player lifecycle and subscribes to repository profile changes so automatic enrollment refreshes an open manager. Keep existing Material 3 styling. Samples remain bounded at 3 per meeting speaker and 20 per profile.

Tests: SpeakerDataTest, SpeakerRepositoryTest, SpeakerServiceTest, SpeakerControlsTest, millisecond chunk/route regressions, plus opt-in SpeakerDeviceTest using staged public two-speaker audio in isolated app cache. Do not substitute self-similarity or synthetic fixtures for real cross-person/illness accuracy evaluation. Never enable microphone device tests implicitly. Device installs must use adb install -r, preserving existing app data.

### Android 模板提示词

设置 → 提示词中选择会议类型，会显示该模板独立的中文摘要提示词；修改后点击保存，可恢复当前模板默认值。生成时按实际所选模板读取配置，会议内临时选择也生效。转写与标题提示词独立于摘要模板。全局补充要求默认留空，兼容保留旧版自定义摘要提示词；模板自定义按枚举名存储，留空使用内置默认。摘要版本记录该模板提示词与全局补充要求。测试覆盖模板切换、独立持久化、恢复默认和原文插值。

ASR 生命周期：一次会议转写通过 `TranscriptionClient.withSession` 共用一个本地 Qwen/Whisper 实例，所有说话片段和空片段后的整段重试复用该实例，完成/失败/取消后释放。先完成声纹分析再加载 ASR，避免分人模型与 ASR 同时常驻。当前易用性修改按用户要求仅 build + adb install -r，不执行测试。

说话人计数只包含有有效声纹证据的分组。分人模型产生的过短/低质量碎片保留为未确认，不直接创建新字母或声纹档案；原始语音内容仍参与转写。分组策略版本升为 v2，使旧分组缓存下次处理时重算。ASR 与声纹在同一次用户操作中完成，内部仍为两个独立模型。

重新处理已有录音：会议右上角菜单及 Transcript 页提供“重新转写并生成”，直接使用当前保存的录音，重跑 ASR 和纪要生成，不重新录制；使用当前纪要的模板选项（没有记录时使用默认选项）。结果自动保存，旧纪要自动归档。菜单“仅重新生成纪要”复用现有 Transcript。处理中禁用重复操作。

新增“对话翻译”内容模板：独立中文提示词，保留说话人、时间戳、问答顺序并逐段翻译，不执行摘要概览/详细程度/行动项策略。支持原文＋中英互译、译为中文/英文/日文；原语言旧偏好在此模式按原文＋互译处理。重新转写及仅重新生成均先打开模板选择，默认沿用当前已保存版本的选项，确认后自动生成并保存。未确定说话人统一显示“未确认”，旧内容阅读时兼容替换旧标签。

### DPDFNet8 人声保留降噪

设置 → 通用 → 录音实时降噪 → 降噪方案，可选“DPDFNet8（人声保留，默认）”和“GTCRN（轻量）”。保存后对下一次录音生效；保留已有方案选择和降噪开关状态，开关默认关闭。DPDFNet8 使用 16 kHz 单声道、CPU 4 线程，GTCRN 使用 CPU 1 线程。不叠加 Android 系统 NS，不添加音量均衡或自动增益。

DPDFNet8 固定采用 6 dB 抑制上限：先补偿该模型在 sherpa-onnx 1.13.8 中四跳共 640 样本（40 ms）的输出延迟，再将对齐的原始 PCM 与增强 PCM 按约 50.1% / 49.9% 混合。不是直接回放原音，也不是给流式接口设置无效的 attenuationLimitDb；该字段仅在离线接口实现。流式结束补零排出延迟尾音，精确检查输入/输出样本数；只缓存待对齐片段，积压过大立即停止降噪分支。保留轻声的代价是保留部分残余噪声。参数和延迟绑定当前模型/runtime，升级时须重新验证。

保留原始 recording.m4a 和完整的 recording-denoised.m4a；初始化、队列积压、时长异常仍保护原音。自动转写优先完整降噪文件，已有录音不自动改写。denoise-model.txt 记录模型、6 dB 上限和 640 样本对齐参数。SpeechPreservingDenoiserTest 覆盖延迟对齐、过度抑制下的语音保留、任意块边界、短录音尾音和积压失败；DenoisingBranchTest 覆盖分支和文件完整性。原生模型质量和实时速度需要真机验证，JVM 测试不能代替。SpeechPreservingDeviceTest 是显式传入私有缓存 PCM16 WAV 样本和参考音频的本地回归，检查正式 ModelDenoiser 路径、AAC 编码、样本数、波形误差和实时速度；不启动麦克风、不修改会议。

已用用户授权的 14:32 整段录音作本地对照：原音转写 83 字，旧全强度降噪仅 13 字，离线 6 dB 候选恢复 83 字且与原音转写一致，用户试听认可。此为单个样本的转写一致性，不是带人工标注的准确率评测，也不代表所有会议环境。流式对齐候选与获认可离线候选的波形相关系数约 0.9999。私人录音与识别内容不提交仓库。

模型随 APK 附带，官方来源 https://huggingface.co/Ceva-IP/DPDFNet/resolve/dd6818d00f50c836fed43a6243ebe49116de5964/onnx/dpdfnet8.onnx ，14542517 bytes，SHA-256 899d4f23f3ff86edbffa8c537e4bcbdc49da1b4e84e0ef390611e0604a3b26cb。Apache-2.0 许可证附于 assets/licenses/DPDFNet-APACHE-2.0.txt（上游 ceva-ip/DPDFNet 9bd9844a227bb6aa57e55588d8d0e961fcff1c46）。

真机回归（2026-10-19，PKG110）：输入 491520 样本 / 30.72 秒，处理 11.94 秒（RTF 0.389），与认可离线候选的波形相关系数 0.999923，相对 RMS 误差 0.01243，完整样本数一致。仅代表该样本和本次设备状态；长期录音的发热/耗电未据此验证。覆盖安装使用 adb install -r，保留现有数据和降噪开关/模型选择。

声纹管理的“更新”直接打开录制弹窗，不再选择文件。用户显式开始后录制本人声音，建议 10–20 秒，满 5 秒可完成，30 秒自动结束并提交当前档案；补充样本而非覆盖已有样本。会议正在录音时禁止并行录制。取消、页面离开或应用进入后台停止录音并删除临时文件，交给 ViewModel 的录音在处理完成/失败/取消后清理。录音只在私有缓存中暂存，不创建会议或生成逐字稿/纪要。“添加”同样直接录制，先完成录音再填写姓名（1–80 字符），点击“保存声纹”后创建独立命名档案；不再打开文件选择器，也不自动合并到相似声纹。未填写姓名或取消不会创建档案。沿用单人、样本质量与档案容量检查，失败不改旧档案。SpeakerControlsTest 覆盖添加时先录制再命名的入口、更新入口和取消，不隐式启动麦克风测试。RecordedVoiceProfileTest 覆盖填写姓名后创建独立档案（相似声纹不覆盖已有身份）、姓名校验和临时录音清理。


## Android local summaries

When editing local-summary inference, downloads, settings or tests, read [docs/android-local-summary.md](docs/android-local-summary.md). LOCAL is a third summary provider; ASR selection is independent. Preserve existing provider defaults and credentials. Catalog revisions/hashes and no-thinking formats live in LocalSummaryModels.kt / LocalSummaryClient.kt. Native GGML symbols are isolated from Whisper; maintain that boundary when upgrading dependencies. JVM checks cannot substitute for opt-in native device tests. Model quality and memory/thermal claims require real-device evidence.

Android transcription and summary services use dropdown selectors. Settings show provider-specific fields only inside the selected transcription or summary service. Switching providers preserves hidden credentials and model choices; Gemini settings share the same saved key/model across both stages.
