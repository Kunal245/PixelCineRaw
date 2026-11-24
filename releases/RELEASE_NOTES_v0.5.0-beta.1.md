# 🎬 PixelCineRaw `v0.5.0-beta.1`

> **Beta 1 Release:** Pixel 9a cinema camera with 10-bit ALL-I 280Mbps HLG/HDR10+, clean ISP, 3-mode OIS+EIS stabilization, and iPhone-smooth zoom wheel.

---

### 📊 Build Specifications

| Specification | Value |
| :--- | :--- |
| **Release Tag** | `v0.5.0-beta.1` |
| **Commit SHA** | [`a928607`](https://github.com/kunalnimje/PixelCineRaw/commit/a928607d5add2e075fe35d1a39c3236ba0d77abc) |
| **Release Date** | `2026-11-24 02:36:33 +0530` |
| **APK Binary** | `PixelCineRaw-v0.5.0-beta.1.apk` |
| **File Size** | **59.82 MB** (62,728,172 bytes) |
| **SHA-256 Checksum** | `4003d5752d0ae3788bdc04446b2643004fb5b04e3fd88fc56ed79da5c21a339e` |
| **Android Compatibility** | Android 15+ (`minSdk 35`, `targetSdk 37`, `compileSdk 37`) |
| **Target Architecture** | Universal / `arm64-v8a` |
| **Hardware Target** | Google Pixel 9a (Tensor G4) & Camera2 Manual Sensor |
| **License** | [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0) |

---

### 🌟 Key Highlights & Features
- **10-bit ALL-Intra 280Mbps HEVC:** Uncompressed intra-frame capture with zero inter-frame motion blur or temporal compression artifacts.
- **Stabilization Modes (`OFF / OIS / OIS+EIS`):** Direct hardware control over Optical and Electronic Image Stabilization.
- **iPhone-Style Cylindrical Zoom Wheel:** Unified tap-and-slide gesture bar with preset buttons (`0.5x`, `1x`, `2x`, `5x`) and seamless slider dragging locked to Camera2 frame VSYNC.
- **Tap-to-Focus Reticle Alignment:** Fixed touch coordinate offset between viewfinder surface and overlay bracket with integrated EV bias slider.
- **Clean Cinema ISP (`EDGE_MODE_OFF`):** Zero synthetic oversharpening for organic, filmic optical texture.
- **Uncompressed 48kHz WAV Sidecar:** Parallel high-fidelity audio recording alongside 384kbps AAC.

---

### 📦 Installation & Verification

1. **Direct Download:** Download `PixelCineRaw-v0.5.0-beta.1.apk` attached below.
2. **Verify Checksum:**
   ```bash
   # Windows PowerShell:
   Get-FileHash PixelCineRaw-v0.5.0-beta.1.apk -Algorithm SHA256

   # Linux / macOS:
   sha256sum PixelCineRaw-v0.5.0-beta.1.apk
   ```
   **Expected Hash:**
   `4003d5752d0ae3788bdc04446b2643004fb5b04e3fd88fc56ed79da5c21a339e`
3. **Install via ADB:**
   ```bash
   adb install -r PixelCineRaw-v0.5.0-beta.1.apk
   ```

---

### 📝 Changelog (since `v0.1.0-alpha.1`)
```
a928607 feat(stab-focus): add OFF/OIS/OIS+EIS stabilization modes and fix tap-to-focus coordinate alignment
59dd776 feat(ui-zoom): redesign iPhone-style unified tap-and-slide cylindrical zoom wheel
a142b21 perf(zoom): sync Camera2 zoom updates to frame VSYNC and build 120Hz inertial Canvas zoom wheel
ab74f06 fix(ui-zoom): add Format/Codec quick popovers, fix 2x/5x zoom taps, and upgrade to ultra-fluid cinema zoom engine
0059a4d feat(ui): redesign modern lightweight cinema HUD with torch control, horizon leveler, tap-to-focus EV, and clip preview
170df6d fix(gl): fix GL_RGBA16F FBO completeness, zero-division uTexelSize, and SurfaceTexture stream bounds
5df3bdf feat(max-out): implement 10-bit All-Intra 280Mbps HEVC, GL_RGBA16F Lanczos supersampling, HDR10+, and studio audio
```
