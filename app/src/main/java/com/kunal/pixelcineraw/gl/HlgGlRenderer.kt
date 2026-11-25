package com.kunal.pixelcineraw.gl

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
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

class HlgGlRenderer {

    companion object {
        private const val TAG = "HlgGlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        private const val EGL_GL_COLORSPACE_SRGB_KHR = 0x3089

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

// Maps linear BT.2020 (with diffuse white at 0.5) to calibrated 1,000-nit HDR luminance before PQ encoding
float pqOetf(float linearBt2020) {
    float nits = mix(
        linearBt2020 * 400.0,
        200.0 + pow(max(0.0, (linearBt2020 - 0.5) * 2.0), 1.4) * 800.0,
        step(0.5, linearBt2020)
    );
    float L = clamp(nits / 10000.0, 0.0, 1.0);
    float Lm1 = pow(L, m1);
    float num = c1 + c2 * Lm1;
    float den = 1.0 + c3 * Lm1;
    return pow(num / den, m2);
}

vec3 encodePq(vec3 rgb) {
    return vec3(pqOetf(rgb.r), pqOetf(rgb.g), pqOetf(rgb.b));
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
        // HLG / BT.2020 Mode: +1.0 EV (2.0x) linear midtone recovery with smooth highlight roll-off
        vec3 linearRgb = pow(max(vec3(0.0), color), vec3(2.2)) * uExposureTrim;
        vec3 x = linearRgb * 2.0;
        linearRgb = (x / (vec3(1.0) + x * 0.35)) * 1.35;
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        vec3 dithered = applyTriangularIgnDither(rec2020, gl_FragCoord.xy);
        vec3 hlgRgb = clamp(encodeHlg(dithered), 0.0, 1.0);
        gl_FragColor = vec4(hlgRgb, 1.0);
    } else if (uColorMode == 3) {
        // HDR10+ (SMPTE ST.2084 PQ): +1.0 EV (2.0x) linear midtone recovery with smooth highlight roll-off
        vec3 linearRgb = pow(max(vec3(0.0), color), vec3(2.2)) * uExposureTrim;
        vec3 x = linearRgb * 2.0;
        linearRgb = (x / (vec3(1.0) + x * 0.35)) * 1.35;
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        vec3 dithered = applyTriangularIgnDither(rec2020, gl_FragCoord.xy);
        vec3 pqRgb = clamp(encodePq(dithered), 0.0, 1.0);
        gl_FragColor = vec4(pqRgb, 1.0);
    } else if (uColorMode == 1) {
        // PIXEL-LOG (uColorMode == 1): +1.0 EV (2.0x) midtone recovery with smooth highlight roll-off
        vec3 trimmed = color * uExposureTrim;
        vec3 x = trimmed * 2.0;
        vec3 recovered = (x / (vec3(1.0) + x * 0.35)) * 1.35;
        vec3 dithered = clamp(applyTriangularIgnDither(recovered, gl_FragCoord.xy), 0.0, 1.0);
        gl_FragColor = vec4(dithered, 1.0);
    } else {
        // REC.709 (uColorMode == 0): standard 0.0 EV compensation, 1.0x output, and smooth highlight shoulder
        color = applyHighlightShoulder(color);
        vec3 trimmed = color * uExposureTrim;
        vec3 dithered = clamp(applyTriangularIgnDither(trimmed, gl_FragCoord.xy), 0.0, 1.0);
        gl_FragColor = vec4(dithered, 1.0);
    }
}
"""
    }

    // GL Threading
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

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

    // Zero-allocation reusable query buffers for eglQuerySurface
    private val queryWidth = IntArray(1)
    private val queryHeight = IntArray(1)

    // Quantized MP4 presentation timestamps & frame pacing for video encoder
    @Volatile var targetFps: Int = 60
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
    var isFalseColorEnabled: Boolean = false
        private set

    @Volatile
    var currentLutPreset: Int = 1 // 0 = OFF, 1 = REC709_NATURAL, 2 = KODAK_2383, 3 = CINEMA_TEAL
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
    private var lastHistogramSampleTimeMs: Long = 0L
    private val histogramPixelBuffer = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
    var onHistogramExtracted: ((HistogramData) -> Unit)? = null

    private val previewMatrix = FloatArray(16)
    private val encoderMatrix = FloatArray(16)
    private val previewAspectMatrix = FloatArray(16)
    private val encoderAspectMatrix = FloatArray(16)

    private fun computePreviewMatrix(stMatrix: FloatArray, rotation: Int, isFront: Boolean): FloatArray {
        System.arraycopy(stMatrix, 0, previewMatrix, 0, 16)
        val rotationDeg = if (isFront) {
            when (rotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_270 -> -90f
                Surface.ROTATION_180 -> 0f
                else -> 180f // ROTATION_0 (portrait)
            }
        } else {
            when (rotation) {
                Surface.ROTATION_90 -> -90f
                Surface.ROTATION_270 -> 90f
                Surface.ROTATION_180 -> 180f
                else -> 0f // ROTATION_0 (portrait)
            }
        }
        if (rotationDeg != 0f) {
            Matrix.translateM(previewMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.rotateM(previewMatrix, 0, rotationDeg, 0.0f, 0.0f, 1.0f)
            Matrix.translateM(previewMatrix, 0, -0.5f, -0.5f, 0.0f)
        }

        if (isFront) {
            // Horizontal mirror preview for selfie/front camera viewfinder
            Matrix.translateM(previewMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.scaleM(previewMatrix, 0, -1.0f, 1.0f, 1.0f)
            Matrix.translateM(previewMatrix, 0, -0.5f, -0.5f, 0.0f)
        }

        return previewMatrix
    }

    private fun computeEncoderMatrix(stMatrix: FloatArray, rotation: Int, isFront: Boolean): FloatArray {
        System.arraycopy(stMatrix, 0, encoderMatrix, 0, 16)
        val rotationDeg = if (isFront) {
            when (rotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_270 -> -90f
                Surface.ROTATION_180 -> 0f
                else -> 180f
            }
        } else {
            when (rotation) {
                Surface.ROTATION_90 -> -90f
                Surface.ROTATION_270 -> 90f
                Surface.ROTATION_180 -> 180f
                else -> 0f
            }
        }
        if (rotationDeg != 0f) {
            Matrix.translateM(encoderMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.rotateM(encoderMatrix, 0, rotationDeg, 0.0f, 0.0f, 1.0f)
            Matrix.translateM(encoderMatrix, 0, -0.5f, -0.5f, 0.0f)
        }
        // Recorded MP4 stays un-mirrored
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
            // Recreate preview window surface when transitioning between SDR and HDR (or between HLG and PQ)
            val isHdrTarget = profile == ColorProfile.HLG_10BIT || profile == ColorProfile.HDR10_PLUS
            if (isCurrentPreviewHdr != isHdrTarget || (isHdrTarget && lastPreviewProfile != profile)) {
                val surf = currentSurface
                if (surf != null && surf.isValid) {
                    destroyPreviewSurface()
                    val eglSurf = createPreviewWindowSurface(surf, profile)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Recreated preview EGL surface for profile $profile (HDR=$isHdrTarget)")
                    }
                }
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

        // 1. Try 10-bit RGBA_1010102 config for true HDR OLED display
        val configAttribs10 = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        var found10 = EGL14.eglChooseConfig(
            eglDisplay, configAttribs10, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0 && configs[0] != null

        if (!found10) {
            val configAttribs10WindowOnly = intArrayOf(
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
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
            Log.i(TAG, "Selected 10-bit RGBA_1010102 EGLConfig for Actua OLED HDR preview")
        }

        // 2. Standard 8-bit RGBA_8888 config
        val configAttribs8 = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
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
        if (context == EGL14.EGL_NO_CONTEXT) {
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
                if (currentSurface == surface && previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    return@post
                }
                destroyPreviewSurface()
                if (surface != null && surface.isValid && isReady) {
                    currentSurface = surface
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
        this.targetFps = fps
        val handler = glHandler ?: return
        handler.post {
            try {
                destroyEncoderSurface()
                if (surface != null && surface.isValid && isReady) {
                    encoderWidth = width
                    encoderHeight = height
                    val eglSurf = createWindowSurface(surface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        encoderEglSurface = eglSurf
                        recordingStartNs = -1L
                        recordedFrameIndex = 0L
                        lastInputFrameTimeNs = -1L
                        Log.i(TAG, "Encoder EGL surface attached (${encoderWidth}x${encoderHeight}, ${targetFps}fps)")
                    }
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

        // Try creating with HDR colorspace if in HDR mode and extension is available
        if (isHdr && supportsColorspace) {
            val colorspace = if (profile == ColorProfile.HDR10_PLUS && supportsPqColorspace) {
                EGL_GL_COLORSPACE_BT2020_PQ_EXT
            } else if (supportsHlgColorspace) {
                EGL_GL_COLORSPACE_BT2020_HLG_EXT
            } else {
                -1
            }

            if (colorspace != -1) {
                val hdrAttribs = intArrayOf(
                    EGL_GL_COLORSPACE_KHR, colorspace,
                    EGL14.EGL_NONE
                )
                try {
                    val targetConfig = eglConfig10Bit ?: config
                    val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, targetConfig, surface, hdrAttribs, 0)
                    if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                        Log.i(TAG, "Created True Live HDR OLED window surface (colorspace=0x${Integer.toHexString(colorspace)}, config=${if (targetConfig == eglConfig10Bit) "10-bit" else "8-bit"})")
                        isCurrentPreviewHdr = true
                        lastPreviewProfile = profile
                        return eglSurf
                    }
                    val err = EGL14.eglGetError()
                    Log.w(TAG, "eglCreateWindowSurface with HDR colorspace returned NO_SURFACE: 0x${Integer.toHexString(err)}")
                } catch (e: Throwable) {
                    Log.w(TAG, "Exception creating HDR EGL window surface", e)
                }
            }
        }

        // Standard SDR window surface
        val defaultAttribs = intArrayOf(EGL14.EGL_NONE)
        try {
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, defaultAttribs, 0)
            if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                Log.i(TAG, "Created standard EGL window surface for preview (SDR)")
                isCurrentPreviewHdr = false
                lastPreviewProfile = profile
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
                    Log.i(TAG, "Created 8-bit fallback EGL window surface")
                    isCurrentPreviewHdr = false
                    lastPreviewProfile = profile
                    return eglSurf
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Exception creating 8-bit fallback EGL window surface", e)
            }
        }

        return EGL14.EGL_NO_SURFACE
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val config = eglConfig ?: run {
            Log.w(TAG, "Cannot create window surface: EGLConfig is null")
            return EGL14.EGL_NO_SURFACE
        }
        if (!surface.isValid) {
            Log.w(TAG, "Cannot create window surface: Surface is invalid")
            return EGL14.EGL_NO_SURFACE
        }

        try {
            // Standard EGL window surface (rock-solid 8-bit RGBA8888, zero driver stalls)
            val defaultAttribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, defaultAttribs, 0)
            if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
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
                Log.d("PixelCineHDR", "GL frame rendered successfully (mode=$currentColorMode)")
            }

            // 3. Render directly to Viewfinder using full SurfaceView dimensions
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_WIDTH, queryWidth, 0)
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_HEIGHT, queryHeight, 0)
            val w = if (queryWidth[0] > 0) queryWidth[0] else previewWidth
            val h = if (queryHeight[0] > 0) queryHeight[0] else previewHeight

            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val pMatrix = computePreviewMatrix(texMatrix, displayRotation, isFrontCamera)
            val pAspect = computeAspectMatrix(previewAspectMatrix, streamWidth, streamHeight, w, h, displayRotation, anamorphicScale)
            drawQuad(
                matrix = pMatrix,
                aspectMatrix = pAspect,
                falseColor = if (isFalseColorEnabled) 1 else 0,
                lutPreset = currentLutPreset
            )
            checkGlError("drawQuad preview")

            // Real-time downsampled histogram & scene luma telemetry extraction (10 fps, 16x16 thumbnail ~0.02ms)
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastHistogramSampleTimeMs >= 100L && onHistogramExtracted != null && w >= 16 && h >= 16) {
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
                } catch (e: Exception) {
                    // Ignore transient buffer access issues
                }
            }

            // Viewfinder screen pass: call eglSwapBuffers immediately.
            // NOTE: Do NOT call eglPresentationTimeANDROID on display windowSurface - SurfaceFlinger will pace it immediately!
            EGL14.eglSwapBuffers(eglDisplay, pSurf)

            // 4. Render to MediaCodec HEVC Encoder if recording (exact upright matrix matching viewfinder)
            if (eSurf != null && eSurf != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, eSurf, eSurf, eglContext)) {
                    EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_WIDTH, queryWidth, 0)
                    EGL14.eglQuerySurface(eglDisplay, eSurf, EGL14.EGL_HEIGHT, queryHeight, 0)
                    val encW = if (queryWidth[0] > 0) queryWidth[0] else encoderWidth
                    val encH = if (queryHeight[0] > 0) queryHeight[0] else encoderHeight

                    GLES20.glViewport(0, 0, encW, encH)
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    val eMatrix = computeEncoderMatrix(texMatrix, displayRotation, isFrontCamera)
                    val eAspect = computeAspectMatrix(encoderAspectMatrix, streamWidth, streamHeight, encW, encH, displayRotation, 1.0f)
                    drawQuad(
                        matrix = eMatrix,
                        aspectMatrix = eAspect,
                        falseColor = 0,
                        lutPreset = if (isLutBakeEnabled) currentLutPreset else 0
                    )
                    checkGlError("drawQuad encoder")

                    // Exact quantized mathematical frame cadence (ptsNs = frameIndex * (1_000_000_000L / targetFps))
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
                        // Advance frameIndex by 2 if genuine sensor drop > 1.7 * frameDurationNs occurred
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error rendering GL frame", e)
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
        currentSurface = null
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
                destroyPreviewSurface()
                destroyEncoderSurface()

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
