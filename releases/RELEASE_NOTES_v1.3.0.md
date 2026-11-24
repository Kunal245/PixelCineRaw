# PixelCineRaw v1.3.0 Release Notes

**Release Date:** November 25, 2026  
**Version:** `1.3.0` (`versionCode = 17`)  
**Target Hardware:** Google Pixel 9a (Tensor G4 ISP) & Modern Android 14+ Devices  
**License:** GPL-3.0  

---

## 🌟 Highlights

PixelCineRaw `v1.3.0` brings a major upgrade to the dynamic range, highlight preservation, auto-exposure engine, and live viewfinder fidelity across all color profiles (`REC.709`, `PIXEL-LOG`, `HLG10`, and `HDR10+`).

### 1. True Live 10-Bit HDR OLED Viewfinder (Actua Display)
- **10-Bit EGL Surface Rendering:** Selects a native 10-bit `RGBA_1010102` (`EGL_RED_SIZE=10, EGL_GREEN_SIZE=10, EGL_BLUE_SIZE=10, EGL_ALPHA_SIZE=2`) EGL configuration for the live preview window surface.
- **Hardware Extended Colorspace EGL Attributes:** Automatically detects and applies `EGL_GL_COLORSPACE_BT2020_HLG_EXT` (`0x3540`) for `HLG10` and `EGL_GL_COLORSPACE_BT2020_PQ_EXT` (`0x3340`) for `HDR10+` when constructing the viewfinder EGLSurface.
- **SurfaceControl Extended Dynamic Range (Android 14+ / API 34+):** Directly drives Android's hardware composer via `SurfaceControl.Transaction`:
  - `HLG10`: `setDataSpace(sc, DataSpace.DATASPACE_BT2020_HLG)` with `setExtendedRangeBrightness(sc, 4.0f, 4.0f)`.
  - `HDR10+`: `setDataSpace(sc, DataSpace.DATASPACE_BT2020_PQ)` with `setExtendedRangeBrightness(sc, 4.0f, 4.0f)`.
  - `REC.709` / `PIXEL-LOG`: `setDataSpace(sc, DataSpace.DATASPACE_SRGB)` with `setExtendedRangeBrightness(sc, 1.0f, 1.0f)`.
- **Activity Window HDR Mode:** Configures `window.colorMode = ActivityInfo.COLOR_MODE_HDR` during HDR workflows to grant full access to peak Actua OLED display luminance without tone-mapping compression or SDR clipping.
- **Seamless Dynamic Switcher:** Hot-swaps EGL window surfaces seamlessly on the GL render thread when switching between SDR and HDR color profiles without restarting camera preview or dropping frames.

### 2. Native Tensor G4 Hardware Auto-Exposure (`CONTROL_AE_MODE_ON`) Across ALL Modes
- **Hardware-Driven 60Hz Metering:** Replaces custom software Auto-ISO feedback loops with Google Tensor G4's native hardware Auto-Exposure engine (`CONTROL_AE_MODE = CONTROL_AE_MODE_ON`) whenever `ISO == AUTO`.
- **Zero Software Stepping or Hunting:** Tensor G4's neural multi-zone metering and hardware analog/digital gain smoothing run natively at the sensor refresh rate, delivering silky-smooth, instantaneous exposure balance.
- **Strict FPS & Anti-Banding Locking:** Locks `CONTROL_AE_TARGET_FPS_RANGE = [targetFps, targetFps]` and `CONTROL_AE_ANTIBANDING_MODE = CONTROL_AE_ANTIBANDING_MODE_50HZ` (or user's selected mode) to eliminate flicker and maintain cinema frame rates.
- **Immediate Manual Overrides:** Clean, instantaneous handoff to `CONTROL_AE_MODE_OFF` whenever manual ISO or manual Shutter is dialed, and instant return to `CONTROL_AE_MODE_ON` upon tapping `AUTO ISO`.

### 3. -1.0 EV Highlight Headroom & Midtone Recovery for HDR & Log Modes
- **Hardware Under-Exposure Bias:** In `HLG10`, `HDR10+`, and `PIXEL-LOG` modes, automatically biases `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION` by `-1.0 EV` (`targetEv = state.evCompensation - 1.0f`) to preserve specular highlights and prevent sensor clipping, while keeping the on-screen UI readout centered at the user's selected EV bias.
- **GL Shader Midtone Recovery & Logarithmic Roll-Off:** In `HlgGlRenderer.kt` (`precision highp float;`), applies a `+1.0 EV` (`2.0x`) linear midtone gain recovery paired with a smooth normalized logarithmic highlight roll-off (`(x / (1.0 + x * 0.35)) * 1.35`) before OETF encoding:
  - Midtones (`x = 1.0`) are mathematically restored to standard exposure (`1.0x`).
  - Highlights smoothly compress with zero abrupt knee or color shifting, recovering an extra stop of dynamic range in bright clouds, skies, and neon lights.
- **Standard Rec.709 Consistency:** `REC.709` mode retains standard `0.0 EV` compensation and `1.0x` output with its dedicated highlight shoulder.

---

## 📦 Artifact Verification

- **Package File:** `releases/PixelCineRaw-v1.3.0.apk`
- **SHA-256 Checksum:** `8cda519a43e65382122dc091c2753e35ff5dad1efa0cec8cdf3e62f2121e0ec6`
