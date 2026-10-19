# Meeting Recorder

A meeting recorder that transcribes audio and generates structured notes using Google Gemini.

This repository is a monorepo with two independent apps — a Linux desktop applet and a native Android app — that share the same storage format so recordings are accessible from both.

| Path | Contents |
|---|---|
| `linux/` | GTK4 + libadwaita desktop applet (Debian / Ubuntu / Fedora / Arch) |
| `android/` | Native Android app (Kotlin + Jetpack Compose) |

---

## Linux App

### Features

- **Record** system audio + microphone simultaneously, or microphone only
- **Transcribe** with Google Gemini or local Whisper (timestamped, speaker-labeled transcript)
- **Summarize** into structured Markdown notes with Google Gemini or DeepSeek (Android) or local Ollama
- **Meetings browser** — the Library tab lists past meetings with the day of the week, date, time, and duration; rename or delete them
- **Summarize from the library** — re-run summarization for any past meeting from the meetings browser
- **Local models** — run fully offline with no API key required
- **Customizable prompts** — edit transcription and summarization prompts in Settings
- **System tray** integration — a StatusNotifierItem (SNI) exposed over D-Bus; left-click opens the window where the host supports it, otherwise opens the menu
- **Lightweight background daemon** — the app runs as a small GTK-free tray daemon, so recording, transcription, and model installs keep running in the background even with no window open. Closing the window returns to the tray; reopening (tray "Open" or the app icon) shows the current state (an in-progress model install still shows its progress).
- **Low memory mode** — by default the window stays loaded in the background so reopening is instant; enable Low memory mode (Settings → General) to unload it on close instead, so the tray daemon idles at roughly a fifth of the memory at the cost of a brief delay when you reopen
- **Call detection** — optionally monitor for active calls and get notified to start recording
- **Start at system startup** — optionally launch the tray daemon automatically on login

### Output Structure

Each recording session creates a folder:

```
~/meetings/
└── 2026/
    └── March/
        └── 04/
            └── 14-30_Standup/
                ├── recording.mp3
                ├── transcript.md
                └── notes.md
```

### Requirements

- Linux with a supported package manager: **apt** (Debian/Ubuntu/Mint), **dnf** (Fedora/RHEL), or **pacman** (Arch/Manjaro). Works on both **x86_64** and **arm64/aarch64**.
- System packages installed by `linux/install.sh`: `ffmpeg`, `pulseaudio-utils`, `pipewire-pulse`, Python 3 with GTK4 + libadwaita bindings

> **Look & theming:** the app uses **libadwaita**, so it follows your system **light/dark** preference and renders in the Adwaita style. On non-GNOME desktops (KDE, XFCE, Cinnamon, …) it still runs perfectly but keeps the Adwaita look rather than matching a custom desktop theme — this is libadwaita's intended behavior.
- Python packages (installed into a venv): see `linux/requirements.txt`

The base install is **Gemini-only and minimal** — no local engines or GPU libraries are installed by default. Each local option below is installed **on demand** from **Settings → Models** when you choose it.

| Service | Requirement |
|---|---|
| **Gemini** (transcription or summarization) | Free API key from [aistudio.google.com](https://aistudio.google.com) — no local install |

> Your API key is stored in the system keyring (GNOME Keyring / KWallet) when one is available, falling back to a permission-restricted config file otherwise.
| **Whisper** (local transcription) | Engine (`faster-whisper`) installed on opt-in; model downloaded from HuggingFace (~500 MB – 3 GB); **NVIDIA GPU or CPU** |
| **whisper.cpp** (local transcription, GPU) | Engine built from source on opt-in with the detected backend — **AMD (ROCm/Vulkan), Apple (Metal), NVIDIA (CUDA), or CPU**; GGML model downloaded from HuggingFace |
| **Ollama** (local summarization) | [Ollama](https://ollama.com) installed and running (`ollama serve`); uses NVIDIA/AMD/Apple GPU automatically |

### Installation

#### Option 1: native package (recommended)

Download the package for your distro from the [Releases](../../releases) page.

**Debian / Ubuntu / Mint (.deb)**
```bash
sudo dpkg -i meeting-recorder_*.deb
sudo apt-get install -f   # installs any missing dependencies
# To uninstall:
sudo apt remove meeting-recorder
```

**Fedora / RHEL / openSUSE (.rpm)**
```bash
sudo dnf install ./meeting-recorder-*.rpm
# To uninstall:
sudo dnf remove meeting-recorder
```

**Arch / Manjaro (.pkg.tar.zst)**
```bash
sudo pacman -U meeting-recorder-*.pkg.tar.zst
# To uninstall:
sudo pacman -R meeting-recorder
```

All packages set up a Python venv at `/opt/meeting-recorder/venv` on first install with **only the Gemini-ready essentials**. The local transcription engines (Whisper / whisper.cpp), Ollama, and GPU runtimes (CUDA / ROCm) are installed later, on demand, from **Settings → Models**. The `.deb` and apt repository are architecture-independent (`all`) and work on both amd64 and arm64.

#### Option 2: install.sh (from source)

```bash
git clone <repo-url>
cd meeting-recorder
linux/install.sh
```

`linux/install.sh` detects your package manager (apt / dnf / pacman) and installs all system dependencies, then creates a Python venv with all required packages.

To uninstall:

```bash
linux/uninstall.sh
```

Then launch either way:

```bash
meeting-recorder
# or from your application menu: "Meeting Recorder"
```

> **GNOME users:** The tray is a StatusNotifierItem (SNI), and GNOME has no built-in SNI host, so the icon needs the AppIndicator/KStatusNotifierItem extension to appear. `install.sh` installs it automatically; if you installed via a native package, install it manually:
> ```bash
> # Debian/Ubuntu
> sudo apt install gnome-shell-extension-appindicator
> # Fedora
> sudo dnf install gnome-shell-extension-appindicator
> # Arch
> sudo pacman -S gnome-shell-extension-appindicator
> ```
> Then enable it in the GNOME Extensions app and log out/in.
>
> Whether **left-click focuses the window** or **opens the menu** is decided by the SNI host: hosts that deliver the `Activate` action (e.g. KDE Plasma) focus the window, while the GNOME extension typically opens the menu on any click. KStatusNotifierItem-capable panels on XFCE, MATE, Cinnamon, KDE, LXQt, … show the icon natively without an extension.

### Running from Source

```bash
cd meeting-recorder
python3 -m venv .venv --system-site-packages
.venv/bin/pip install -r linux/requirements.txt
PYTHONPATH=linux/src python3 -m meeting_recorder
```

`python3 -m meeting_recorder` (no flag) is **client** mode: it starts the background
daemon if needed and opens a window. To run the pieces directly, use
`python3 -m meeting_recorder --daemon` (the GTK-free tray daemon) and
`python3 -m meeting_recorder --window` (the GTK window, normally spawned by the daemon).

### Recording Modes

| Mode | What is captured | When to use |
|------|-----------------|-------------|
| **Record (Headphones)** | Microphone + system audio (calls, browser, etc.) | You're wearing headphones — no echo risk |
| **Record (Speaker)** | Microphone only | Laptop speakers — avoids loopback echo |

### Services

#### Transcription

| Service | How it works | Requires |
|---|---|---|
| **Google Gemini** | Audio sent to Gemini API | API key |
| **Whisper** | Runs locally via `faster-whisper` | Engine installed + model downloaded in Settings → Models; NVIDIA GPU or CPU |
| **whisper.cpp** | Runs locally via a from-source whisper.cpp build | Engine built + GGML model downloaded in Settings → Models; **GPU on AMD / Apple / NVIDIA / Vulkan**, or CPU |

#### Summarization

| Service | How it works | Requires |
|---|---|---|
| **Google Gemini** | Text sent to Gemini API | API key |
| **Ollama** | Runs locally via Ollama | Ollama running (`ollama serve`), model pulled in Settings → Models |

Mix and match freely — e.g. Whisper for transcription + Ollama for summarization runs fully offline with no API key.

### First-Time Setup

Open **Settings** (gear icon → **Preferences**, or the tray menu). The gear icon also has an **About Meeting Recorder** entry showing the app version and project links.

1. **General tab** — choose your transcription and summarization services; set output folder and recording quality
2. **Models tab** — configure the selected services:
   - *Gemini*: paste your API key and choose a model
   - *Whisper*: install the engine (first time), then select a model and click Download
   - *whisper.cpp*: pick an acceleration backend and build the engine (first time), then download a GGML model
   - *Ollama*: set host and click Download next to your preferred model
3. **Prompts tab** — optionally customize the transcription or summarization prompt

### Settings Reference

#### General tab

| Setting | Description |
|---|---|
| Transcription service | Gemini (cloud), Whisper (local), or whisper.cpp (local, GPU) |
| Summarization service | Gemini (cloud) or Ollama (local) |
| Start at system startup | Launch automatically on login |
| Enable call detection | Monitor for active calls and notify you to start recording |
| Low memory mode | Unload the window from memory when you close it (~20 MB vs. ~100 MB idle in the tray) at the cost of a brief delay when reopening. Off by default — enable on low-memory systems |
| Output folder | Where recordings and notes are saved (default: `~/meetings`) |
| Recording quality | Audio bitrate preset (Very High / High / Medium / Low) |

#### Models tab

**Gemini**

| Setting | Description |
|---|---|
| API key | Required when Gemini is selected for transcription or summarization |
| Model | Gemini model to use (`gemini-flash-latest` recommended) |
| Processing timeout | Max time to wait for a Gemini response (1–10 min) |

**Whisper**

The Whisper engine (`faster-whisper`) is **not in the base install**. When Whisper is selected and the engine is missing, the section shows an **Install Whisper engine** button; once installed, the model controls appear.

| Setting | Description |
|---|---|
| Whisper model | Model to use for local transcription |
| Model list | Download status and one-click download for each available model |

Available Whisper models:

| Model | Size | Notes |
|---|---|---|
| `large-v3-turbo` | ~1.6 GB | High quality, 8× faster than large-v3 — recommended |
| `distil-large-v3` | ~1.5 GB | Fast, near-large quality |
| `large-v3` | ~3 GB | Best accuracy, slow on CPU |
| `medium` | ~1.5 GB | Good balance |
| `small` | ~500 MB | Fast, lower accuracy |

The `faster-whisper` engine accelerates on **NVIDIA (CUDA)** and otherwise runs on CPU. For **AMD or Apple GPU** acceleration, use the whisper.cpp engine below.

**whisper.cpp (GPU-accelerated)**

A local engine that supports a wider range of GPUs. The engine is **built from source on opt-in** (a build toolchain is installed automatically); until then the section shows a **Build whisper.cpp engine** button.

| Setting | Description |
|---|---|
| Acceleration backend | `auto` (detect), or force `cuda` / `rocm` / `vulkan` / `metal` / `cpu`. Used for both the build and at runtime. The detected backend is shown next to the selector. |
| Model | GGML model to use for local transcription |
| Model list | Download status and one-click download for each available GGML model |

Available whisper.cpp (GGML) models: `large-v3-turbo` (~1.6 GB), `large-v3` (~3 GB), `medium` (~1.5 GB), `small` (~470 MB).

**GPU Acceleration**

This section detects your GPU vendor and offers the matching runtime install: **CUDA** for NVIDIA, **ROCm** for AMD, built-in **Metal** for Apple Silicon, or a note that only CPU is available (in which case Gemini is recommended for speed).

**Ollama**

| Setting | Description |
|---|---|
| Ollama model | Model to use for local summarization |
| Ollama host | Ollama server address (default: `http://localhost:11434`) |
| Model list | Download status and one-click download for each available model |

Available Ollama models:

| Model | Size | Notes |
|---|---|---|
| `phi4-mini` | ~3 GB | Lightest, good quality |
| `gemma3:4b` | ~4 GB | Good quality |
| `qwen2.5:7b` | ~5 GB | Very capable |
| `llama3.1:8b` | ~5 GB | Very capable |
| `gemma3:12b` | ~8 GB | Best quality, high RAM required |

#### Prompts tab

Customize the transcription and summarization prompts. Each has a **Reset to default** button. The `{transcript}` placeholder in the summarization prompt is replaced with the transcript text.

Note: transcription prompts apply to Gemini only — the local Whisper and whisper.cpp engines do not use a prompt.

### Workflow

1. Click **Record (Headphones)** or **Record (Speaker)** to start
2. The timer shows elapsed recording time; **Pause** / **Resume** as needed
3. Click **Stop** — a 5-second countdown begins (click **Cancel** to abort)
4. After 5 seconds, transcription starts automatically
5. When done, links to the transcript and notes files appear in the window

### Noise Reduction (Optional)

If your microphone picks up too much ambient noise, enable PipeWire's WebRTC noise suppression:

**Temporary (current session only):**
```bash
pactl load-module module-echo-cancel aec_method=webrtc noise_suppression=true
```

**Permanent:**

Create `~/.config/pipewire/pipewire-pulse.conf.d/echo-cancel.conf`:
```
pulse.cmd = [
  { cmd = "load-module" args = "module-echo-cancel aec_method=webrtc noise_suppression=true" flags = [] }
]
```

Then restart PipeWire:
```bash
systemctl --user restart pipewire pipewire-pulse
```

### Logs

Application logs written to `/var/log/meeting-recorder/` (fallback: `~/.local/share/meeting-recorder/`):

```
app.log    — DEBUG and INFO messages
error.log  — WARNING and above
```

---

## Android App

### Features

- **Record** microphone audio (AAC/M4A) at a configurable quality, captured in a foreground service that survives brief interruptions
- **Transcribe** with Google Gemini or Alibaba Cloud Model Studio (`qwen3-asr-flash`)
- **Summarize** into structured Markdown notes with Google Gemini or DeepSeek (Android)
- **Auto-title** — generates a meeting title from the notes when none is provided
- **Generate from the library** — generate the transcript & notes, or regenerate notes, for any meeting from its detail screen
- **Recover failed recordings** — if processing fails, the raw audio is kept in your library so you can generate the transcript & notes later
- **Use Existing Recording** — import an external audio file and transcribe/summarize it
- **Silenced-mic warning** — if the system mutes the mic mid-recording (e.g. an answered call), the audio is kept and you're warned instead of getting a silent transcript
- **Do Not Disturb while recording** (optional) — silence notifications during capture
- **Meetings browser** — browse and read past transcripts and notes, each listed with the day of the week, date, time, and duration (same format as the Linux Library); rename or delete meetings
- **Audio playback** — play back recordings directly in the meeting detail view
- Recordings saved to `Documents/Meetings/` — same structure as the Linux app

### Requirements

- Android 12+ (API 31)
- API keys for your selected services: Alibaba Model Studio or Gemini for transcription, DeepSeek or Gemini for notes. Alibaba + DeepSeek needs no Gemini key.
- "All files access" (`MANAGE_EXTERNAL_STORAGE`) permission — required to read/write `Documents/Meetings/`

### Installation

Download `meeting-recorder-android-*.apk` from the [Releases](../../releases) page, transfer it to your phone, and install it (enable **Install from unknown sources** in Settings if prompted).

### Output Structure

Recordings are saved to `Documents/Meetings/` on external storage, in the same dated hierarchy as the Linux app:

```
Documents/Meetings/
└── 2026/
    └── March/
        └── 04/
            └── 14-30_Standup/
                ├── recording.m4a
                ├── transcript.md
                └── notes.md
```

### First-Time Setup

1. Open the app and tap the **Settings** icon
2. Choose the transcription service. For Alibaba, enter a Model Studio key, matching regional HTTPS base URL and `qwen3-asr-flash`. Default Beijing base: `https://dashscope.aliyuncs.com/compatible-mode/v1`; Singapore: `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`. Existing installs keep Gemini until switched.
3. Select **Summary service → DeepSeek**, enter its separate key/model, choose **中英双语摘要** (the default), and tap **Save**. DeepSeek directly generates paired Chinese/English Markdown points; `transcript.md` keeps the source language. Custom summary prompts are preserved. Other choices are Chinese or original-language notes. API usage is billed by each provider; an unrelated subscription may not cover these models.
4. Return to the main screen — grant **All files access** when prompted
5. Tap the microphone button to start recording

### Building from Source

```bash
# Requires Android SDK (API 35) and JDK 17
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Release build (requires signing credentials)
set -x KEYSTORE_PASSWORD your_store_pass
set -x KEY_ALIAS meetingrecorder
set -x KEY_PASSWORD your_key_pass
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

---

## Development

### Repository layout

```
linux/
├── src/meeting_recorder/  # GTK4 + libadwaita desktop app (Python)
├── tests/                 # Unit tests
├── packaging/             # .deb / .rpm / PKGBUILD / launcher scripts
├── install.sh / uninstall.sh
└── requirements.txt
android/
├── app/src/main/
│   ├── java/com/github/meetingrecorder/
│   │   ├── audio/         # MediaRecorder wrapper + foreground recording service
│   │   ├── data/          # Config, Meeting, MeetingRepository, GeminiClient
│   │   └── ui/            # Compose screens + ViewModels
│   └── res/
├── app/src/test/          # Unit tests (no device required)
└── build.gradle.kts / settings.gradle.kts
```

### Running Linux tests

```bash
pip install pytest
pytest
```

### Running Android unit tests

```bash
cd android && ./gradlew test
```

### CI

Every pull request to `main` runs:

- **Unit tests** — Python 3.10 and 3.12
- **Package build smoke tests** — builds `.deb` (ubuntu:24.04), `.rpm` (fedora:41), and `.pkg.tar.zst` (archlinux) in distro containers
- **Android debug build** — compiles the Kotlin app with `./gradlew assembleDebug`

Pushing a tag triggers the release workflows:

| Tag pattern | Workflow | Output |
|---|---|---|
| `v*` (e.g. `v1.2.0`) | `release.yml` | `.deb`, `.rpm`, `.pkg.tar.zst` attached to GitHub Release; apt repo on `gh-pages` updated |
| `android-*` (e.g. `android-1.0.0`) | `release-android.yml` | Signed `.apk` attached to GitHub Release |

## License

MIT

### Alibaba file transcription and summary prompts

The Android decoder feeds sequential PCM16 WAV segments (at most 60 seconds and 4 MB PCM each) to Alibaba's Qwen3-ASR OpenAI-compatible API. Only one bounded audio segment is uploaded at a time; no public URL or OSS bucket is required. Supports mono/stereo audio formats available through the device codecs and 16-bit PCM WAV. Timestamps denote segment starts, not speaker turns; no diarization is promised. Fixed boundaries may affect words spanning segments. Unsupported codecs, empty or incomplete service output stop processing visibly. Recognition requests retry transient errors at most twice, which can incur additional provider usage. Long transcript summary generation is still bounded by the chosen LLM's context and output limits.

Alibaba's API ignores the Gemini transcription prompt. The summary prompt now distinguishes confirmed decisions from proposals, preserves numbers/negations/uncertainty, and forbids invented speakers, owners or deadlines. Bilingual mode pairs corresponding points rather than translating the whole transcript. Saved custom prompts still apply; clear them or use **Reset** to adopt the improved default. Pure framing and mock HTTP tests do not substitute for device codec or live cloud quality testing.

API reference: https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference

### Local Whisper and Markdown reading (personal Android build)

The transcription selector now includes **Whisper / 本地离线**. It runs bundled whisper.cpp v1.9.4 on the phone CPU. Download a multilingual quantized model in Settings, select it, then Save. Medium is the default choice; tiny, base, small, medium, large-v3 and large-v3-turbo can each be downloaded/deleted independently. Sizes shown are download sizes, not total runtime RAM. No model is included in the APK or downloaded automatically. Downloads are checked against pinned size and SHA-256 and stored outside Android backups. Stay in the app while downloading; cancellation/failure removes the partial file and retry starts over.

Local transcription needs no ASR key and sends no audio to a server. The selected summary provider still receives transcript text. Audio is decoded in bounded segments, converted to mono 16 kHz, and processed locally with up to four CPU threads. It does not use the phone NPU/GPU and does not promise realtime speed or speaker identification. Model changes and deletion wait for active model use. Hardware target: Snapdragon 8 Gen 3-class ARM64 with ARMv8.2 FP16/dotprod; this personal APK is not universal.

Notes and transcripts now display native Markdown with headings, emphasis, lists, tasks and basic tables through Markwon. Toggle **Markdown 原文** or **复制 Markdown** to preserve the full source (including table text); selecting/copying a rendered table has TextView limitations. Existing files render without regenerating summaries. No WebView, JavaScript or remote image fetching is added.

Validation includes Robolectric Markdown span/TextView regression tests, download-integrity tests and resampling tests. These do not replace device inference benchmarks. Upstream provenance and license: `android/app/src/main/cpp/vendor/WHISPER_SOURCE.md` and `whisper/LICENSE`.

The native Markdown scroll viewport is explicitly clipped at the Compose boundary and constrained to the space below its action row, preventing scrolled text from painting over tabs or toolbar actions.

Android Settings → General → Appearance offers System (default), Light and Dark. Selection applies immediately and persists without pressing Save; status/navigation-bar icons and native Markdown rendering follow the selected theme.

## Android Qwen3-ASR and Chinese settings

Qwen3-ASR 0.6B INT8 appears as a radio-selectable model inside the existing Whisper settings list, with download/delete actions. The legacy QWEN provider is automatically migrated to WHISPER plus the Qwen model ID; files and keys remain intact. SelectedTranscriptionClient dispatches that ID to the Qwen engine. Existing provider selections and keys are preserved. The six model/tokenizer files (~941 MiB) are pinned to a Hugging Face revision and each verified by byte length/SHA-256. Completed files are reused after interrupted downloads; partial files are removed. Models live under noBackupFilesDir. `QwenModelStore` serializes download/delete/inference.

The official sherpa-onnx 1.13.8 Android AAR provides ARM64 CPU inference (up to four threads); no NPU acceleration. `QwenTranscriptionClient` decodes bounded 20-second segments after mono/16kHz conversion, preserving original-language text and segment timestamps. Fixed boundaries may affect words. Cancellation takes effect between native segments, not within a native decode; the decoder is released only after native work ends. Tests cover package integrity/retry, segmentation, resource cleanup, empty output and key independence. Actual Android native inference, mixed-language accuracy and thermal performance still require device testing.

Settings labels/options are Chinese (service names/model identifiers retain their original spelling). Removed explanatory local-key/upload-routing paragraphs. New dependency provenance is in `android/app/libs/README.md`; license texts are bundled under assets/licenses.

### Android processing recovery

Transcripts are saved as soon as transcription completes, before requesting a summary. Summaries are saved before optional title generation. If summarization fails, reopen the meeting and generate notes from the saved transcript. Imported audio is retained after processing failures. Detail task progress and errors remain visible across Notes, Transcript and Audio tabs. This does not add background execution or automatic resume after Android kills the process.

### Android 实时录音降噪

在设置中打开“录音实时降噪（GTCRN）”并保存，下一次 App 内录音生效。模型随安装包附带，约 523 KiB，默认关闭。开启后以 16 kHz 单声道采集，同时保留原始和降噪 AAC 录音；Audio 页可切换回放。自动转写优先使用完整的降噪版本，降噪失败或处理跟不上时回退原音。两份音频会占用更多存储。系统录音机导入的旧音频不经过此实时链路。

此版本的录音服务在录制期间保持 CPU 唤醒，但不能保证系统强制结束后继续录音。降噪可能影响远处或轻声发言，也不保证提高转写准确率。测试脚本、公开样本输出和结果分别位于 tools/benchmark_gtcrn.py 与 deliverables/denoise-tests；本机结果不能代替 Android 真机验证。

### 录完后手动转写

停止录音后仅保存音频，显示“录音已保存”。点击“查看会议”，打开刚才的录音，再手动生成文字和摘要。录音无需提前配置转写或摘要 Key；自动转换倒计时选项已移除。开启实时降噪时仍在录制期间处理并保留两份音频，转写与摘要由用户另外触发。


### Android 会议纪要模板与版本

设置 → 常规中可保存默认会议类型（通用会议、项目周会、需求评审、技术评审、客户沟通、头脑风暴）和详细程度（速览、标准、详细）。保留现有摘要语言偏好及自定义提示词；Gemini 与 DeepSeek 共用模板组装规则。类型决定关注点，详细程度决定信息保留量，未讨论的栏目不填充。

会议详情 → 生成纪要，或纪要页右上角 → 重新生成，可临时选择类型、详细程度与语言。有逐字稿时直接复用，没有时先转写录音。生成后先显示预览：**采用此版本**才替换当前 `notes.md`，**保留原版**丢弃本次预览。首次从详情生成也需要采用；录音/导入主流程继续使用默认设置及原有保存流程。预览保留在 ViewModel 内，退出页面或进程结束后不会自动保存。

模型被要求给重要决策和行动项附上 `[P行号]` 与原文已有的 `[HH:MM:SS]`。逐字稿阅读页显示相同的行号，预览中的**查看引用原文**显示该版本生成时的源文本。程序拒绝越界行号和原文不存在的标准时间戳；这只验证引用位置，不证明结论与引用语义一致，也不保证模型为每条结论附上引用。当前仍为全文单次总结，未实现长会议分段或独立事实核验。

采用版本保存在会议目录的 `notes-history/*.json`，记录正文、源逐字稿、所选模板/粒度/语言、模型与自定义提示词。首次替换时自动保留旧 `notes.md`，旧纪要没有可追溯的模型或生成参数，界面会显示未记录。右上角 → **历史版本**可预览并重新采用；恢复不删除其他版本。损坏的历史 JSON 被跳过，归档失败时不会替换当前纪要。`notes.md` 和 `meeting.json` 的既有格式继续兼容 Linux。

模板结构参考（独立编写提示词，非复制模板正文）：
- [Confluence 通用会议纪要](https://www.atlassian.com/software/confluence/templates/meeting-notes/)：按议题保留讨论、决定及行动项，并记录决定依据。
- [Confluence 周会记录](https://www.atlassian.com/software/confluence/templates/weekly-meeting-notes)：关注持续跟进、决策和下一步。
- [Notion 会议模板分类](https://www.notion.com/templates/category/meetings)：提供不同会议场景的组织参考。

验证：模板/粒度组合、原文插值、引用范围、HTTP 参数、配置兼容、版本恢复、保存失败保护以及预览采用/丢弃/生成失败的 JVM 测试；Compose 测试覆盖原有任务进度和新选项/预览操作。离线测试不代表真实模型的内容质量、真机布局或长会议效果验证。

### Android 本地分人与可选声纹

普通录音仍然直接保存。保存后可点 **识别并命名（可选）**；导入处理结果也提供 **保存并命名说话人**。入口打开本场会议的 **说话人** 页，不强制识别或建立声纹档案。

1. 设置 → **声纹** → 下载模型（约 28 MiB / 29.8 MB）。复用已打包的 sherpa-onnx，使用 CPU；分人模型与声纹模型分别校验固定大小、SHA-256 和上游版本，下载失败可重试。没有模型时原有录音和转写继续工作。
2. **转写时自动分人 A/B/C** 默认开启，但只在模型已安装时生效。可关闭，恢复原有转写。新会议重新编号；按首次发言顺序为 A/B/C，超过 Z 后为 AA/AB。
3. 在会议 → **说话人** 点 **分析说话人**，本地分析已有音频、显示时间线并提供清晰样本试听，不改写现有逐字稿或纪要。点 **分人转写** 则按本地分人片段调用当前 ASR，更新逐字稿并生成纪要预览；现有纪要仍需明确采用后才替换。云端转写会增加请求次数，局部 ASR 可能反复加载模型，速度会受影响。
4. 每位说话人点 **识别并命名（可选）**：可以只输入本场姓名，也可选择已有声纹档案。显示的相似度只用于推荐，不是身份概率；无论分数高低都需要用户确认。
5. 只有勾选 **保存新声纹供以后识别** 或 **追加本次样本到此声纹** 并确认后，才保存所选样本到跨会议声纹库。可试听并挑选片段，同一人最多 20 份样本，适用于经确认的不同发声状态；不自动追加或覆盖。
6. 设置 → **声纹** 支持姓名修改、样本试听、单样本移除、整份声纹删除。删除停止以后匹配，但不删除原始会议录音或改写历史逐字稿；本场已确认姓名保留，可在会议页单独清除。删除会议时清理本场私有试听样本，用户已主动存入声纹库的独立副本保留。会议页也可单独“清除本场分析”，移除本场特征、试听片段及命名关联，保留原始录音、逐字稿和声纹库副本。

分人按有界音频窗口运行，跨窗口用声纹相似度保守拼接编号；检测到的重叠区间标为 **重叠/未确认**。每位说话人最多保留 3 份本场试听样本，每份 2–6 秒；过短、过静、明显削波或重叠的区间不用于入库。无法取得合格样本时仍允许仅命名。未被模型检测出的重叠、噪声、相似声音、窗口边界和感冒/嘶哑可能造成错分或漏分，编号和推荐都需要核对。相似度阈值为启发式初值，尚未经用户实际会议或感冒场景校准。

模型、声纹向量和试听片段放在 `noBackupFilesDir`；公共会议目录只新增一个 `speakers.json` 分析引用。逐字稿与纪要保持原有格式。复制会议不会共享另一场仍存在会议的身份；重命名会议后可继续读取关联。跨设备复制或清除应用数据后，需要重新分析本地说话人。模型版本不同的声纹不会相互匹配。

模型来源与许可：
- [pyannote segmentation 3.0 ONNX INT8](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/tree/9403a6902bb58e3d5ae8c7e77c3422de279db2e0)，MIT（CNRS）；附带许可证位于 assets/licenses/pyannote-segmentation-MIT.txt。
- [3D-Speaker CAMPPlus 中英模型](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/0743f301363dec56491a490f6d6cbc9d67f9a3bf)，[上游 3D-Speaker](https://github.com/modelscope/3D-Speaker)，附带其 Apache-2.0 许可证；实际模型文件名与 SHA 固定在 SpeakerModelStore.kt。
- [sherpa-onnx Kotlin 分人示例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/kotlin-api-examples/test_offline_speaker_diarization.kt)。

测试覆盖编号、跨窗口毫秒时间线、重叠区间、质量筛选、多个声纹样本、模型不兼容、每场隔离、确认/不入库、追加/删除/试听文件、失败回滚与 Compose 确认操作。`SpeakerDeviceTest` 为显式开启的隔离真机测试，只使用暂存的公开双人音频，不录制麦克风、不调用云 API、不修改用户会议。运行示例：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 在应用 cache/speaker-device-test/models/ 暂存两个固定模型，
# 在 cache/speaker-device-test/two-speakers.wav 暂存上游公开样本后运行：
adb shell am instrument -w -e speakerTest true -e class com.github.meetingrecorder.SpeakerDeviceTest com.github.meetingrecorder.test/androidx.test.runner.AndroidJUnitRunner
```

双人测试音频为 [上游 1-two-speakers-en.wav](https://huggingface.co/csukuangfj/speaker-embedding-models/blob/0743f301363dec56491a490f6d6cbc9d67f9a3bf/1-two-speakers-en.wav)，512044 bytes，SHA-256 `f1c877dc01595e28be7147bf2fe38e5268147a868bf3fdb5c37b97f5940e21f3`；只暂存到测试缓存，不打包进 APK。

Android CI 和发布工作流在 Gradle 配置前接受 SDK（含预览）许可并安装项目指定的 NDK 30.0.16138531 / CMake 4.1.2，避免干净 runner 在 ktlint 阶段因缺少原生工具链许可失败。

### Android 模板提示词

设置 → 提示词中选择会议类型，会显示该模板独立的中文摘要提示词；修改后点击保存，可恢复当前模板默认值。生成时按实际所选模板读取配置，会议内临时选择也生效。转写与标题提示词独立于摘要模板。全局补充要求默认留空，兼容保留旧版自定义摘要提示词；模板自定义按枚举名存储，留空使用内置默认。摘要版本记录该模板提示词与全局补充要求。测试覆盖模板切换、独立持久化、恢复默认和原文插值。
