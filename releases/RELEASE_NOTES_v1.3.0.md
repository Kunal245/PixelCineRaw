# PixelCineRaw v1.3.0 Release Notes

**Release Date:** November 25, 2026  
**Version:** `1.3.0` (`versionCode = 17`)  
**Target Hardware:** Google Pixel 9a (Tensor G4 ISP) & Modern Android 14+ Devices  
**License:** GPL-3.0  

---

## 🌟 Highlights

PixelCineRaw `v1.3.0` brings a major upgrade to the dynamic range, highlight preservation, auto-exposure engine, and live viewfinder fidelity across all color profiles (`REC.709`, `PIXEL-LOG`, `HLG10`, and `HDR10+`).

### 1. Neutral White Highlight Preservation & Clean Rec.709 Path
- **Zero Blue/Cyan Highlight Hue Shift:** Eliminated per-channel vector compression in `HLG10`, `HDR10+`, and `PIXEL-LOG`. Instead, computes scalar luminance $L = \mathbf{x} \cdot (0.2126, 0.7152, 0.0722)$ and applies smooth highlight compression strictly to $L$, scaling $(R, G, B)$ proportionally. Preserves 100% accurate chromaticity so bright windows, reflections, and lights stay neutral white without electric blue or cyan artifacts.
- **Native Tensor G4 Rec.709 Pipeline:** When `REC.709` is active, Camera2 engages `TONEMAP_MODE_HIGH_QUALITY` without custom contrast curves or underexposure biases, and the OpenGL ES pipeline outputs unmodified Rec.709 colorimetry.

### 2. True Live 10-Bit HDR OLED Viewfinder with Dynamic SDR/HDR EGLSurface Recreation
- **Dynamic EGLSurface Reallocation:** Whenever switching between HDR (`HLG10` / `HDR10+`) and SDR (`REC.709` / `PIXEL-LOG`), `HlgGlRenderer.kt` cleanly destroys and recreates the EGL window surface on the render thread with the exact colorspace attribute:
  - **`HLG10`:** `EGL_GL_COLORSPACE_KHR = 0x3540` (`EGL_GL_COLORSPACE_BT2020_HLG_EXT`) + `SurfaceControl.setDataSpace(DATASPACE_BT2020_HLG)` + `setExtendedRangeBrightness(4.0f, 4.0f)` + `window.colorMode = COLOR_MODE_HDR`.
  - **`HDR10+`:** `EGL_GL_COLORSPACE_KHR = 0x3340` (`EGL_GL_COLORSPACE_BT2020_PQ_EXT`) + `SurfaceControl.setDataSpace(DATASPACE_BT2020_PQ)` + `setExtendedRangeBrightness(4.0f, 4.0f)` + `window.colorMode = COLOR_MODE_HDR`.
  - **`REC.709` & `PIXEL-LOG`:** `EGL_GL_COLORSPACE_KHR = 0x3089` (`EGL_GL_COLORSPACE_SRGB_KHR`) + `SurfaceControl.setDataSpace(DATASPACE_SRGB)` + `setExtendedRangeBrightness(1.0f, 1.0f)` + `window.colorMode = COLOR_MODE_DEFAULT`.
- **Persistent Surface Lifecycle:** Preserves `currentSurface` across EGL allocations so subsequent profile cycles immediately take effect without detaching or freezing the viewfinder.
- **HDR10+ 1,000-Nit Peak Luminance Mapping:** Calibrates diffuse white (`0.5`) to ~200 nits and smoothly scales specular highlights up to 1,000 nits before ST.2084 PQ encoding, making HDR10+ glow on OLED displays.

### 3. `mcpro24fps`-Style Center-Weighted AE Metering (`METER: CENTER / MATRIX`)
- **Ceiling Light Anti-Dimming:** Prevents ceiling tube lights, chandeliers, lamps, and bright windows from triggering sudden exposure drops when panning.
- **Center-Weighted Metering Regions:** Dynamically computes active sensor array (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`) and configures `CONTROL_AE_REGIONS` with a core subject zone covering the middle **38% × 38%** (`weight = 1000`) and a low-weight peripheral guard (`weight = 150`).
- **Scene Mode Locking:** Locks `CONTROL_MODE = CONTROL_MODE_AUTO` and `CONTROL_SCENE_MODE = CONTROL_SCENE_MODE_DISABLED` to prevent OEM night/backlight heuristic overrides from pumping brightness mid-pan.
- **Quick-Toggle Pill & Popover Menu:** Dedicated `METER: CENTER / MATRIX` toggle pill in the Top Status Bar and segmented selector in the Format popover across both Landscape and Portrait modes.

### 4. Native Tensor G4 Hardware Auto-Exposure (`CONTROL_AE_MODE_ON`) Across ALL Modes
- **Hardware-Driven 60Hz Metering:** Replaces custom software Auto-ISO feedback loops with Google Tensor G4's native hardware Auto-Exposure engine (`CONTROL_AE_MODE = CONTROL_AE_MODE_ON`) whenever `ISO == AUTO`.
- **Zero Software Stepping or Hunting:** Tensor G4's neural multi-zone metering and hardware analog/digital gain smoothing run natively at the sensor refresh rate, delivering silky-smooth, instantaneous exposure balance.
- **Strict FPS & Anti-Banding Locking:** Locks `CONTROL_AE_TARGET_FPS_RANGE = [targetFps, targetFps]` and `CONTROL_AE_ANTIBANDING_MODE = CONTROL_AE_ANTIBANDING_MODE_50HZ` (or user's selected mode) to eliminate flicker and maintain cinema frame rates.
- **Immediate Manual Overrides:** Clean, instantaneous handoff to `CONTROL_AE_MODE_OFF` whenever manual ISO or manual Shutter is dialed, and instant return to `CONTROL_AE_MODE_ON` upon tapping `AUTO ISO`.

---

## 📦 Artifact Verification

- **Package File:** `releases/PixelCineRaw-v1.3.0.apk`
- **SHA-256 Checksum:** `e5df49a183a6c59cbc885263b3ea07556d25ace1664eacc26126c5452151872c`
