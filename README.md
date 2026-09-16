# MusicMixer Android App

A fully-featured Android audio mixing app built with Kotlin + Android SDK.

## Features

| Feature | Details |
|---|---|
| **Multi-file Import** | Pick multiple audio files at once via system file picker (any format Android can decode: MP3, WAV, OGG, M4A, AAC, FLAC) |
| **Waveform Display** | Real waveform thumbnail rendered per track using MediaCodec PCM peak extraction |
| **Trim Editor** | Per-track editor with full-size interactive waveform — drag green/pink handles OR use seekbars to set exact in/out points |
| **Preview** | Play back only the trimmed region before committing |
| **Track Ordering** | Move tracks up/down with arrow buttons |
| **Per-track Volume** | 0–150% individual gain slider |
| **Fade In/Out** | Optional fade applied to each track during export |
| **Master Volume** | Global mix output gain |
| **WAV Export** | Lossless 16-bit PCM 44.1 kHz stereo via `MediaCodec` + `OfflineAudioContext`-style offline render |
| **Share on WhatsApp** | Direct share intent targeting `com.whatsapp` |
| **Share via...** | Generic Android share sheet for any other app |
| **Save to Downloads** | Saves WAV to `Downloads/MusicMixer/` (Scoped Storage compatible) |
| **App Icon** | Custom waveform-bars adaptive icon with purple gradient |

## Project Structure

```
MusicMixerApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/musicmixer/app/
│   │   ├── model/
│   │   │   └── AudioTrack.kt           # Parcelable track model
│   │   ├── audio/
│   │   │   ├── AudioDecoder.kt         # MediaCodec peak extraction
│   │   │   ├── AudioMixer.kt           # Offline WAV render engine
│   │   │   └── AudioPlayer.kt          # Trim preview playback
│   │   ├── ui/main/
│   │   │   ├── MainActivity.kt         # Main screen + export sheet
│   │   │   ├── MainViewModel.kt        # Track state management
│   │   │   ├── TrackAdapter.kt         # RecyclerView adapter
│   │   │   └── WaveformView.kt         # Compact waveform thumbnail
│   │   └── ui/editor/
│   │       ├── EditorActivity.kt       # Trim editor screen
│   │       └── TrimWaveformView.kt     # Full interactive waveform
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml
│       │   ├── activity_editor.xml
│       │   ├── item_track.xml
│       │   └── bottom_sheet_export.xml
│       ├── drawable/           # Vector icons + app logo
│       ├── mipmap-*/           # Adaptive launcher icons (all densities)
│       └── values/             # Colors, strings, themes, styles, dimens
```

## How to Build

1. Open `MusicMixerApp/` in **Android Studio Hedgehog** (or newer).
2. Let Gradle sync automatically.
3. Connect a device or start an emulator (API 26+).
4. Run → **app**.

## Requirements

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 34 (Android 14)
- **Kotlin**: 1.9.22
- **AGP**: 8.2.2

## How to Use

1. **Launch** the app → tap **+** to pick audio files.
2. Tap **✂ (pencil icon)** on any track to open the trim editor.
3. Drag the **green** (start) or **pink** (end) handles on the waveform to select the region you want.
4. Tap **▶ Preview** to hear the selection, then **✓ Apply Trim**.
5. Repeat for all tracks. Reorder with ↑↓ arrows.
6. Tap **🎵 Compose Mix** — the app renders everything to a WAV.
7. Choose **Share on WhatsApp**, **Share via…**, or **Save to Downloads**.
