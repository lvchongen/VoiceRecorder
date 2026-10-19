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

在设置中打开“录音实时降噪”并选择方案后保存，下一次 App 内录音生效。DPDFNet8（人声保留，约 14 MB）和 GTCRN（轻量，约 523 KiB）均随安装包附带，降噪开关默认关闭，方案默认 DPDFNet8。开启后以 16 kHz 单声道采集，同时保留原始和降噪 AAC 录音；Audio 页可切换回放。自动转写优先使用完整的降噪版本，降噪失败或处理跟不上时回退原音。两份音频会占用更多存储。系统录音机导入的旧音频不经过此实时链路。

此版本的录音服务在录制期间保持 CPU 唤醒，但不能保证系统强制结束后继续录音。降噪可能影响远处或轻声发言，也不保证提高转写准确率。测试脚本、公开样本输出和结果分别位于 tools/benchmark_gtcrn.py 与 deliverables/denoise-tests；本机结果不能代替 Android 真机验证。

### 录完后手动转写

停止录音后仅保存音频，显示“录音已保存”。点击“查看会议”，打开刚才的录音，再手动生成文字和摘要。录音无需提前配置转写或摘要 Key；自动转换倒计时选项已移除。开启实时降噪时仍在录制期间处理并保留两份音频，转写与摘要由用户另外触发。


### Android 会议纪要模板与版本

设置 → 常规中可保存默认会议类型（通用会议、项目周会、需求评审、技术评审、客户沟通、头脑风暴）和详细程度（速览、标准、详细）。保留现有摘要语言偏好及自定义提示词；Gemini 与 DeepSeek 共用模板组装规则。类型决定关注点，详细程度决定信息保留量，未讨论的栏目不填充。

会议详情 → 生成纪要，或右上角 → 重新生成，可选择类型、详细程度与语言。有逐字稿时直接复用，没有时先转写录音。**生成完成后直接保存并显示 Notes**，无需“保留原版 / 采用此版本”确认。旧版自动保存在会议目录的 `notes-history/*.json`，需要时从历史版本查看或恢复。

Transcript 默认按原有时间戳排序、突出显示时间，保留多行发言内容；无时间戳的旧文本保留原文，不编造时间。日常 Notes 和 Transcript 隐藏 `[P1]` 等内部引用标记；历史原文与磁盘原始内容保持完整。生成提示词不再要求在正文显示这些编号。

历史版本记录正文、源逐字稿、模板/详细程度/语言、模型与提示词。生成或归档失败不会替换原有纪要。历史版本的恢复和关闭按钮各占一行，避免小屏截断；这些控件只在主动打开历史版本时出现。`notes.md` 和 `meeting.json` 的格式保持兼容 Linux。

模板结构参考（独立编写提示词，非复制模板正文）：
- [Confluence 通用会议纪要](https://www.atlassian.com/software/confluence/templates/meeting-notes/)：按议题保留讨论、决定及行动项，并记录决定依据。
- [Confluence 周会记录](https://www.atlassian.com/software/confluence/templates/weekly-meeting-notes)：关注持续跟进、决策和下一步。
- [Notion 会议模板分类](https://www.notion.com/templates/category/meetings)：提供不同会议场景的组织参考。

验证：模板/粒度组合、原文插值、引用范围、HTTP 参数、配置兼容、版本恢复、保存失败保护以及自动保存、历史恢复和生成失败保护的 JVM 测试；Compose 测试覆盖原有任务进度和新选项/预览操作。离线测试不代表真实模型的内容质量、真机布局或长会议效果验证。

### Android 自动声纹与管理

声纹识别默认可用，无需单独开启。首次启动会在后台准备约 28 MiB 的模型；录音和转写不等待下载。录音保存后自动在本机分析 A/B/C 并入库，已有清晰匹配会沿用档案，未匹配声音保存为“未命名 1、2…”。不再弹出命名确认、选择样本或额外保存步骤。

设置 → **声纹**（也可从会议右上角菜单 → **声纹管理**）提供：
- **试听、命名、删除**：管理自动收集的档案，删除不影响会议录音和已保存的文字。
- **更新**：选择只包含该说话人的清晰录音，追加新样本；保留已有样本，支持不同发声状态。
- **添加**：导入单人录音，自动建立或匹配档案。样本支持逐份试听和移除。

会议页保留纪要、逐字稿、录音的原有布局，录音结束后直接保存。声纹分析、存储或模型准备失败不会阻塞正常转写和保存。分人转写遇到局部空识别，会使用完整录音重试，不再把一个空片段视为整场没有语音；认证、网络等其他错误仍正常报告，取消操作不重试。

本场按首次发言分配 A/B/C，跨会议只复用声纹档案；匹配阈值是启发式而非准确率保证。每人每场最多 3 份 2–6 秒清晰样本，每份档案最多 20 份样本；过短、过静、明显削波及检测到的重叠片段不入库。自动匹配要求相似度至少 0.8，且领先第二候选至少 0.1；本场不同标签不关联同一档案。匹配不足会建立新档案，可在管理页修正命名/删除。噪声、相似声音、感冒/嘶哑仍可能导致错分或重复建档。删除的档案不会被同一份缓存分析自动重新创建。

模型、声纹向量和试听片段放在 `noBackupFilesDir`；公共会议目录只新增一个 `speakers.json` 分析引用。逐字稿与纪要保持原有格式。复制会议不会共享另一场仍存在会议的身份；重命名会议后可继续读取关联。跨设备复制或清除应用数据后，需要重新分析本地说话人。模型版本不同的声纹不会相互匹配。

模型来源与许可：
- [pyannote segmentation 3.0 ONNX INT8](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/tree/9403a6902bb58e3d5ae8c7e77c3422de279db2e0)，MIT（CNRS）；附带许可证位于 assets/licenses/pyannote-segmentation-MIT.txt。
- [3D-Speaker CAMPPlus 中英模型](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/0743f301363dec56491a490f6d6cbc9d67f9a3bf)，[上游 3D-Speaker](https://github.com/modelscope/3D-Speaker)，附带其 Apache-2.0 许可证；实际模型文件名与 SHA 固定在 SpeakerModelStore.kt。
- [sherpa-onnx Kotlin 分人示例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/kotlin-api-examples/test_offline_speaker_diarization.kt)。

测试覆盖编号、跨窗口毫秒时间线、重叠区间、质量筛选、多个声纹样本、模型不兼容、每场隔离、自动建档/匹配/删除后不重建、追加/删除/试听文件、空片段整段重试、失败回滚与精简管理界面。`SpeakerDeviceTest` 为显式开启的隔离真机测试，只使用暂存的公开双人音频，不录制麦克风、不调用云 API、不修改用户会议。运行示例：

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

声纹管理的“更新”直接打开录制弹窗，不再选择文件。用户显式开始后录制本人声音，建议 10–20 秒，满 5 秒可完成，30 秒自动结束并提交当前档案；补充样本而非覆盖已有样本。会议正在录音时禁止并行录制。取消、页面离开或应用进入后台停止录音并删除临时文件，交给 ViewModel 的录音在处理完成/失败/取消后清理。录音只在私有缓存中暂存，不创建会议或生成逐字稿/纪要；“添加”仍保留音频导入。沿用单人、样本质量与档案容量检查，失败不改旧档案。SpeakerControlsTest 覆盖更新入口与取消，不隐式启动麦克风测试。
