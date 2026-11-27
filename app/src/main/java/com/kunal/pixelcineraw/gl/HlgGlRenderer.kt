package com.kunal.pixelcineraw.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.HistogramData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance OpenGL ES Cinema Render Pipeline (mcpro24fps architecture).
 *
 * Keeps Camera2 streaming continuously into a single offscreen SurfaceTexture (OES) at 60fps.
 * Toggling between REC.709, PIXEL-LOG, and HLG happens instantly inside the GLSL fragment
 * shader (uColorMode uniform) with ZERO CameraCaptureSession restart and ZERO SurfaceView recreation!
 *
 * Guarantees that:
 * 1. SurfaceTexture buffer size is configured before creating input Surface and CaptureSession.
 * 2. EGL14.eglMakeCurrent() is ALWAYS active BEFORE calling SurfaceTexture.updateTexImage().
 * 3. Standard GLES OES external texture shaders and full SurfaceView viewports are used without distortion.
 */
/**
 * Thread-safe 32-entry circular buffer matching Camera2 SENSOR_TIMESTAMP (nanoseconds)
 * to actual hardware SENSOR_SENSITIVITY applied by the camera sensor.
 */
class TimestampIsoMap(private val capacity: Int = 32) {
    private val timestamps = LongArray(capacity)
    private val isos = IntArray(capacity)
    private var writeIndex = 0

    @Synchronized
    fun put(timestampNs: Long, iso: Int) {
        timestamps[writeIndex] = timestampNs
        isos[writeIndex] = iso
        writeIndex = (writeIndex + 1) % capacity
    }

    @Synchronized
    fun get(targetTimestampNs: Long): Int? {
        var closestDiff = Long.MAX_VALUE
        var matchedIso: Int? = null
        for (i in 0 until capacity) {
            val ts = timestamps[i]
            if (ts == 0L) continue
            val diff = Math.abs(ts - targetTimestampNs)
            if (diff < closestDiff) {
                closestDiff = diff
                matchedIso = isos[i]
            }
        }
        return if (closestDiff <= 65_000_000L) matchedIso else null
    }
}

class HlgGlRenderer(private val context: Context? = null) {

    companion object {
        private const val TAG = "HlgGlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340

        // Full-screen Quad geometry (clip-space bounds)
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        )

        // Texture coordinates matching clip-space quad
        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        )

        private const val VERTEX_SHADER_CODE = """
uniform mat4 uSTMatrix;
uniform mat4 uAspectMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uSTMatrix * uAspectMatrix * aTextureCoord).xy;
}
"""

        private const val BLIT_VERTEX_SHADER_CODE = """
uniform mat4 uTexMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uTexMatrix * aTextureCoord).xy;
}
"""

        private const val BLIT_FRAGMENT_SHADER_CODE = """
precision highp float;
uniform sampler2D sFboTexture;
varying vec2 vTextureCoord;

void main() {
    gl_FragColor = texture2D(sFboTexture, vTextureCoord);
}
"""

        private const val FRAGMENT_SHADER_CODE = """
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES sTexture;
uniform int uColorMode; // 0 = REC.709, 1 = PIXEL-LOG, 2 = HLG / BT.2020, 3 = HDR10+ PQ
uniform int uFalseColor; // 0 = off, 1 = on
uniform int uLutPreset;  // 0 = off, 1 = REC.709 PRO, 2 = KODAK 2383, 3 = TEAL & ORANGE
uniform int uDeband;     // 0 = off (pure single-pass), 1 = on (2-tap diagonal jittered sky smoothing)
uniform vec2 uTexelSize;
uniform float uExposureTrim;
varying vec2 vTextureCoord;

// Rec.709 to Rec.2020 3x3 color conversion matrix (column-major in GLSL)
const mat3 REC709_TO_REC2020 = mat3(
    0.6274040, 0.0690970, 0.0163916,
    0.3292820, 0.9195400, 0.0880132,
    0.0433136, 0.0113616, 0.8955952
);

// ITU-R BT.2100 / ARIB STD-B67 HLG OETF transfer parameters
const float a = 0.17883277;
const float b = 0.28466892; // 1.0 - 4.0 * a
const float c = 0.55991073; // 0.5 - a * ln(4.0 * a)

float hlgOetf(float E) {
    E = max(0.0, E);
    if (E <= 1.0 / 12.0) {
        return sqrt(3.0 * E);
    } else {
        return a * log(max(1e-6, 12.0 * E - b)) + c;
    }
}

vec3 encodeHlg(vec3 rgb) {
    return vec3(hlgOetf(rgb.r), hlgOetf(rgb.g), hlgOetf(rgb.b));
}

// SMPTE ST 2084 PQ (Perceptual Quantizer) transfer function constants
const float m1 = 0.1593017578125;
const float m2 = 78.84375;
const float c1 = 0.8359375;
const float c2 = 18.8515625;
const float c3 = 18.6875;

// Maps linear BT.2020 directly and proportionally to 1,000-nit SMPTE ST.2084 PQ (zero per-channel power distortion)
vec3 encodePq(vec3 linearBt2020) {
    vec3 nits = clamp(linearBt2020 * 1000.0, 0.0, 1000.0);
    vec3 L = nits / 10000.0;
    vec3 Lm1 = pow(max(vec3(1e-6), L), vec3(m1));
    vec3 num = vec3(c1) + vec3(c2) * Lm1;
    vec3 den = vec3(1.0) + vec3(c3) * Lm1;
    return pow(num / den, vec3(m2));
}


// LUT 1: Rec.709 Pro (broadcast saturation & contrast)
vec3 applyRec709Pro(vec3 color) {
    vec3 c = pow(max(color, vec3(0.0)), vec3(0.95));
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, 1.15);
    return clamp(c, 0.0, 1.0);
}

// LUT 2: Kodak 2383 Film Print (warm highlights, deep blacks, subtractive film dye response)
vec3 applyKodak2383(vec3 color) {
    vec3 c = color;
    c = c * c * (3.0 - 2.0 * c);
    c.r = pow(max(0.0, c.r), 0.92);
    c.g = pow(max(0.0, c.g), 0.98);
    c.b = pow(max(0.0, c.b), 1.08);
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, 1.25);
    return clamp(c, 0.0, 1.0);
}

// LUT 3: Cinema Teal & Orange (blockbuster skin tone isolation)
vec3 applyCinemaTeal(vec3 color) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec3 teal = vec3(0.0, 0.12, 0.22) * (1.0 - luma);
    vec3 orange = vec3(0.22, 0.10, 0.0) * luma;
    vec3 c = clamp(color + orange - teal, 0.0, 1.0);
    c = c * c * (3.0 - 2.0 * c);
    return clamp(c, 0.0, 1.0);
}

// Arri-Style False Color Luminance Mapping
vec3 applyFalseColor(vec3 color) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float ire = luma * 100.0;
    if (ire >= 98.0) return vec3(1.0, 0.0, 0.0); // Red: 98%+ clipping
    if (ire >= 80.0) return vec3(1.0, 0.85, 0.0); // Yellow: 80-97% highlights
    if (ire >= 52.0 && ire <= 58.0) return vec3(1.0, 0.4, 0.75); // Pink: 52-58% skin reference
    if (ire >= 38.0 && ire <= 44.0) return vec3(0.0, 0.85, 0.2); // Green: 38-44% 18% gray card
    if (ire >= 4.0 && ire < 12.0) return vec3(0.0, 0.4, 0.95); // Blue: 4-12% shadows
    if (ire < 4.0) return vec3(0.5, 0.0, 0.8); // Purple: <4% crushed blacks
    return vec3(luma * 0.45); // Neutral reference gray
}

// Static 10-bit Triangular Interleaved Gradient Noise (IGN) anti-banding spatial dither
// Stationary spatial dither (zero temporal seed variation) eliminates 8-to-10-bit contour bands
// while preventing temporal noise grain that stresses the HEVC encoder.
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Fast 2-tap diagonal jittered deband filter (smooths sky bands on demand with only 2 extra samples)
vec3 applyDebandFast(samplerExternalOES tex, vec2 uv, vec2 texelSize, vec3 center, vec2 fragCoord) {
    if (texelSize.x <= 0.0 || texelSize.y <= 0.0) {
        return center;
    }
    float rot = ign(fragCoord) * 6.2831853;
    vec2 dir = vec2(cos(rot), sin(rot));
    vec2 offset = dir * (6.0 * texelSize);

    vec3 s1 = texture2D(tex, uv + offset).rgb;
    vec3 s2 = texture2D(tex, uv - offset).rgb;

    vec3 diff1 = abs(center - s1);
    vec3 diff2 = abs(center - s2);
    float maxDiff1 = max(max(diff1.r, diff1.g), diff1.b);
    float maxDiff2 = max(max(diff2.r, diff2.g), diff2.b);

    vec3 accum = center;
    float totalWeight = 1.0;

    if (maxDiff1 < 0.015) {
        float w1 = 1.0 - (maxDiff1 / 0.015);
        accum += s1 * w1;
        totalWeight += w1;
    }
    if (maxDiff2 < 0.015) {
        float w2 = 1.0 - (maxDiff2 / 0.015);
        accum += s2 * w2;
        totalWeight += w2;
    }

    return accum / totalWeight;
}

// Static triangular spatial dither: amplitude = 0.25 / 255.0 right before HLG/PQ curve
vec3 applyTriangularIgnDither(vec3 color, vec2 fragCoord) {
    float n1 = ign(fragCoord);
    float n2 = ign(fragCoord + vec2(1.7, 3.1));
    float tri = (n1 + n2 - 1.0); // Triangular PDF centered at 0.0 in [-1.0, 1.0]
    float dither = tri * (0.25 / 255.0);
    return max(vec3(0.0), color + vec3(dither));
}

// Smooth cubic highlight shoulder roll-off above 0.82 luminance
// Eliminates abrupt highlight knee transitions and sky color shifting before HLG/PQ OETF
vec3 applyHighlightShoulder(vec3 color) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (luma > 0.82) {
        float t = clamp((luma - 0.82) / 0.18, 0.0, 1.0);
        // Smooth cubic Hermite curve with continuous 1st derivative at 0.82
        float rolledLuma = 0.82 + 0.18 * (t - (t * t * t) * 0.3333333) * 1.5;
        float scale = rolledLuma / max(1e-5, luma);
        return color * scale;
    }
    return color;
}

void main() {
    vec4 src = texture2D(sTexture, vTextureCoord);
    vec3 color = src.rgb;

    if (uDeband == 1) {
        color = applyDebandFast(sTexture, vTextureCoord, uTexelSize, color, gl_FragCoord.xy);
    }

    // 3D LUT Simulation pass
    if (uLutPreset == 1) {
        color = applyRec709Pro(color);
    } else if (uLutPreset == 2) {
        color = applyKodak2383(color);
    } else if (uLutPreset == 3) {
        color = applyCinemaTeal(color);
    }

    if (uFalseColor == 1) {
        gl_FragColor = vec4(applyFalseColor(color), 1.0);
        return;
    }

    if (uColorMode == 2) {
        // HLG / BT.2020 Mode: +1.0 EV (2.0x) linear midtone recovery with scalar luminance roll-off (zero hue shift)
        vec3 linearRgb = pow(max(vec3(0.0), color), vec3(2.2)) * uExposureTrim * 2.0;
        float luma = max(dot(linearRgb, vec3(0.2126, 0.7152, 0.0722)), 1e-5);
        float compressedLuma = (luma / (1.0 + luma * 0.35)) * 1.35;
        linearRgb *= (compressedLuma / luma);
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        vec3 dithered = applyTriangularIgnDither(rec2020, gl_FragCoord.xy);
        vec3 hlgRgb = clamp(encodeHlg(dithered), 0.0, 1.0);
        gl_FragColor = vec4(hlgRgb, 1.0);
    } else if (uColorMode == 3) {
        // HDR10+ (SMPTE ST.2084 PQ): +1.0 EV (2.0x) linear midtone recovery with scalar luminance roll-off (zero hue shift)
        vec3 linearRgb = pow(max(vec3(0.0), color), vec3(2.2)) * uExposureTrim * 2.0;
        float luma = max(dot(linearRgb, vec3(0.2126, 0.7152, 0.0722)), 1e-5);
        float compressedLuma = (luma / (1.0 + luma * 0.35)) * 1.35;
        linearRgb *= (compressedLuma / luma);
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        vec3 dithered = applyTriangularIgnDither(rec2020, gl_FragCoord.xy);
        vec3 pqRgb = clamp(encodePq(dithered), 0.0, 1.0);
        gl_FragColor = vec4(pqRgb, 1.0);
    } else if (uColorMode == 1) {
        // PIXEL-LOG (uColorMode == 1): +1.0 EV (2.0x) midtone recovery with scalar luminance roll-off (zero hue shift)
        vec3 linearRgb = color * uExposureTrim * 2.0;
        float luma = max(dot(linearRgb, vec3(0.2126, 0.7152, 0.0722)), 1e-5);
        float compressedLuma = (luma / (1.0 + luma * 0.35)) * 1.35;
        linearRgb *= (compressedLuma / luma);
        vec3 dithered = clamp(applyTriangularIgnDither(linearRgb, gl_FragCoord.xy), 0.0, 1.0);
        gl_FragColor = vec4(dithered, 1.0);
    } else {
        // REC.709 (uColorMode == 0): clean unmodified Rec.709 output for neutral white highlights
        vec3 trimmed = color * uExposureTrim;
        vec3 dithered = clamp(applyTriangularIgnDither(trimmed, gl_FragCoord.xy), 0.0, 1.0);
        gl_FragColor = vec4(dithered, 1.0);
    }
}
"""
    }

    // GL Threading & ADPF PerformanceHintManager
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var performanceHintSession: PerformanceHintManager.Session? = null
    @Volatile
    private var adpfTargetWorkDurationNs: Long = 16_666_666L

    // EGL State
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglConfig8Bit: EGLConfig? = null
    private var eglConfig10Bit: EGLConfig? = null
    private var supportsColorspace: Boolean = false
    private var supportsHlgColorspace: Boolean = false
    private var supportsPqColorspace: Boolean = false
    private var activeColorProfile: ColorProfile = ColorProfile.REC709
    private var isCurrentPreviewHdr: Boolean = false
    private var lastPreviewProfile: ColorProfile = ColorProfile.REC709
    private var currentEglColorspace: Int = -1
    private var dummyPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Window Surfaces
    private var previewEglSurface: EGLSurface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var currentSurface: Surface? = null

    // Camera2 OES Input SurfaceTexture & Surface
    private var oesTextureId: Int = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    var inputSurface: Surface? = null
        private set

    // Shaders & Geometry Buffers
    private var isGles3: Boolean = false
    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTextureCoordLoc: Int = -1
    private var uSTMatrixLoc: Int = -1
    private var uAspectMatrixLoc: Int = -1
    private var uTexelSizeLoc: Int = -1
    private var uColorModeLoc: Int = -1
    private var uFalseColorLoc: Int = -1
    private var uLutPresetLoc: Int = -1
    private var uDebandLoc: Int = -1
    private var uExposureTrimLoc: Int = -1

    // Single-Pass 10-bit GL_RGB10_A2 FBO & Pass-Through Blit Shader
    private var blitProgramId: Int = 0
    private var blitAPositionLoc: Int = -1
    private var blitATextureCoordLoc: Int = -1
    private var blitUTexMatrixLoc: Int = -1
    private var blitSFboTextureLoc: Int = -1
    private var fboId: Int = 0
    private var fboTexId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    private val texMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // Stream and Surface Dimensions (always landscape width >= height)
    @Volatile
    var streamWidth: Int = 3840
        private set

    @Volatile
    var streamHeight: Int = 2160
        private set

    private var previewWidth = 1920
    private var previewHeight = 1080

    private var encoderWidth = 3840
    private var encoderHeight = 2160

    private var hasLoggedFirstFrame = false

    // Zero-allocation reusable query & invalidate buffers
    private val queryWidth = IntArray(1)
    private val queryHeight = IntArray(1)
    private val invalidateFboAttachments = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
    private val invalidateDefaultAttachments = intArrayOf(GLES30.GL_DEPTH, GLES30.GL_STENCIL)

    // Quantized MP4 presentation timestamps & frame pacing for video encoder
    @Volatile
    var targetFps: Int = 60
        private set
    @Volatile private var recordingStartNs: Long = -1L
    @Volatile private var recordedFrameIndex: Long = 0L
    @Volatile private var lastInputFrameTimeNs: Long = -1L
    var onFirstEncoderFrame: ((Long) -> Unit)? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var currentColorMode: Int = 0
        private set

    @Volatile
    var displayRotation: Int = Surface.ROTATION_90
        private set

    @Volatile
    var isFrontCamera: Boolean = false
        private set

    @Volatile
    var sensorOrientation: Int = 90
        private set

    @Volatile
    var isFalseColorEnabled: Boolean = false
        private set

    @Volatile
    var currentLutPreset: Int = 0 // 0 = OFF, 1 = REC709_NATURAL, 2 = KODAK_2383, 3 = CINEMA_TEAL
        private set

    @Volatile
    var isLutBakeEnabled: Boolean = false
        private set

    @Volatile
    var anamorphicScale: Float = 1.0f
        private set

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun setFrontCamera(isFront: Boolean) {
        isFrontCamera = isFront
        hasLoggedFirstFrame = false
    }

    fun setCameraOrientation(isFront: Boolean, sensorOrientation: Int) {
        this.isFrontCamera = isFront
        this.sensorOrientation = sensorOrientation
        this.hasLoggedFirstFrame = false
    }

    fun setFalseColor(enabled: Boolean) {
        isFalseColorEnabled = enabled
    }

    fun setLutPreset(preset: Int) {
        currentLutPreset = preset
    }

    fun setLutBakeEnabled(enabled: Boolean) {
        isLutBakeEnabled = enabled
    }

    fun setAnamorphicScale(scale: Float) {
        anamorphicScale = scale
    }

    @Volatile
    var isDebandEnabled: Boolean = false
        private set

    fun setDeband(enabled: Boolean) {
        isDebandEnabled = enabled
    }

    val timestampIsoMap = TimestampIsoMap()

    @Volatile
    var isAutoIsoEnabled: Boolean = false

    @Volatile
    var minSensorIso: Float = 50f

    @Volatile
    var maxSensorIso: Float = 3200f

    @Volatile
    var desiredContinuousIso: Float = 100f
        private set

    @Volatile
    private var isAutoIsoGliding: Boolean = false
    @Volatile
    private var autoIsoGlideStartNs: Long = 0L
    @Volatile
    private var autoIsoStartIso: Float = 100f
    @Volatile
    private var autoIsoTargetIso: Float = 100f
    private val AUTO_ISO_GLIDE_DURATION_NS: Long = 1_500_000_000L // 1.5 seconds

    private var lastKnownHardwareIso: Int = 100

    @Volatile
    var exposureTrim: Float = 1.0f
        private set

    fun setExposureTrim(trim: Float) {
        exposureTrim = trim.coerceIn(0.70f, 1.45f)
    }

    fun setManualIso(iso: Float) {
        isAutoIsoGliding = false
        desiredContinuousIso = iso
        exposureTrim = 1.0f
    }

    fun startAutoIsoGlide(targetIso: Float, minIso: Float, maxIso: Float) {
        minSensorIso = minIso
        maxSensorIso = maxIso
        val clampedTarget = targetIso.coerceIn(minIso, maxIso)
        if (Math.abs(clampedTarget - desiredContinuousIso) >= 2.0f) {
            autoIsoStartIso = desiredContinuousIso
            autoIsoTargetIso = clampedTarget
            autoIsoGlideStartNs = System.nanoTime()
            isAutoIsoGliding = true
        }
    }

    // Real-time downsampled histogram & luma telemetry extraction (10 fps, 16x16 thumbnail ~0.02ms)
    // Paused automatically while recording to prevent GPU glReadPixels pipeline stalls
    @Volatile
    var isRecordingActive: Boolean = false
        private set

    fun setRecordingActive(active: Boolean) {
        isRecordingActive = active
    }

    private var lastHistogramSampleTimeMs: Long = 0L
    private val histogramPixelBuffer = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
    var onHistogramExtracted: ((HistogramData) -> Unit)? = null

    private val previewMatrix = FloatArray(16)
    private val encoderMatrix = FloatArray(16)
    private val previewAspectMatrix = FloatArray(16)
    private val encoderAspectMatrix = FloatArray(16)
    private val fboAspectMatrix = FloatArray(16)
    private val blitRotMatrix = FloatArray(16)
    private val blitFinalMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private fun computeBlitPreviewMatrix(
        fboW: Int,
        fboH: Int,
        outW: Int,
        outH: Int,
        rotation: Int,
        isFront: Boolean,
        anamorphic: Float
    ): FloatArray {
        val aspect = computeAspectMatrix(previewAspectMatrix, fboW, fboH, outW, outH, rotation, anamorphic)
        val relRotDeg = when (rotation) {
            Surface.ROTATION_0 -> 90f
            Surface.ROTATION_90 -> 0f
            Surface.ROTATION_180 -> -90f
            Surface.ROTATION_270 -> 180f
            else -> 90f
        }
        Matrix.setIdentityM(blitRotMatrix, 0)
        if (relRotDeg != 0f) {
            Matrix.translateM(blitRotMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.rotateM(blitRotMatrix, 0, relRotDeg, 0.0f, 0.0f, 1.0f)
            Matrix.translateM(blitRotMatrix, 0, -0.5f, -0.5f, 0.0f)
        }
        if (isFront) {
            Matrix.translateM(blitRotMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.scaleM(blitRotMatrix, 0, -1.0f, 1.0f, 1.0f)
            Matrix.translateM(blitRotMatrix, 0, -0.5f, -0.5f, 0.0f)
        }
        Matrix.multiplyMM(blitFinalMatrix, 0, blitRotMatrix, 0, aspect, 0)
        return blitFinalMatrix
    }

    private fun computePreviewMatrix(stMatrix: FloatArray, rotation: Int, isFront: Boolean): FloatArray {
        System.arraycopy(stMatrix, 0, previewMatrix, 0, 16)
        // OutputConfiguration.MIRROR_MODE_NONE ensures SurfaceTexture.getTransformMatrix(stMatrix)
        // aligns both Rear (90 deg) and Front (270 deg) sensors upright in ROTATION_0 portrait
        // without baked-in HAL mirroring. Screen-relative rotation is identical for both sensors.
        val rotationDeg = when (rotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_270 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> 0f // ROTATION_0 (portrait)
        }
        if (rotationDeg != 0f) {
            Matrix.translateM(previewMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.rotateM(previewMatrix, 0, rotationDeg, 0.0f, 0.0f, 1.0f)
            Matrix.translateM(previewMatrix, 0, -0.5f, -0.5f, 0.0f)
        }

        if (isFront) {
            // Horizontal mirror preview in screen space for natural selfie viewfinder across all rotations
            Matrix.translateM(previewMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.scaleM(previewMatrix, 0, -1.0f, 1.0f, 1.0f)
            Matrix.translateM(previewMatrix, 0, -0.5f, -0.5f, 0.0f)
        }

        return previewMatrix
    }

    private fun computeLandscapeEncoderMatrix(stMatrix: FloatArray): FloatArray {
        System.arraycopy(stMatrix, 0, encoderMatrix, 0, 16)
        // Native ROTATION_90 landscape coordinates (un-mirrored) so MediaCodec always encodes
        // hardware-aligned upright, true-to-life landscape frames for both Rear and Front cameras.
        val rotationDeg = -90f
        Matrix.translateM(encoderMatrix, 0, 0.5f, 0.5f, 0.0f)
        Matrix.rotateM(encoderMatrix, 0, rotationDeg, 0.0f, 0.0f, 1.0f)
        Matrix.translateM(encoderMatrix, 0, -0.5f, -0.5f, 0.0f)
        return encoderMatrix
    }

    /**
     * Computes the 1:1 square-pixel aspect ratio center-crop matrix (uAspectMatrix).
     * Compares incomingBufferAspect against targetOutputAspect without stretching horizontally or squeezing vertically.
     * Optionally applies anamorphic de-squeeze factor to expand compressed widescreen visuals.
     * Accounts for display/device rotation so vertical 9:16 and 3:4 portrait viewports receive 1:1 square pixels.
     */
    private fun computeAspectMatrix(
        result: FloatArray,
        streamW: Int,
        streamH: Int,
        outW: Int,
        outH: Int,
        rotation: Int = Surface.ROTATION_90,
        anamorphic: Float = 1.0f
    ): FloatArray {
        Matrix.setIdentityM(result, 0)
        if (streamW <= 0 || streamH <= 0 || outW <= 0 || outH <= 0) {
            return result
        }

        val isRotated = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        val incomingBufferAspect = if (isRotated) {
            streamH.toFloat() / streamW.toFloat()
        } else {
            streamW.toFloat() / streamH.toFloat()
        }
        val targetOutputAspect = outW.toFloat() / outH.toFloat()

        var scaleU = 1.0f
        var scaleV = 1.0f

        if (incomingBufferAspect > targetOutputAspect) {
            scaleU = targetOutputAspect / incomingBufferAspect
        } else if (incomingBufferAspect < targetOutputAspect) {
            scaleV = incomingBufferAspect / targetOutputAspect
        }

        if (anamorphic > 1.01f) {
            scaleU *= (1.0f / anamorphic)
        }

        Matrix.translateM(result, 0, 0.5f, 0.5f, 0.0f)
        Matrix.scaleM(result, 0, scaleU, scaleV, 1.0f)
        Matrix.translateM(result, 0, -0.5f, -0.5f, 0.0f)

        return result
    }

    init {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(VERTICES)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(TEX_COORDS)
                position(0)
            }
    }

    private fun ensureGlThreadStarted() {
        if (glThread == null) {
            glThread = HandlerThread("PixelCineHlgGl", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
                glHandler = Handler(looper)
            }
            glHandler?.post {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set THREAD_PRIORITY_URGENT_DISPLAY on GL thread", e)
                }
                try {
                    val targetNs = (1_000_000_000L / maxOf(1, targetFps)).coerceAtLeast(1_000_000L)
                    adpfTargetWorkDurationNs = targetNs
                    val mgr = context?.getSystemService(PerformanceHintManager::class.java)
                    if (mgr != null && performanceHintSession == null) {
                        performanceHintSession = mgr.createHintSession(intArrayOf(Process.myTid()), targetNs)
                        Log.i(TAG, "ADPF PerformanceHintManager session created on PixelCineHlgGl (targetWorkDuration=${targetNs / 1_000_000.0}ms)")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "ADPF PerformanceHintManager unavailable on device", e)
                }
            }
        }
    }

    fun setTargetFps(fps: Int) {
        val clampedFps = maxOf(1, fps)
        this.targetFps = clampedFps
        val targetNs = (1_000_000_000L / clampedFps).coerceAtLeast(1_000_000L)
        adpfTargetWorkDurationNs = targetNs
        glHandler?.post {
            try {
                performanceHintSession?.updateTargetWorkDuration(targetNs)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to update ADPF targetWorkDuration", e)
            }
        }
    }

    /**
     * Lazily initializes standard 8-bit RGBA_8888 EGL context, binds active preview surface,
     * and creates the Camera2 OES input surface.
     */
    fun initialize(
        previewSurface: Surface? = null,
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        onReady: () -> Unit = {}
    ) {
        val sW = maxOf(streamWidth, streamHeight)
        val sH = minOf(streamWidth, streamHeight)
        val vW = maxOf(viewWidth, viewHeight)
        val vH = minOf(viewWidth, viewHeight)
        this.streamWidth = sW
        this.streamHeight = sH
        this.previewWidth = vW
        this.previewHeight = vH
        ensureGlThreadStarted()
        val handler = glHandler ?: return

        handler.post {
            try {
                if (!isReady || (eglContext == EGL14.EGL_NO_CONTEXT)) {
                    initEGL()
                    initShaders()
                    initOesTexture(sW, sH)
                    isReady = true
                    Log.i(TAG, "HlgGlRenderer initialized successfully (${sW}x$sH, standard 8-bit RGBA8888)")
                }

                if (previewSurface != null && previewSurface.isValid) {
                    destroyPreviewSurface()
                    currentSurface = previewSurface
                    val eglSurf = createPreviewWindowSurface(previewSurface, activeColorProfile)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Preview surface initialized (${vW}x$vH, stream=${sW}x$sH, HDR=$isCurrentPreviewHdr)")
                    }
                }

                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize HlgGlRenderer", e)
                isReady = false
            }
        }
    }

    fun getCameraInputSurface(): Surface? = inputSurface

    /**
     * Updates the active color mode (0 = REC.709, 1 = PIXEL-LOG, 2 = HLG, 3 = HDR10+) instantly
     * in the fragment shader and seamlessly transitions EGL window surface between SDR and HDR!
     */
    fun setColorMode(profile: ColorProfile) {
        activeColorProfile = profile
        val mode = when (profile) {
            ColorProfile.REC709 -> 0
            ColorProfile.FLAT_LOG -> 1
            ColorProfile.HLG_10BIT -> 2
            ColorProfile.HDR10_PLUS -> 3
        }
        currentColorMode = mode
        val handler = glHandler ?: return
        handler.post {
            val targetIsHdr = profile == ColorProfile.HLG_10BIT || profile == ColorProfile.HDR10_PLUS

            // Recreate preview EGL surface ONLY when crossing SDR <-> HDR boundary (8-bit vs 10-bit/colorspace);
            // toggling REC709 <-> PIXEL-LOG or HLG10 <-> HDR10+ updates uColorMode instantaneously with zero black flash.
            if (previewEglSurface == null || previewEglSurface == EGL14.EGL_NO_SURFACE || isCurrentPreviewHdr != targetIsHdr) {
                val surf = currentSurface
                if (surf != null && surf.isValid) {
                    destroyPreviewSurface()
                    val eglSurf = createPreviewWindowSurface(surf, profile)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Recreated preview EGL surface for SDR/HDR boundary transition -> $profile (colorspace=0x${Integer.toHexString(currentEglColorspace)}, HDR=$isCurrentPreviewHdr)")
                    }
                }
            } else {
                lastPreviewProfile = profile
                Log.i(TAG, "Instant zero-flash color profile switch -> $profile (uColorMode=$mode)")
            }

            if (isReady && programId != 0) {
                val currentDrawSurf = previewEglSurface ?: dummyPbufferSurface
                if (currentDrawSurf != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, currentDrawSurf, currentDrawSurf, eglContext)
                }
                GLES20.glUseProgram(programId)
                GLES20.glUniform1i(uColorModeLoc, mode)
            }
        }
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        supportsColorspace = extensions.contains("EGL_KHR_gl_colorspace")
        supportsHlgColorspace = supportsColorspace && (extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg") || extensions.contains("EGL_EXT_gl_colorspace_bt2020_linear"))
        supportsPqColorspace = supportsColorspace && extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq")
        Log.i(TAG, "EGL colorspace support: KHR=$supportsColorspace, HLG=$supportsHlgColorspace, PQ=$supportsPqColorspace")

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        // 1. Try 10-bit RGBA_1010102 config (preferring EGL_RECORDABLE_ANDROID for MediaCodec fast-path)
        val configAttribs10Recordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_SAMPLE_BUFFERS, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        var found10 = EGL14.eglChooseConfig(
            eglDisplay, configAttribs10Recordable, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0 && configs[0] != null

        if (found10) {
            Log.i(TAG, "10-bit EGLConfig supports EGL_RECORDABLE_ANDROID=1")
        } else {
            val configAttribs10 = intArrayOf(
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_SAMPLE_BUFFERS, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            found10 = EGL14.eglChooseConfig(
                eglDisplay, configAttribs10, 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0 && configs[0] != null
        }

        if (!found10) {
            val configAttribs10WindowOnly = intArrayOf(
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_SAMPLE_BUFFERS, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            found10 = EGL14.eglChooseConfig(
                eglDisplay, configAttribs10WindowOnly, 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0 && configs[0] != null
        }

        if (found10) {
            eglConfig10Bit = configs[0]
            Log.i(TAG, "Selected 10-bit RGBA_1010102 EGLConfig")
        }

        // 2. Standard 8-bit RGBA_8888 config
        val configAttribs8 = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_SAMPLE_BUFFERS, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR or EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val found8 = EGL14.eglChooseConfig(
            eglDisplay, configAttribs8, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0 && configs[0] != null

        if (found8) {
            eglConfig8Bit = configs[0]
        }

        eglConfig = eglConfig10Bit ?: eglConfig8Bit ?: run {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR or EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                configs[0]
            } else {
                throw RuntimeException("Failed to find suitable EGLConfig")
            }
        }

        // Create GLES 3.0 context with fallback to GLES 2.0
        var context = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        ), 0)
        if (context != EGL14.EGL_NO_CONTEXT) {
            isGles3 = true
        } else {
            isGles3 = false
            context = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            ), 0)
        }
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create GLES EGLContext")
        }
        eglContext = context

        // Create 1x1 dummy pbuffer surface so EGLContext is ALWAYS current immediately!
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        dummyPbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (dummyPbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, dummyPbufferSurface, dummyPbufferSurface, eglContext)
        }
    }

    private fun initShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Error linking HLG shader program: $error")
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uSTMatrixLoc = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uAspectMatrixLoc = GLES20.glGetUniformLocation(programId, "uAspectMatrix")
        uColorModeLoc = GLES20.glGetUniformLocation(programId, "uColorMode")
        uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")
        uFalseColorLoc = GLES20.glGetUniformLocation(programId, "uFalseColor")
        uLutPresetLoc = GLES20.glGetUniformLocation(programId, "uLutPreset")
        uDebandLoc = GLES20.glGetUniformLocation(programId, "uDeband")
        uExposureTrimLoc = GLES20.glGetUniformLocation(programId, "uExposureTrim")

        // Compile ultra-fast pass-through blit shader for single-pass FBO distribution
        val blitVert = loadShader(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER_CODE)
        val blitFrag = loadShader(GLES20.GL_FRAGMENT_SHADER, BLIT_FRAGMENT_SHADER_CODE)
        blitProgramId = GLES20.glCreateProgram()
        GLES20.glAttachShader(blitProgramId, blitVert)
        GLES20.glAttachShader(blitProgramId, blitFrag)
        GLES20.glLinkProgram(blitProgramId)

        val blitLinkStatus = IntArray(1)
        GLES20.glGetProgramiv(blitProgramId, GLES20.GL_LINK_STATUS, blitLinkStatus, 0)
        if (blitLinkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(blitProgramId)
            GLES20.glDeleteProgram(blitProgramId)
            blitProgramId = 0
            Log.w(TAG, "Failed to link FBO blit program, will fall back to dual-pass: $error")
        } else {
            blitAPositionLoc = GLES20.glGetAttribLocation(blitProgramId, "aPosition")
            blitATextureCoordLoc = GLES20.glGetAttribLocation(blitProgramId, "aTextureCoord")
            blitUTexMatrixLoc = GLES20.glGetUniformLocation(blitProgramId, "uTexMatrix")
            blitSFboTextureLoc = GLES20.glGetUniformLocation(blitProgramId, "sFboTexture")
        }

        checkGlError("initShaders")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            val err = GLES20.glGetError()
            throw RuntimeException("glCreateShader failed for type $type (glError=0x${Integer.toHexString(err)})")
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader $type: $error\nCode:\n$shaderCode")
        }
        return shader
    }

    private fun initOesTexture(width: Int, height: Int) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Buffer size configured BEFORE creating Surface(surfaceTexture)
        // Direct 1:1 onFrameAvailable callback on dedicated PixelCineHlgGl thread (zero frame-pacing beat jitter)
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                renderFrame()
            }, glHandler)
        }

        inputSurface = Surface(surfaceTexture)
        Log.i(TAG, "Created SurfaceTexture with buffer size ${width}x$height, inputSurface.isValid=${inputSurface?.isValid}")
    }

    /**
     * Attaches or updates the viewfinder Surface safely.
     */
    fun setPreviewSurface(
        surface: Surface?,
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int,
        streamHeight: Int
    ) {
        val sW = maxOf(streamWidth, streamHeight)
        val sH = minOf(streamWidth, streamHeight)
        val vW = maxOf(viewWidth, viewHeight)
        val vH = minOf(viewWidth, viewHeight)
        this.streamWidth = sW
        this.streamHeight = sH
        this.previewWidth = vW
        this.previewHeight = vH
        surfaceTexture?.setDefaultBufferSize(sW, sH)

        val handler = glHandler ?: return
        handler.post {
            try {
                val targetIsHdr = activeColorProfile == ColorProfile.HLG_10BIT || activeColorProfile == ColorProfile.HDR10_PLUS
                if (currentSurface == surface && previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE && isCurrentPreviewHdr == targetIsHdr) {
                    return@post
                }
                destroyPreviewSurface()
                currentSurface = surface
                if (surface != null && surface.isValid && isReady) {
                    val eglSurf = createPreviewWindowSurface(surface, activeColorProfile)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Preview EGL surface set successfully (${vW}x$vH, stream=${sW}x$sH, HDR=$isCurrentPreviewHdr)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error setting preview surface", e)
            }
        }
    }

    /**
     * Attaches or detaches the HEVC MediaCodec recording input surface safely.
     */
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int, fps: Int = 60) {
        val clampedFps = maxOf(1, fps)
        this.targetFps = clampedFps
        val targetNs = (1_000_000_000L / clampedFps).coerceAtLeast(1_000_000L)
        adpfTargetWorkDurationNs = targetNs
        val handler = glHandler ?: return
        handler.post {
            try {
                try {
                    performanceHintSession?.updateTargetWorkDuration(targetNs)
                } catch (_: Throwable) {}
                destroyEncoderSurface()
                if (surface != null && surface.isValid && isReady) {
                    // Always enforce native landscape dimensions for encoder & single-pass FBO
                    encoderWidth = maxOf(width, height)
                    encoderHeight = minOf(width, height)
                    val eglSurf = createWindowSurface(surface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        encoderEglSurface = eglSurf
                        recordingStartNs = -1L
                        recordedFrameIndex = 0L
                        lastInputFrameTimeNs = -1L
                        val currentSurf = previewEglSurface ?: dummyPbufferSurface
                        if (currentSurf != EGL14.EGL_NO_SURFACE &&
                            EGL14.eglMakeCurrent(eglDisplay, currentSurf, currentSurf, eglContext)
                        ) {
                            ensureFbo(encoderWidth, encoderHeight)
                        }
                        Log.i(TAG, "Encoder EGL surface attached (${encoderWidth}x${encoderHeight}, ${targetFps}fps, fboId=$fboId)")
                    }
                } else {
                    destroyFbo()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error setting encoder surface", e)
            }
        }
    }

    private fun createPreviewWindowSurface(surface: Surface, profile: ColorProfile): EGLSurface {
        val config = eglConfig ?: run {
            Log.w(TAG, "Cannot create preview window surface: EGLConfig is null")
            return EGL14.EGL_NO_SURFACE
        }
        if (!surface.isValid) {
            Log.w(TAG, "Cannot create preview window surface: Surface is invalid")
            return EGL14.EGL_NO_SURFACE
        }

        val isHdr = profile == ColorProfile.HLG_10BIT || profile == ColorProfile.HDR10_PLUS
        val targetColorspace = when (profile) {
            ColorProfile.HLG_10BIT -> if (supportsColorspace && supportsHlgColorspace) EGL_GL_COLORSPACE_BT2020_HLG_EXT else 0
            ColorProfile.HDR10_PLUS -> if (supportsColorspace && supportsPqColorspace) EGL_GL_COLORSPACE_BT2020_PQ_EXT else 0
            else -> 0 // Standard default EGL attributes (EGL_NONE) for 1:1 WYSIWYG SDR (REC.709 & PIXEL-LOG)
        }

        val targetConfig = if (isHdr) (eglConfig10Bit ?: config) else (eglConfig8Bit ?: config)

        if (targetColorspace != 0) {
            val attribs = intArrayOf(
                EGL_GL_COLORSPACE_KHR, targetColorspace,
                EGL14.EGL_NONE
            )
            try {
                val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, targetConfig, surface, attribs, 0)
                if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                    currentEglColorspace = targetColorspace
                    isCurrentPreviewHdr = isHdr
                    lastPreviewProfile = profile
                    Log.i(TAG, "Created HDR EGL window surface (colorspace=0x${Integer.toHexString(targetColorspace)}, HDR=$isHdr, config=${if (targetConfig == eglConfig10Bit) "10-bit" else "8-bit"})")
                    return eglSurf
                }
                val err = EGL14.eglGetError()
                Log.w(TAG, "eglCreateWindowSurface with colorspace 0x${Integer.toHexString(targetColorspace)} returned NO_SURFACE: 0x${Integer.toHexString(err)}")
            } catch (e: Throwable) {
                Log.w(TAG, "Exception creating HDR EGL window surface with colorspace 0x${Integer.toHexString(targetColorspace)}", e)
            }
        }

        // Standard default EGL attributes (EGL14.EGL_NONE) for 1:1 WYSIWYG SDR (REC.709 & PIXEL-LOG) and fallback
        val defaultAttribs = intArrayOf(EGL14.EGL_NONE)
        try {
            val fallbackConfig = if (isHdr) (eglConfig10Bit ?: config) else (eglConfig8Bit ?: config)
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, fallbackConfig, surface, defaultAttribs, 0)
            if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                currentEglColorspace = 0
                isCurrentPreviewHdr = isHdr
                lastPreviewProfile = profile
                Log.i(TAG, "Created standard 1:1 WYSIWYG EGL window surface (EGL_NONE, HDR=$isHdr, config=${if (fallbackConfig == eglConfig10Bit) "10-bit" else "8-bit"})")
                return eglSurf
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Exception creating standard EGL window surface", e)
        }

        // Fallback to 8-bit config if 10-bit was used and failed
        if (config != eglConfig8Bit && eglConfig8Bit != null) {
            try {
                val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig8Bit, surface, defaultAttribs, 0)
                if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                    currentEglColorspace = 0
                    isCurrentPreviewHdr = false
                    lastPreviewProfile = profile
                    Log.i(TAG, "Created 8-bit fallback EGL window surface")
                    return eglSurf
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Exception creating 8-bit fallback EGL window surface", e)
            }
        }

        currentEglColorspace = -1
        return EGL14.EGL_NO_SURFACE
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val isHdrOr10Bit = activeColorProfile != ColorProfile.REC709
        val config = if (isHdrOr10Bit) {
            (eglConfig10Bit ?: eglConfig8Bit ?: eglConfig)
        } else {
            (eglConfig8Bit ?: eglConfig)
        } ?: run {
            Log.w(TAG, "Cannot create window surface: EGLConfig is null")
            return EGL14.EGL_NO_SURFACE
        }
        if (!surface.isValid) {
            Log.w(TAG, "Cannot create window surface: Surface is invalid")
            return EGL14.EGL_NO_SURFACE
        }

        try {
            val defaultAttribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, defaultAttribs, 0)
            if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                Log.i(TAG, "Created encoder EGL window surface (config=${if (config == eglConfig10Bit) "10-bit" else "8-bit"})")
                return eglSurf
            }
            Log.w(TAG, "Failed to create EGL window surface: 0x${Integer.toHexString(EGL14.eglGetError())}")
        } catch (e: Throwable) {
            Log.w(TAG, "Exception creating EGL window surface", e)
        }

        return EGL14.EGL_NO_SURFACE
    }

    /**
     * Executes the shader render pass when a new Camera2 frame arrives.
     * ALWAYS calls eglMakeCurrent BEFORE surfaceTexture.updateTexImage()!
     */
    private fun renderFrame() {
        val st = surfaceTexture ?: return
        val pSurf = previewEglSurface
        val eSurf = encoderEglSurface

        if (pSurf == null || pSurf == EGL14.EGL_NO_SURFACE) {
            return
        }

        val frameStartNs = System.nanoTime()
        try {
            // 1. MUST make EGL context current BEFORE calling updateTexImage()
            if (!EGL14.eglMakeCurrent(eglDisplay, pSurf, pSurf, eglContext)) {
                Log.w(TAG, "eglMakeCurrent failed in renderFrame: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return
            }

            // 2. Update hardware buffer onto GL_TEXTURE_EXTERNAL_OES texture
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val frameTs = st.timestamp

            // 2b. Frame-Locked 60fps Continuous S-Curve Auto-ISO & SENSOR_TIMESTAMP Gain Matching
            if (isAutoIsoEnabled) {
                if (isAutoIsoGliding) {
                    val elapsedNs = System.nanoTime() - autoIsoGlideStartNs
                    val progress = (elapsedNs.toDouble() / AUTO_ISO_GLIDE_DURATION_NS.toDouble()).coerceIn(0.0, 1.0).toFloat()
                    val easedT = progress * progress * (3.0f - 2.0f * progress) // 1.5s cubic S-curve
                    val stopRatio = (autoIsoTargetIso / maxOf(1.0f, autoIsoStartIso)).toDouble()
                    desiredContinuousIso = (autoIsoStartIso * Math.pow(stopRatio, easedT.toDouble()).toFloat())
                        .coerceIn(minSensorIso, maxSensorIso)

                    if (progress >= 1.0f) {
                        desiredContinuousIso = autoIsoTargetIso
                        isAutoIsoGliding = false
                    }
                }

                // Match exact hardware ISO of this specific frame buffer via SENSOR_TIMESTAMP
                val matchedIso = timestampIsoMap.get(frameTs)
                if (matchedIso != null && matchedIso > 0) {
                    lastKnownHardwareIso = matchedIso
                }
                val actualIso = lastKnownHardwareIso
                if (actualIso > 0) {
                    exposureTrim = (desiredContinuousIso / actualIso.toFloat()).coerceIn(0.70f, 1.45f)
                } else {
                    exposureTrim = 1.0f
                }
            } else {
                isAutoIsoGliding = false
                exposureTrim = 1.0f
            }

            if (!hasLoggedFirstFrame) {
                hasLoggedFirstFrame = true
                Log.d("PixelCineHDR", "GL frame rendered (mode=$currentColorMode, front=$isFrontCamera, sensorOri=$sensorOrientation, rot=$displayRotation, stMatrix=${texMatrix.joinToString(",") { "%.2f".format(it) }})")
            }

            // 3. Render pipeline: Single-pass 10-bit GL_RGB10_A2 FBO when recording & shaders match,
            // cutting fragment shading load by ~50% at 4K 60fps.
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_WIDTH, queryWidth, 0)
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_HEIGHT, queryHeight, 0)
            val w = if (queryWidth[0] > 0) queryWidth[0] else previewWidth
            val h = if (queryHeight[0] > 0) queryHeight[0] else previewHeight

            val effectivePreviewLut = if (currentColorMode == 2 || currentColorMode == 3) 0 else currentLutPreset
            val effectiveEncoderLut = if (currentColorMode == 2 || currentColorMode == 3) {
                0
            } else {
                if (isLutBakeEnabled) currentLutPreset else 0
            }
            val canUseSinglePassFbo = eSurf != null &&
                eSurf != EGL14.EGL_NO_SURFACE &&
                !isFalseColorEnabled &&
                (effectivePreviewLut == effectiveEncoderLut) &&
                blitProgramId != 0 &&
                ensureFbo(encoderWidth, encoderHeight)

            if (canUseSinglePassFbo) {
                // Pass 1: Heavy color science shader ONCE into 10-bit GL_RGB10_A2 landscape FBO
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glViewport(0, 0, fboWidth, fboHeight)
                if (isGles3) {
                    GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateFboAttachments, 0)
                }
                val fboMatrix = computeLandscapeEncoderMatrix(texMatrix)
                val fboAspect = computeAspectMatrix(
                    fboAspectMatrix, streamWidth, streamHeight, fboWidth, fboHeight, Surface.ROTATION_90, 1.0f
                )
                drawQuad(
                    matrix = fboMatrix,
                    aspectMatrix = fboAspect,
                    falseColor = 0,
                    lutPreset = effectiveEncoderLut
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // Pass 2a: Blit FBO to Viewfinder (pSurf is already current)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val pBlitMatrix = computeBlitPreviewMatrix(
                    fboWidth, fboHeight, w, h, displayRotation, isFrontCamera, anamorphicScale
                )
                drawBlitQuad(fboTexId, pBlitMatrix)
                invalidateDefaultDepthStencil()
                EGL14.eglSwapBuffers(eglDisplay, pSurf)

                // Pass 2b: Blit FBO 1:1 to landscape MediaCodec Encoder Surface (zero rotation!)
                if (EGL14.eglMakeCurrent(eglDisplay, eSurf, eSurf, eglContext)) {
                    EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_WIDTH, queryWidth, 0)
                    EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_HEIGHT, queryHeight, 0)
                    val encW = if (queryWidth[0] > 0) queryWidth[0] else encoderWidth
                    val encH = if (queryHeight[0] > 0) queryHeight[0] else encoderHeight

                    GLES20.glViewport(0, 0, encW, encH)
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawBlitQuad(fboTexId, identityMatrix)
                    invalidateDefaultDepthStencil()
                    submitEncoderFrame(eSurf)
                }
            } else {
                // Direct Viewfinder Pass (idle preview, False Color, or unbaked LUT preview)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val pMatrix = computePreviewMatrix(texMatrix, displayRotation, isFrontCamera)
                val pAspect = computeAspectMatrix(
                    previewAspectMatrix, streamWidth, streamHeight, w, h, displayRotation, anamorphicScale
                )
                drawQuad(
                    matrix = pMatrix,
                    aspectMatrix = pAspect,
                    falseColor = if (isFalseColorEnabled) 1 else 0,
                    lutPreset = effectivePreviewLut
                )
                checkGlError("drawQuad preview")

                // Pause glReadPixels histogram extraction while recording to eliminate GPU pipeline stalls
                val isRecordingNow = isRecordingActive || (eSurf != null && eSurf != EGL14.EGL_NO_SURFACE)
                if (!isRecordingNow) {
                    extractHistogram(w, h)
                }

                invalidateDefaultDepthStencil()
                EGL14.eglSwapBuffers(eglDisplay, pSurf)

                // Render to MediaCodec HEVC Encoder in native landscape (ROTATION_90) when recording
                if (eSurf != null && eSurf != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglMakeCurrent(eglDisplay, eSurf, eSurf, eglContext)) {
                        EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_WIDTH, queryWidth, 0)
                        EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_HEIGHT, queryHeight, 0)
                        val encW = if (queryWidth[0] > 0) queryWidth[0] else encoderWidth
                        val encH = if (queryHeight[0] > 0) queryHeight[0] else encoderHeight

                        GLES20.glViewport(0, 0, encW, encH)
                        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                        val eMatrix = computeLandscapeEncoderMatrix(texMatrix)
                        val eAspect = computeAspectMatrix(
                            encoderAspectMatrix, streamWidth, streamHeight, encW, encH, Surface.ROTATION_90, 1.0f
                        )
                        drawQuad(
                            matrix = eMatrix,
                            aspectMatrix = eAspect,
                            falseColor = 0,
                            lutPreset = effectiveEncoderLut
                        )
                        checkGlError("drawQuad encoder")

                        invalidateDefaultDepthStencil()
                        submitEncoderFrame(eSurf)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error rendering GL frame", e)
        } finally {
            val actualWorkNs = (System.nanoTime() - frameStartNs).coerceAtLeast(1L)
            try {
                performanceHintSession?.reportActualWorkDuration(actualWorkNs)
            } catch (_: Throwable) {}
        }
    }

    private fun extractHistogram(w: Int, h: Int) {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastHistogramSampleTimeMs < 100L || onHistogramExtracted == null || w < 16 || h < 16) {
            return
        }
        lastHistogramSampleTimeMs = nowMs
        try {
            histogramPixelBuffer.rewind()
            GLES20.glReadPixels(w / 2 - 8, h / 2 - 8, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, histogramPixelBuffer)
            histogramPixelBuffer.rewind()

            val rCounts = IntArray(32)
            val gCounts = IntArray(32)
            val bCounts = IntArray(32)
            val lCounts = IntArray(32)
            var maxCount = 1

            for (i in 0 until 256) {
                val r = histogramPixelBuffer.get().toInt() and 0xFF
                val g = histogramPixelBuffer.get().toInt() and 0xFF
                val b = histogramPixelBuffer.get().toInt() and 0xFF
                histogramPixelBuffer.get() // skip alpha
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)

                val rBin = r shr 3
                val gBin = g shr 3
                val bBin = b shr 3
                val lBin = luma shr 3

                rCounts[rBin]++
                gCounts[gBin]++
                bCounts[bBin]++
                lCounts[lBin]++

                if (rCounts[rBin] > maxCount) maxCount = rCounts[rBin]
                if (gCounts[gBin] > maxCount) maxCount = gCounts[gBin]
                if (bCounts[bBin] > maxCount) maxCount = bCounts[bBin]
                if (lCounts[lBin] > maxCount) maxCount = lCounts[lBin]
            }

            val redBins = FloatArray(32) { rCounts[it].toFloat() / maxCount }
            val greenBins = FloatArray(32) { gCounts[it].toFloat() / maxCount }
            val blueBins = FloatArray(32) { bCounts[it].toFloat() / maxCount }
            val lumaBins = FloatArray(32) { lCounts[it].toFloat() / maxCount }

            val data = HistogramData(redBins, greenBins, blueBins, lumaBins)
            onHistogramExtracted?.invoke(data)
        } catch (_: Exception) {
            // Ignore transient buffer access issues
        }
    }

    private fun drawQuad(
        matrix: FloatArray,
        aspectMatrix: FloatArray,
        falseColor: Int = 0,
        lutPreset: Int = 0
    ) {
        GLES20.glUseProgram(programId)

        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(uAspectMatrixLoc, 1, false, aspectMatrix, 0)
        GLES20.glUniform1i(uColorModeLoc, currentColorMode)
        if (uFalseColorLoc != -1) {
            GLES20.glUniform1i(uFalseColorLoc, falseColor)
        }
        if (uLutPresetLoc != -1) {
            GLES20.glUniform1i(uLutPresetLoc, lutPreset)
        }
        if (uDebandLoc != -1) {
            GLES20.glUniform1i(uDebandLoc, if (isDebandEnabled) 1 else 0)
        }
        if (uTexelSizeLoc != -1) {
            val tw = maxOf(1, streamWidth).toFloat()
            val th = maxOf(1, streamHeight).toFloat()
            GLES20.glUniform2f(uTexelSizeLoc, 1.0f / tw, 1.0f / th)
        }
        if (uExposureTrimLoc != -1) {
            GLES20.glUniform1f(uExposureTrimLoc, exposureTrim)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
    }

    private fun drawBlitQuad(texId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(blitProgramId)
        GLES20.glUniformMatrix4fv(blitUTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(blitSFboTextureLoc, 0)

        GLES20.glEnableVertexAttribArray(blitAPositionLoc)
        GLES20.glVertexAttribPointer(blitAPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(blitATextureCoordLoc)
        GLES20.glVertexAttribPointer(blitATextureCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(blitAPositionLoc)
        GLES20.glDisableVertexAttribArray(blitATextureCoordLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun submitEncoderFrame(eSurf: EGLSurface) {
        val nowNs = System.nanoTime()
        val frameDurationNs = 1_000_000_000L / maxOf(1, targetFps)

        if (recordingStartNs < 0L) {
            recordingStartNs = nowNs
            recordedFrameIndex = 0L
            lastInputFrameTimeNs = nowNs
            onFirstEncoderFrame?.invoke(recordingStartNs)
        } else {
            val deltaNs = nowNs - lastInputFrameTimeNs
            lastInputFrameTimeNs = nowNs
            if (deltaNs > 1.7 * frameDurationNs) {
                val droppedSteps = Math.round(deltaNs.toDouble() / frameDurationNs.toDouble()).toLong().coerceAtLeast(2L)
                recordedFrameIndex += droppedSteps
            } else {
                recordedFrameIndex += 1L
            }
        }

        val ptsNs = recordedFrameIndex * frameDurationNs
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eSurf, ptsNs)
        EGL14.eglSwapBuffers(eglDisplay, eSurf)
    }

    private fun invalidateDefaultDepthStencil() {
        if (isGles3) {
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 2, invalidateDefaultAttachments, 0)
        }
    }

    private fun ensureFbo(width: Int, height: Int): Boolean {
        if (!isGles3 || width <= 0 || height <= 0) return false
        if (fboId != 0 && fboTexId != 0 && fboWidth == width && fboHeight == height) {
            return true
        }
        destroyFbo()

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val tex = texIds[0]
        if (tex == 0) return false

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB10_A2,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES30.GL_UNSIGNED_INT_2_10_10_10_REV,
            null
        )

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        val fbo = fboIds[0]
        if (fbo == 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDeleteTextures(1, texIds, 0)
            return false
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            tex,
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "GL_RGB10_A2 FBO incomplete: status=0x${Integer.toHexString(status)}")
            GLES20.glDeleteFramebuffers(1, fboIds, 0)
            GLES20.glDeleteTextures(1, texIds, 0)
            return false
        }

        fboId = fbo
        fboTexId = tex
        fboWidth = width
        fboHeight = height
        Log.i(TAG, "Created 10-bit GL_RGB10_A2 FBO (${width}x${height})")
        return true
    }

    private fun destroyFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            fboTexId = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun destroyPreviewSurface() {
        previewEglSurface?.let {
            if (it != EGL14.EGL_NO_SURFACE) {
                try {
                    if (dummyPbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, dummyPbufferSurface, dummyPbufferSurface, eglContext)
                    } else {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    }
                    EGL14.eglDestroySurface(eglDisplay, it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error destroying preview EGLSurface", e)
                }
            }
            previewEglSurface = null
        }
        currentEglColorspace = -1
    }

    private fun destroyEncoderSurface() {
        encoderEglSurface?.let {
            if (it != EGL14.EGL_NO_SURFACE) {
                try {
                    if (dummyPbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, dummyPbufferSurface, dummyPbufferSurface, eglContext)
                    } else {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    }
                    EGL14.eglDestroySurface(eglDisplay, it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error destroying encoder EGLSurface", e)
                }
            }
            encoderEglSurface = null
        }
        recordingStartNs = -1L
        recordedFrameIndex = 0L
        lastInputFrameTimeNs = -1L
    }

    fun release() {
        hasLoggedFirstFrame = false
        val handler = glHandler
        if (handler != null) {
            handler.post {
                try {
                    performanceHintSession?.close()
                } catch (_: Throwable) {}
                performanceHintSession = null
                destroyPreviewSurface()
                destroyEncoderSurface()
                destroyFbo()

                if (dummyPbufferSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        EGL14.eglDestroySurface(eglDisplay, dummyPbufferSurface)
                    } catch (_: Throwable) {}
                    dummyPbufferSurface = EGL14.EGL_NO_SURFACE
                }

                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }

                if (blitProgramId != 0) {
                    GLES20.glDeleteProgram(blitProgramId)
                    blitProgramId = 0
                }

                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }

                surfaceTexture?.release()
                surfaceTexture = null

                inputSurface?.release()
                inputSurface = null

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL14.EGL_NO_CONTEXT
                    }
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }

                isReady = false
            }
        }
    }
}
