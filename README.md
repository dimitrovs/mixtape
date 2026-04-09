# Mixtape

Android app that automatically detects USB storage and transfers all your music to it as MP3.

## Features

- **Auto-launch on USB insert** — opens automatically when you plug in a USB OTG drive
- **Full music scan** — finds all audio files on your phone (MP3, FLAC, WAV, OGG, M4A, AAC, WMA, Opus)
- **Smart conversion** — converts non-MP3 files to MP3; copies existing MP3s directly
- **Background service** — runs as a foreground service with progress notification
- **Skip duplicates** — won't re-copy files that already exist on the USB drive

## How It Works

1. Plug in a USB OTG drive
2. The app launches automatically (or open it manually)
3. Grant audio permission when prompted
4. Tap **Select USB Storage** and pick your USB drive
5. Tap **Start Transfer** — the app scans, converts, and copies all music to a `Mixtape/` folder on the drive

## Technical Details

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34
- **Language**: Kotlin + C (NDK)
- **MP3 encoding**: LAME encoder via JNI — produces real MP3 files that work on any car stereo
- **Audio decoding**: Android `MediaCodec` decodes any format (FLAC, WAV, OGG, etc.) to PCM
- **USB access**: Storage Access Framework (SAF) + `DocumentFile` API
- **USB detection**: `USB_DEVICE_ATTACHED` intent filter for USB mass storage class devices

## Building

First, download the LAME MP3 encoder source (one-time setup):

```bash
./setup_lame.sh
```

Then build the APK:

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

**Requirements**: Android SDK with NDK and CMake installed (Android Studio installs these automatically).

## Permissions

- `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (older) — to find music files
- `FOREGROUND_SERVICE` — to run the transfer in the background
- `POST_NOTIFICATIONS` — to show transfer progress
