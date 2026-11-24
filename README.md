<div align="center">

# 🎬 PixelCineRaw

**Flagship-grade, hardware-tuned Android Cinema Camera application.**  
*Built with Kotlin, Jetpack Compose, Camera2 API, and OpenGL ES 3.0.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release: v1.0.0](https://img.shields.io/badge/Release-v1.0.0%20(Stable)-brightgreen.svg)](https://github.com/kunalnimje/PixelCineRaw/releases/tag/v1.0.0)
[![Platform](https://img.shields.io/badge/Platform-Android%2014+-green.svg)](https://developer.android.com)
[![Hardware Tuned](https://img.shields.io/badge/Tuned%20For-Google%20Pixel%209a%20(Tensor%20G4)-orange.svg)](https://store.google.com)
[![HDR Pipeline](https://img.shields.io/badge/Color-HLG10%20%7C%20HDR10%2B-purple.svg)]()
[![Codecs](https://img.shields.io/badge/Codec-10--bit%20ALL--I%20280Mbps-red.svg)]()

</div>

---

## 📽 Overview

**PixelCineRaw** transforms your Android smartphone into a professional cinema camera inspired by Blackmagic Camera and Final Cut Camera. Designed from the metal up for filmmakers, cinematographers, and colorists, it bypasses aggressive consumer computational processing to deliver pristine, organic, filmic imagery.

Unlike stock camera apps that apply heavy spatial noise reduction, aggressive edge sharpening, and crushing 8-bit dynamic range compression, PixelCineRaw gives you **100% manual control over the sensor**, capturing up to **10-bit ALL-Intra 280 Mbps HEVC** footage with wide color gamut **HLG10 / HDR10+ (BT.2020)** color spaces.

---

## ✨ Key Features

### 📱 Dual Adaptive UIs
- **Landscape Cinema Rig HUD:** Comprehensive broadcast-grade cinema HUD featuring top status telemetry, dual-side exposure/lens/WB/focus rotary controls, live waveform/histogram overlays, and anamorphic frame lines.
- **Minimal iPhone-Style Vertical Portrait Mode:** Minimalist, sleek one-handed mobile camera interface with an edge-to-edge unclipped `9:16` / `3:4` vertical viewfinder, compact floating top bar, single-pill 56dp borderless exposure strip (`SHUTTER`, `ISO`, `WB`, `FOCUS`, `EV`), haptic rotary wheels, and instant Pro Tools bottom sheet.
- **Three-State Orientation Lock & Hold:**
  - `AUTO`: Dynamic rotation following device sensors.
  - `LOCK LANDSCAPE`: Locks orientation to horizontal landscape while allowing 180° gimbal/rig flipping.
  - `LOCK PORTRAIT`: Locks to upright orientation for vertical cinema workflows.
  - **Recording Hold:** Automatically locks orientation in its current state when recording starts to prevent mid-take aspect ratio disruption.

### 🎞️ Stutter-Free 10-Bit HDR Video Pipeline
- **Zero-Micro-Stutter 10-Bit HEVC Pipeline:** Mathematical quantized monotonic MP4 frame cadence ($16.666\,\text{ms}$ at 60fps, $33.333\,\text{ms}$ at 30fps, $41.666\,\text{ms}$ at 24fps) eliminates erratic durations in `MediaMuxer` and Google Photos.
- **Tensor G4 Realtime Hardware Priority:** Forces `MediaFormat.KEY_PRIORITY = 0` and `KEY_OPERATING_RATE = fps` to keep the hardware HEVC encoder clock steady without downclocking during complex high-motion pans.
- **HLG10 & HDR10+ (BT.2020):** Wide Dynamic Range with 10-bit Rec.2020 colorimetry (`COLOR_STANDARD_BT2020` + `COLOR_TRANSFER_HLG` or `COLOR_TRANSFER_ST2084` PQ) with live 120Hz OLED preview and embedded MP4 metadata.
- **10-bit ALL-Intra (ALL-I) & Long-GOP:** Frame-by-frame intra coding up to **280 Mbps** for zero temporal compression artifacts, alongside optimized 120 Mbps 10-bit Long-GOP for extended documentary recording.
- **Universal Zero-Stretch Viewfinder:** 1:1 true-to-life square pixels rendered on both the 120Hz live OLED viewfinder and exported MP4 files across all aspect ratios (`16:9`, `4:3 Open Gate`, `2.39:1 CinemaScope`, `3:4`, `9:16`).
- **Anamorphic Lens De-Squeeze:** Real-time GL shader preview de-squeezing for 1.33x, 1.55x, and 2.0x anamorphic glass.
- **Pixel-Log & Rec.709:** Flat logarithmic curve option for post-production grading alongside broadcast-standard Rec.709.
- **3D LUT Engine:** Live OpenGL ES 3D LUT preview with `.cube` file import and optional "Bake LUT into MP4" toggle.

### 🎛️ True Cinema Exposure & Anti-Banding
- **True 180° Cinema Shutter & Shutter-Priority Auto-ISO:** Authentic optical motion blur (`1/48s` at 24p, `1/60s` at 30p, `1/120s` at 60p) with live 32-bin luma histogram feedback and automatic highlight-clipping `ND NEEDED` warning badge.
- **Sub-Stop ISO & $130\,\text{dp}$/Stop Relative Dial:** Eliminates analog sensor stair-stepping using fine `CONTROL_POST_RAW_SENSITIVITY_BOOST` gain multipliers with a 250ms optical exponential glide and zero-flash auto-to-manual handoff.
- **50Hz / 60Hz Indoor Light Anti-Banding:** Hardware ISP anti-banding mode (`50Hz`, `60Hz`, `AUTO`, `OFF`) with automatic flicker-free $1/50\,\text{s}$ and $1/100\,\text{s}$ shutter harmonic synchronization for European/PAL AC indoor lighting.
- **Precision Rotary Dials:** Haptic-responsive rotary wheels for White Balance (Kelvin 2300K–8000K + Tint), Manual Focus with hyperfocal marks, and EV Bias.

### 📐 Precision Optics & Pro Tools
- **VSYNC-Synced Cylindrical Zoom Wheel:** Smooth logarithmic zoom hub supporting instant preset taps (`0.5x`, `1x`, `2x`, `5x`) and drag-to-slide precision zooming up to 8.0x with zero frame skipping.
- **Non-Linear Cinema Follow-Focus Wheel:** High-precision manual focus with hyperfocal marks and programmable A → B rack focus (1.0s–3.0s).
- **Multi-Tier Stabilization:** Real hardware optical image stabilization (`OFF / OIS / OIS+EIS`) for maximum sensor real estate or rock-solid handheld shots.
- **Clean Cinema ISP (`EDGE_MODE_OFF`):** Disables synthetic software edge enhancement, digital halos, and chromatic fringing for authentic, organic optical roll-off.
- **Monitoring & Assist Suite:**
  - **Arri-Style False Color:** IRE luminance false-color mapping shader (Clipping Red, Highlights Yellow, Skin Tone Pink, 18% Gray Green, Deep Shadows Blue, Crushed Blacks Purple).
  - **Focus Peaking:** Real-time edge detection in Red, Green, Blue, or Yellow.
  - **Exposure Zebras:** High-visibility zebra stripes configurable from 75% to 100% IRE.
  - **Scopes:** Real-time Luma Waveform Monitor and RGB/Luma Histogram.
  - **Framing Guides & Electronic Horizon:** Rule-of-thirds grid, aspect ratio frame lines (`2.39:1`, `16:9`, `4:3`, `1:1`), and real-time gyroscope/accelerometer leveler.
- **Independent Torch Control:** Constant illumination assist toggleable on the fly without affecting camera capture mode.
- **Full Persistent Settings:** Saves and immediately restores all camera format, resolution, FPS, color profile, bitrate, ISP mode, stabilization, orientation, lens, and overlay configurations across app restarts via `SharedPreferences`.

### 🎙️ Audio & Telemetry
- **Buffered Disk I/O & WAV Sidecar:** Embedded 48kHz 320kbps AAC audio default; when 48kHz 24-bit uncompressed WAV sidecar is enabled, disk writes are queued via a 256 KB `BufferedOutputStream` on a background I/O thread so disk operations never stall muxing.
- **USB-C Audio Detection:** Automatic external microphone detection with live UI status indicator.
- **Real-Time Telemetry:** Battery percentage, remaining internal storage (GB and remaining minutes at current bitrate), and Tensor G4 hardware thermal status.
- **Gyroflow IMU Logger:** Generates `.gcsv` sidecar files containing high-frequency gyroscope and accelerometer data for post-production stabilization in Gyroflow.

---

## 📱 Hardware Compatibility & Recommendations

| Tier | Hardware / Devices | Capabilities |
| :--- | :--- | :--- |
| **Tier 1 (Target Hardware)** | **Google Pixel 9a / Pixel 9 / Pixel 9 Pro** (Tensor G4) | Full 10-bit ALL-I 280 Mbps, HLG10/HDR10+, Clean ISP, OIS+EIS, 4K 60fps |
| **Tier 2 (Supported)** | **Google Pixel 8, 7 series** (Tensor G2/G3), flagship Qualcomm Snapdragon 8 Gen 2/3 | 10-bit HEVC, HLG10, Full manual Camera2 controls, 4K 30/60fps |
| **Tier 3 (Generic Android)** | Android 13+ devices with Camera2 `HARDWARE_LEVEL_FULL` | Standard 8-bit HEVC/H.264, Rec.709, manual exposure controls |

> [!NOTE]
> PixelCineRaw targets devices supporting the Android Camera2 **Manual Sensor** (`REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`) and OpenGL ES 3.0. Devices lacking 10-bit HEVC encoder hardware will automatically fall back to standard 8-bit recording.

---

## 🛠️ Build & Installation

### Prerequisites
- **Android Studio Ladybug (2024.2.1)** or newer
- **Android SDK 35 / 36**
- **JDK 17** (configured as Gradle JVM)
- A physical Android device with Developer Options & USB Debugging enabled (Camera2 manual features cannot be tested on standard emulators).

### Steps
1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-username/PixelCineRaw.git
   cd PixelCineRaw
   ```

2. **Open in Android Studio:**
   - Select **Open an Existing Project** and navigate to the cloned directory.
   - Allow Gradle to sync dependencies.

3. **Build the Debug APK via Terminal:**
   ```bash
   # On macOS / Linux:
   ./gradlew assembleDebug

   # On Windows PowerShell:
   .\gradlew.bat assembleDebug
   ```

4. **Install to your connected device:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Permissions:**
   The application requires the following runtime permissions (requested on first launch):
   - `android.permission.CAMERA` — Low-level Camera2 device access
   - `android.permission.RECORD_AUDIO` — Embedded AAC and broadcast 48kHz WAV sidecar recording

---

## 🔒 Privacy & Security

- **100% Offline & Air-Gapped:** PixelCineRaw contains **zero network permissions** (`android.permission.INTERNET` is neither requested nor used).
- **Zero Telemetry:** No analytics, tracking SDKs, advertising libraries, or cloud telemetry.
- **Local Storage:** All recorded footage, LUTs, and audio sidecars remain strictly in local device storage.

---

## 📜 License & Copyleft Notice

This project is licensed under the **[GNU General Public License v3.0 (GPLv3)](LICENSE)**.

```
PixelCineRaw - Open-source Android Cinema Camera
Copyright (C) 2026 Kunal Nimje

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

### Important Notice on Derivatives & Commercial Misuse
- Under the terms of the **GNU GPLv3**, any fork, derivative application, or modified version of this software distributed publicly (including binaries distributed on app stores like Google Play) **must release their complete source code under the GPLv3**.
- You may not incorporate this codebase into proprietary, closed-source applications.
- The name **"PixelCineRaw"** and associated logos/branding are maintained by the original author. Derivative works or forks must not use official project branding to mislead users or misrepresent the origin of modified binaries.

---

## 🤝 Contributing

Contributions from the filmmaking and Android development communities are welcome! Please review our [**Contributing Guide**](CONTRIBUTING.md) and [**Security Policy**](SECURITY.md) before submitting pull requests.
