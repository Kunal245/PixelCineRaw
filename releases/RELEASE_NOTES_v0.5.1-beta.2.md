# 🎬 PixelCineRaw `v0.5.1-beta.2`

> **Public Open-Source Beta 2 Release:** Flagship Android cinema camera with Orientation Lock (`Auto / Landscape / Portrait`), recording hold, Pro Cinema Suite (3D LUTs, False Color, Scopes, A→B Rack Focus, Gyroflow .gcsv), and GNU GPLv3 open-source licensing.

---

### 📊 Build Specifications

| Specification | Value |
| :--- | :--- |
| **Release Tag** | `v0.5.1-beta.2` |
| **Commit SHA** | [`d091a5d`](https://github.com/kunalnimje/PixelCineRaw/commit/d091a5d1365e34eb1d004c6b9eb7de1972fa89a7) |
| **Release Date** | `2026-11-24 03:30:42 +0530` |
| **APK Binary** | `PixelCineRaw-v0.5.1-beta.2.apk` |
| **File Size** | **60.01 MB** (62,928,235 bytes) |
| **SHA-256 Checksum** | `0a38bdea9dc11497b59c834e10eb42560cfd45fe69de7740908e8518e81eeb6c` |
| **Android Compatibility** | Android 15+ (`minSdk 35`, `targetSdk 37`, `compileSdk 37`) |
| **Target Architecture** | Universal / `arm64-v8a` |
| **Hardware Target** | Google Pixel 9a (Tensor G4) & Camera2 Manual Sensor |
| **License** | [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0) |

---

### 🌟 Key Highlights & Features
- **Three-State Orientation Lock:**
  - `AUTO`: Dynamic gyroscope/accelerometer orientation adaptation.
  - `LOCK LANDSCAPE`: Locks to landscape while allowing 180° gimbal/rig flipping.
  - `LOCK PORTRAIT`: Upright lock for vertical cinema workflows (Reels / Shorts).
  - **Recording Hold:** Automatically holds orientation locked while video recording is actively running to prevent mid-take aspect ratio disruption.
- **Toggleable Pro Cinema Suite:**
  - 3D LUT Engine with `.cube` file import & preview vs. bake toggle.
  - ARRI-style False Color IRE luminance exposure mapping shader.
  - Cinema Scopes: Real-time Luma Waveform Monitor & RGB/Luma Histogram.
  - Programmable A → B Cinema Rack Focus with smooth easing transitions.
  - Gyroflow IMU Logger: High-rate `.gcsv` sidecar logging for gyro post-stabilization.
  - Anamorphic Lens De-Squeeze preview (1.33x, 1.55x, 2.0x).
- **Open-Source Compliance & Privacy:**
  - Full GNU General Public License v3.0 (GPLv3).
  - 100% offline, zero network permissions, zero telemetry.

---

### 📦 Installation & Verification

1. **Direct Download:** Download `PixelCineRaw-v0.5.1-beta.2.apk` attached below.
2. **Verify Checksum:**
   ```bash
   # Windows PowerShell:
   Get-FileHash PixelCineRaw-v0.5.1-beta.2.apk -Algorithm SHA256

   # Linux / macOS:
   sha256sum PixelCineRaw-v0.5.1-beta.2.apk
   ```
   **Expected Hash:**
   `0a38bdea9dc11497b59c834e10eb42560cfd45fe69de7740908e8518e81eeb6c`
3. **Install via ADB:**
   ```bash
   adb install -r PixelCineRaw-v0.5.1-beta.2.apk
   ```

---

### 📝 Changelog (since `v0.5.0-beta.1`)
```
d091a5d docs(oss): add GPLv3 license, comprehensive README, contribution/security guides, and GitHub templates
587aaa8 feat(ui): add Auto/Landscape/Portrait orientation lock and recording orientation hold
b7ac1b1 feat(rc1): add front camera, adaptive portrait/landscape rotation, and toggleable pro cinema suite
```
