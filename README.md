<div align="center">

# 🎬 PixelCineRaw

**Flagship-grade, hardware-tuned Android Cinema Camera application.**  
*Built with Kotlin, Jetpack Compose, Camera2 API, and OpenGL ES 3.0.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
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

### 🎞️ Video & Compression Pipeline
- **10-bit ALL-Intra (ALL-I):** Frame-by-frame intra coding up to **280 Mbps** for artifact-free high-motion capture and zero temporal compression artifacts.
- **Long-GOP Mode:** High-efficiency HEVC compression (80–200 Mbps) for long-form documentary and extended shooting sessions.
- **Universal Zero-Stretch Viewfinder:** 1:1 true-to-life square pixels rendered on both the 120Hz live OLED viewfinder and exported MP4 files across all aspect ratios (`16:9`, `4:3 Open Gate`, `2.39:1 CinemaScope`).
- **Anamorphic Lens De-Squeeze:** Real-time GL shader preview de-squeezing for 1.33x, 1.55x, and 2.0x anamorphic glass.

### 🎨 Color Grading & Display Profiles
- **HLG10 (Hybrid Log-Gamma):** Wide Dynamic Range with 10-bit Rec.2020 colorimetry (`COLOR_STANDARD_BT2020` + `COLOR_TRANSFER_HLG`).
- **HDR10+:** Dynamic tone metadata pipeline for flagship HDR10+ displays.
- **Pixel-Log & Rec.709:** Flat logarithmic curve option for post-production grading alongside broadcast-standard Rec.709.
- **Clean Cinema ISP (`EDGE_MODE_OFF`):** Disables synthetic software edge enhancement, digital halos, and chromatic fringing for authentic, organic optical roll-off.
- **3D LUT Engine:** Live OpenGL ES 3D LUT preview with `.cube` file import and optional "Bake LUT into MP4" toggle.

### 🎛️ Tactile Cinema Controls
- **Precision Rotary Dials:** Haptic-responsive rotary wheels for:
  - **Shutter Angle / Speed:** 180° cinematic shutter rule (11.2° to 360°) or 1/24s to 1/8000s.
  - **ISO Sensitivity:** Manual gain from ISO 50 to 6400.
  - **White Balance:** Kelvin adjustment from 2300K to 8000K with tint control.
  - **Manual Focus:** Smooth micro-stepping slider with hyperfocal infinity marks.
  - **EV Bias:** ±2.0 EV compensation in 1/3-stop increments.
- **iPhone-Style Zoom Wheel:** Smooth logarithmic zoom hub supporting instant preset taps (`0.5x`, `1x`, `2x`, `5x`) and drag-to-slide precision zooming up to 10x with zero frame skipping.
- **Tap-to-Focus Reticle:** Viewfinder touch gesture with synchronized focus bracket and inline vertical EV slider.
- **Programmable A → B Rack Focus:** Set two optical focus marks and execute automated, ease-in/ease-out focus transitions (1.0s–3.0s).
- **Independent Torch Control:** Constant illumination assist toggleable on the fly without affecting camera capture mode.

### 📐 Monitoring & Assist Suite
- **Arri-Style False Color:** IRE luminance false-color mapping shader (Clipping Red, Highlights Yellow, Skin Tone Pink, 18% Gray Green, Deep Shadows Blue, Crushed Blacks Purple).
- **Focus Peaking:** Real-time edge detection in Red, Green, Blue, or Yellow.
- **Exposure Zebras:** High-visibility zebra stripes configurable from 75% to 100% IRE.
- **Scopes:** Real-time Luma Waveform Monitor and RGB/Luma Histogram.
- **Framing Guides & Electronic Horizon:** Rule-of-thirds grid, aspect ratio frame lines (`2.39:1`, `16:9`, `4:3`, `1:1`), and real-time gyroscope/accelerometer leveler.
- **Three-State Orientation Lock:**
  - `AUTO`: Dynamic rotation following device sensors.
  - `LOCK LANDSCAPE`: Locks orientation to horizontal landscape while allowing 180° gimbal/rig flipping.
  - `LOCK PORTRAIT`: Locks to upright orientation for vertical cinema workflows.
  - **Recording Hold:** Automatically holds orientation locked while recording to prevent mid-take aspect ratio disruption.

### 🎙️ Audio & Telemetry
- **Uncompressed 48kHz WAV Sidecar:** Parallel 24-bit/48kHz uncompressed broadcast WAV recording alongside AAC 384kbps embedded audio.
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
