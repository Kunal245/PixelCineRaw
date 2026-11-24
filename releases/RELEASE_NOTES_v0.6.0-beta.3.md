# 🎬 PixelCineRaw `v0.6.0-beta.3`

> **Public Open-Source Beta 3 Release:** Flagship Android cinema camera with stutter-free 10-bit HLG/HDR10+ video output, true 180° shutter priority motion blur, 50Hz indoor anti-banding, iPhone-smooth sub-stop ISO & zoom dials, and full persistent settings across app restarts.

---

### 📊 Build Specifications

| Specification | Value |
| :--- | :--- |
| **Release Tag** | `v0.6.0-beta.3` |
| **Commit SHA** | [`7bf49cf`](https://github.com/kunalnimje/PixelCineRaw/commit/7bf49cfa9a2f604f5c1ae83e91ff6a14fc7ef88d) |
| **Release Date** | `2026-11-24 11:53:00 +0530` |
| **APK Binary** | `PixelCineRaw-v0.6.0-beta.3.apk` |
| **File Size** | **59.97 MB** (62,886,355 bytes) |
| **SHA-256 Checksum** | `9c8614b15905f5913e531961769ae181d3c711f4a31538bf9f540c70282e8f43` |
| **Android Compatibility** | Android 15+ (`minSdk 35`, `targetSdk 37`, `compileSdk 37`) |
| **Target Architecture** | Universal / `arm64-v8a` |
| **Hardware Target** | Google Pixel 9a (Tensor G4) & Camera2 Manual Sensor |
| **License** | [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0) |

---

### 🌟 Key Highlights & Features

- **Zero-Micro-Stutter 10-Bit HEVC Pipeline:**
  - Mathematical quantized monotonic MP4 frame cadence ($16.666\,\text{ms}$ at 60fps, $33.333\,\text{ms}$ at 30fps, $41.666\,\text{ms}$ at 24fps) eliminates erratic durations in `MediaMuxer` and Google Photos.
  - Tensor G4 Realtime Hardware Priority: Forces `MediaFormat.KEY_PRIORITY = 0` and `KEY_OPERATING_RATE = fps` to keep the hardware HEVC encoder clock steady without downclocking during complex high-motion pans.
  - Synchronized Audio/Video $T=0$ epoch: Discards pre-roll audio before the first video frame arrives, mathematically locking sample counts to eliminate clock drift.
  - Buffered Disk I/O: 256 KB `BufferedOutputStream` on a dedicated background I/O thread (`PixelCineWavIo`) so optional uncompressed WAV sidecar writes never block encoding.

- **True 180° Cinema Shutter & Shutter-Priority Auto-ISO:**
  - Authentic optical motion blur ($1/48\,\text{s}$ at 24p, $1/60\,\text{s}$ at 30p, $1/120\,\text{s}$ at 60p).
  - Shutter-Priority Auto-ISO loop via 32-bin luma histogram maintaining $18\%$ gray midtones.
  - Highlight-clipping `ND NEEDED` warning badge with one-tap `AUTO SHUTTER` recovery.

- **Sub-Stop ISO & $130\,\text{dp}$/Stop Relative Dial:**
  - Eliminates analog sensor stair-stepping using fine `CONTROL_POST_RAW_SENSITIVITY_BOOST` gain multipliers with a 250ms optical exponential glide.
  - $130\,\text{dp}$/stop relative logarithmic dragging with zero-flash auto-to-manual handoff.

- **50Hz / 60Hz Indoor Light Anti-Banding:**
  - Hardware ISP anti-banding mode (`50Hz`, `60Hz`, `AUTO`, `OFF`) with instant top bar toggle pill.
  - Automatic flicker-free $1/50\,\text{s}$ and $1/100\,\text{s}$ harmonic shutter sync for 50Hz AC indoor lighting.

- **Full Persistent Settings Across Restarts:**
  - Automatically saves and restores all camera format, resolution, FPS, color profile, bitrate, ISP mode, stabilization, orientation, lens, and overlay configurations across app restarts via `SharedPreferences`.

- **Three-State Orientation Lock & Hold:**
  - `AUTO`: Dynamic gyroscope/accelerometer rotation.
  - `LOCK LANDSCAPE`: Locks orientation to horizontal landscape while allowing 180° gimbal/rig flipping.
  - `LOCK PORTRAIT`: Upright lock for vertical cinema workflows (Reels / Shorts).
  - `Recording Hold`: Prevents aspect ratio disruption during active recordings.

---

### 📦 Installation & Verification

1. **Direct Download:** Download `PixelCineRaw-v0.6.0-beta.3.apk` attached below.
2. **Verify Checksum:**
   ```bash
   # Windows PowerShell:
   Get-FileHash PixelCineRaw-v0.6.0-beta.3.apk -Algorithm SHA256

   # Linux / macOS:
   sha256sum PixelCineRaw-v0.6.0-beta.3.apk
   ```
   **Expected Hash:**
   `9c8614b15905f5913e531961769ae181d3c711f4a31538bf9f540c70282e8f43`
3. **Install via ADB:**
   ```bash
   adb install -r PixelCineRaw-v0.6.0-beta.3.apk
   ```

---

### 📝 Changelog (since `v0.5.1-beta.2`)
```
7bf49cf release(beta3): finalize stutter-free 10-bit HLG/HDR10+ encoder, 180-degree shutter priority, 50Hz anti-banding, and persistent settings
a77f05a fix(recorder): enforce quantized MP4 frame PTS cadence, realtime encoder priority, and buffered disk I/O
ed27510 feat(exposure): add iPhone-smooth sub-stop ISO, 180-degree shutter priority motion blur, and 50Hz indoor anti-banding
6d23501 fix(recorder): use async MediaCodec callback, monotonic MP4 timestamps, and Tensor-tuned 120Mbps 10-bit GOP defaults for stutter-free output
dc273ea fix(perf): remove display eglPresentationTimeANDROID, sync GL draw 1:1 to onFrameAvailable, and stop idle setRepeatingRequest calls
244c146 perf(ultra-smooth): lock VSYNC frame pacing, zero-allocation GL loop, and cinema-damped fluid dials across all controls
bb41672 feat(settings-controls): persist all camera settings across restarts and add VSYNC-synced ultrawide ISO and cinema focus wheel
```
