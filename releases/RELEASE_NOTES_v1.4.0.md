# PixelCineRaw v1.4.0 Release Notes

**Release Date:** November 25, 2026  
**Version:** `1.4.0` (`versionCode = 18`)  
**Target Hardware:** Google Pixel 9a (Tensor G4 ISP) & Modern Android 14+ Devices  
**License:** GPL-3.0  

---

## 🌟 Highlights

PixelCineRaw `v1.4.0` introduces the **1:1 WYSIWYG Viewfinder Engine**, bringing exact color, contrast, and brightness parity between the live viewfinder and final recorded video files across both SDR (`REC.709` / `PIXEL-LOG`) and HDR (`HLG10` / `HDR10+`) in Portrait and Landscape orientations, alongside neutral BT.2020 color science and stable center-weighted Auto-Exposure.

### 1. True 1:1 WYSIWYG SDR & HDR Live Viewfinder
- **Zero Double-Gamma Washout on SDR & Log:** In `REC.709` and `PIXEL-LOG` modes, the viewfinder `EGLSurface` is now created with standard default EGL attributes (`intArrayOf(EGL14.EGL_NONE)`) instead of `EGL_GL_COLORSPACE_SRGB_KHR` (which applied an unwanted second hardware sRGB gamma lift that washed out blacks and muted midtones). Combined with `SurfaceControl.setDataSpace(DATASPACE_SRGB)` and standard `1.0f` brightness, the live preview now matches the saved MP4 recording **1:1 with deep punchy blacks, rich contrast, and zero fading**.
- **True Glowing HDR OLED Preview for HLG10 & HDR10+:** In `HLG10` and `HDR10+`, the viewfinder dynamically recreates its `EGLSurface` with wide-gamut attributes (`EGL_GL_COLORSPACE_BT2020_HLG_EXT` `0x3540` and `EGL_GL_COLORSPACE_BT2020_PQ_EXT` `0x3340` on 10-bit `RGBA_1010102`), paired with `SurfaceControl.setDataSpace(DATASPACE_BT2020_HLG / PQ)`, `setExtendedRangeBrightness(4.0f, 4.0f)`, and `window.colorMode = COLOR_MODE_HDR`. Highlights glow with full OLED intensity while SDR remains strictly standard.
- **Orientation Continuity (Portrait & Landscape):** Added `onConfigurationChanged` synchronization across `AutoFitSurfaceView`, `MainActivity`, and `HlgGlRenderer` to ensure `SurfaceControl` data spaces and brightness levels re-assert themselves instantly during rotation without flickering or dropping HDR/SDR states.
- **Default Clean Monitoring (LUT Defaults to OFF):** 3D LUT simulation now defaults to **OFF** on fresh startup, ensuring that filmmakers see the clean, unadulterated sensor output by default.
- **Automatic SDR LUT Bypass in HDR Modes:** When `HLG10` or `HDR10+` is active, SDR LUT simulation is automatically bypassed in both preview and encoder passes, preventing SDR color curves from distorting wide-gamut HDR colorimetry.

### 2. Neutral White Highlights & Forward BT.2020 Color Science
- **Tensor G4 AWB & High-Quality Tonemapping:** `CaptureRequest.TONEMAP_MODE = TONEMAP_MODE_HIGH_QUALITY` and `CaptureRequest.COLOR_CORRECTION_MODE = COLOR_CORRECTION_MODE_HIGH_QUALITY` are enforced across all modes, giving Tensor G4's ISP hardware tonemapper and AWB full latitude to keep indoor tube lights, windows, and walls pure neutral white.
- **Pure Neutral HDR10+ (PQ) Highlights:** In `encodePq`, replaced per-channel non-linear power expansion (`pow((linear - 0.5)*2.0, 1.4)`) with direct proportional linear mapping (`nits = clamp(linearBt2020 * 1000.0, 0.0, 1000.0)`), completely eliminating blue/cyan halo rings around indoor tube lights and specular reflections.
- **Scalar Luminance Roll-Off (Zero Highlight Hue Shift):** Highlights in `HLG10`, `HDR10+`, and `PIXEL-LOG` are compressed via scalar luminance $L = \mathbf{x} \cdot (0.2126, 0.7152, 0.0722)$ and scaled proportionally ($\mathbf{x} \cdot (L_{\text{comp}} / L)$) before the column-major `REC709_TO_REC2020` matrix and OETF, completely eliminating cyan/blue tinting in specular highlights.

### 3. Center-Weighted Tensor Hardware Auto-Exposure (`METER: CENTER`)
- **Ceiling Light Anti-Dimming Protection:** Keeps `CONTROL_AE_REGIONS` center-weighted to the middle 38% subject zone (`weight = 1000`) with a low-weight peripheral guard (`weight = 150`), preventing ceiling tube lights from plunging room exposure during pans.
- **Native Hardware AE (`CONTROL_AE_MODE_ON`):** Tensor G4's neural multi-zone hardware metering runs natively at 60Hz whenever `ISO == AUTO`, delivering instantaneous exposure adaptation without software stepping or hunting.

---

## 📦 Artifact Verification

- **Package File:** `releases/PixelCineRaw-v1.4.0.apk`
- **SHA-256 Checksum:** `DC5F0696A19D790741D5305FCFFE31ABCB5E0728352A13D52DA40365DC9E7EA6`
