# 🎬 PixelCineRaw `v1.0.0` (First Official Stable Production Release)

> First official stable production release of PixelCineRaw for Google Pixel (Tensor G4 / Pixel 9a) and Camera2 manual sensor Android devices.

---

### 🌟 Release Highlights

#### 📱 Dual Adaptive UIs
- **Landscape Cinema Rig HUD:** Comprehensive broadcast-grade cinema HUD featuring top status telemetry, dual-side exposure/lens/WB/focus rotary controls, live waveform/histogram overlays, and anamorphic frame lines.
- **Minimal iPhone-Style Vertical Portrait Mode:** Minimalist, sleek one-handed mobile camera interface with an edge-to-edge unclipped `9:16` / `3:4` vertical viewfinder, compact floating top bar, single-pill 56dp borderless exposure strip (`SHUTTER`, `ISO`, `WB`, `FOCUS`, `EV`), haptic rotary wheels, and instant Pro Tools bottom sheet.
- **Three-State Orientation Lock & Hold:**
  - `AUTO`: Dynamic rotation following device sensors.
  - `LOCK LANDSCAPE`: Locks orientation to horizontal landscape while allowing 180° gimbal/rig flipping.
  - `LOCK PORTRAIT`: Locks to upright orientation for vertical cinema workflows.
  - **Recording Hold:** Automatically locks orientation in its current state when recording starts to prevent mid-take aspect ratio disruption.

#### 🎞️ Stutter-Free 10-Bit HDR Video Pipeline
- **Zero-Micro-Stutter 10-Bit HEVC Pipeline:** Mathematical quantized monotonic MP4 frame cadence ($16.666\,\text{ms}$ at 60fps, $33.333\,\text{ms}$ at 30fps, $41.666\,\text{ms}$ at 24fps) eliminates erratic durations in `MediaMuxer` and Google Photos.
- **Tensor G4 Realtime Hardware Priority:** Forces `MediaFormat.KEY_PRIORITY = 0` and `KEY_OPERATING_RATE = fps` to keep the hardware HEVC encoder clock steady without downclocking during complex high-motion pans.
- **HLG10 & HDR10+ (BT.2020):** Wide Dynamic Range with 10-bit Rec.2020 colorimetry (`COLOR_STANDARD_BT2020` + `COLOR_TRANSFER_HLG` or `COLOR_TRANSFER_ST2084` PQ) with live 120Hz OLED preview and embedded MP4 metadata.
- **10-bit ALL-Intra (ALL-I) & Long-GOP:** Frame-by-frame intra coding up to **280 Mbps** for zero temporal compression artifacts, alongside optimized 120 Mbps 10-bit Long-GOP for extended documentary recording.
- **Universal Zero-Stretch Viewfinder:** 1:1 true-to-life square pixels rendered on both the 120Hz live OLED viewfinder and exported MP4 files across all aspect ratios (`16:9`, `4:3 Open Gate`, `2.39:1 CinemaScope`, `3:4`, `9:16`).
- **Anamorphic Lens De-Squeeze:** Real-time GL shader preview de-squeezing for 1.33x, 1.55x, and 2.0x anamorphic glass.
- **Clean Cinema ISP (`EDGE_MODE_OFF`):** Disables synthetic software edge enhancement, digital halos, and chromatic fringing for authentic, organic optical roll-off.
- **3D LUT Engine:** Live OpenGL ES 3D LUT preview with `.cube` file import and optional "Bake LUT into MP4" toggle.

#### 🎛️ True Cinema Exposure & Anti-Banding
- **True 180° Cinema Shutter & Shutter-Priority Auto-ISO:** Authentic optical motion blur (`1/48s` at 24p, `1/60s` at 30p, `1/120s` at 60p) with live 32-bin luma histogram feedback and automatic highlight-clipping `ND NEEDED` warning badge.
- **Sub-Stop ISO & $130\,\text{dp}$/Stop Relative Dial:** Eliminates analog sensor stair-stepping using fine `CONTROL_POST_RAW_SENSITIVITY_BOOST` gain multipliers with a 250ms optical exponential glide and zero-flash auto-to-manual handoff.
- **50Hz / 60Hz Indoor Light Anti-Banding:** Hardware ISP anti-banding mode (`50Hz`, `60Hz`, `AUTO`, `OFF`) with automatic flicker-free $1/50\,\text{s}$ and $1/100\,\text{s}$ shutter harmonic synchronization for European/PAL AC indoor lighting.
- **Precision Rotary Dials:** Haptic-responsive rotary wheels for White Balance (Kelvin 2300K–8000K + Tint), Manual Focus with hyperfocal marks, and EV Bias.

#### 📐 Precision Optics & Pro Tools
- **VSYNC-Synced Cylindrical Zoom Wheel:** Smooth logarithmic zoom hub supporting instant preset taps (`0.5x`, `1x`, `2x`, `5x`) and drag-to-slide precision zooming up to 8.0x with zero frame skipping.
- **Non-Linear Cinema Follow-Focus Wheel:** High-precision manual focus with hyperfocal marks and programmable A → B rack focus (1.0s–3.0s).
- **Multi-Tier Stabilization:** Real hardware optical image stabilization (`OFF / OIS / OIS+EIS`) for maximum sensor real estate or rock-solid handheld shots.
- **Monitoring & Assist Suite:** Electronic Horizon Leveler, Live RGB/Luma Histogram, Luma Waveform Monitor, Arri-Style False Color, Focus Peaking, and Exposure Zebras (75%–100% IRE).
- **Independent Torch Control:** Constant illumination assist toggleable on the fly without affecting camera capture mode.
- **Professional Audio:** Embedded 48kHz 320kbps AAC audio alongside optional 48kHz 24-bit uncompressed WAV sidecar recording via a non-blocking 256 KB buffered I/O thread. Automatic USB-C microphone detection with live UI status indicator.
- **Full Persistent Settings:** Saves and immediately restores all camera format, resolution, FPS, color profile, bitrate, ISP mode, stabilization, orientation, lens, and overlay configurations across app restarts via `SharedPreferences`.

---

### 📊 Build Specifications

| Specification | Value |
| :--- | :--- |
| **Release Tag** | `v1.0.0` |
| **Version Code** | `10` |
| **Version Name** | `1.0.0` |
| **APK Binary** | `PixelCineRaw-v1.0.0.apk` |
| **File Size** | **60.12 MB** (63,039,079 bytes) |
| **SHA-256 Checksum** | `dbfa127d7771920437db9a02b402732aefc832900c7567ee7edb995ec632c843` |
| **Target Architecture** | Universal / `arm64-v8a` |
| **Android Compatibility** | Android 15+ (`minSdk 35`, `targetSdk 37`, `compileSdk 37`) |
| **Software License** | [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0) |
