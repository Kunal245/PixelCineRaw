# 🎬 PixelCineRaw `v0.1.0-alpha.1`

> **Initial Alpha Release:** Working Pixel 9a cinema camera with universal zero-stretch viewfinder, smooth zoom, and HLG HDR pipeline.

---

### 📊 Build Specifications

| Specification | Value |
| :--- | :--- |
| **Release Tag** | `v0.1.0-alpha.1` |
| **Commit SHA** | [`ceb572a`](https://github.com/kunalnimje/PixelCineRaw/commit/ceb572ad913e149d0a5a8bc8c6846dc47f691ed5) |
| **Release Date** | `2026-11-23 23:45:49 +0530` |
| **APK Binary** | `PixelCineRaw-v0.1.0-alpha.1.apk` |
| **File Size** | **59.59 MB** (62,484,108 bytes) |
| **SHA-256 Checksum** | `0ae483167c01ea654485bd454e9fa7a52942bd5c5b24ba36ff6a7dfe21bff848` |
| **Android Compatibility** | Android 15+ (`minSdk 35`, `targetSdk 37`, `compileSdk 37`) |
| **Target Architecture** | Universal / `arm64-v8a` |
| **Hardware Target** | Google Pixel 9a (Tensor G4) & Camera2 Manual Sensor |
| **License** | [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0) |

---

### 🌟 Key Highlights & Features
- **Universal Zero-Stretch Viewfinder:** Replacement of stretched preview texture with `AutoFitSurfaceView` for 1:1 square-pixel aspect ratios across all resolutions (`16:9`, `4:3 Open Gate`, `2.39:1`).
- **HLG10 HDR Preview:** OpenGL ES BT.2020 HLG shader pipeline for true 10-bit HDR OLED live preview and output.
- **Logarithmic Pinch-to-Zoom:** Smooth zoom interpolation and cinema slider.
- **Hardware Aspect Ratios:** Unlocked native sensor configurations up to 4K resolution.

---

### 📦 Installation & Verification

1. **Direct Download:** Download `PixelCineRaw-v0.1.0-alpha.1.apk` attached below.
2. **Verify Checksum:**
   ```bash
   # Windows PowerShell:
   Get-FileHash PixelCineRaw-v0.1.0-alpha.1.apk -Algorithm SHA256

   # Linux / macOS:
   sha256sum PixelCineRaw-v0.1.0-alpha.1.apk
   ```
   **Expected Hash:**
   `0ae483167c01ea654485bd454e9fa7a52942bd5c5b24ba36ff6a7dfe21bff848`
3. **Install via ADB:**
   ```bash
   adb install -r PixelCineRaw-v0.1.0-alpha.1.apk
   ```
