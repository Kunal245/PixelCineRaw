package com.kunal.pixelcineraw.gl

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance OpenGL ES 3.0 10-bit BT.2020 HLG Render Pipeline.
 *
 * Emulates the mcpro24fps architecture for devices where Camera2 DynamicRangeProfiles.HLG10
 * is restricted by firmware. Receives Camera2 frames via an offscreen SurfaceTexture (OES),
 * converts color primaries from Rec.709 to Rec.2020 via a 3x3 color matrix, applies the
 * ITU-R BT.2100 HLG OETF transfer curve, and drives both the OLED viewfinder (via EGL_GL_COLORSPACE_BT2020_HLG_EXT)
 * and the HEVCProfileMain10 video encoder.
 *
 * Thread-safe and lazily initialized only when HLG10 mode is requested.
 */
class HlgGlRenderer {

    companion object {
        private const val TAG = "HlgGlRenderer"

        // EGL Colorspace extensions (EGL_EXT_gl_colorspace_bt2020_hlg)
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

        // Full-screen Quad geometry
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        private const val VERTEX_SHADER_CODE = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec4 aTextureCoord;
uniform mat4 uTexMatrix;
out vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uTexMatrix * aTextureCoord).xy;
}
"""

        private const val FRAGMENT_SHADER_CODE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES sTexture;
in vec2 vTextureCoord;
out vec4 fragColor;

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

// Decode logarithmic tone curve from Camera2 sensor to linear scene light
vec3 decodeLinear(vec3 s) {
    // Inverse log transfer: linear = (10^s - 1) / 9
    return clamp((pow(vec3(10.0), s) - 1.0) / 9.0, 0.0, 1.0);
}

void main() {
    vec4 src = texture(sTexture, vTextureCoord);
    vec3 linearRgb = decodeLinear(src.rgb);
    vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
    vec3 hlgRgb = clamp(encodeHlg(rec2020), 0.0, 1.0);
    fragColor = vec4(hlgRgb, 1.0);
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
    private var supportsHlgColorSpace = false

    // Window Surfaces
    private var previewEglSurface: EGLSurface? = null
    private var encoderEglSurface: EGLSurface? = null

    // Camera2 OES Input SurfaceTexture & Surface
    private var oesTextureId: Int = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    var inputSurface: Surface? = null
        private set

    // Shaders & Geometry Buffers
    private var programId: Int = 0
    private var uTexMatrixLoc: Int = -1
    private val texMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // Viewport Proportions
    private var previewWidth = 1920
    private var previewHeight = 1080
    private var viewportX = 0
    private var viewportY = 0
    private var viewportW = 1920
    private var viewportH = 1080

    private var encoderWidth = 3840
    private var encoderHeight = 2160

    @Volatile
    var isReady: Boolean = false
        private set

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
            glThread = HandlerThread("PixelCineHlgGl").apply {
                start()
                glHandler = Handler(looper)
            }
        }
    }

    /**
     * Lazily initializes EGL context with 10-bit config and creates the Camera2 OES input surface.
     */
    fun initialize(width: Int, height: Int, onReady: () -> Unit = {}) {
        previewWidth = width
        previewHeight = height
        ensureGlThreadStarted()
        val handler = glHandler ?: return

        handler.post {
            try {
                if (isReady && eglContext != EGL14.EGL_NO_CONTEXT) {
                    onReady()
                    return@post
                }
                initEGL()
                initShaders()
                initOesTexture(width, height)
                isReady = true
                Log.i(TAG, "HlgGlRenderer initialized successfully (${width}x$height, HLG EGL extension=$supportsHlgColorSpace)")
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize HlgGlRenderer", e)
                isReady = false
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

        // Query available extensions
        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        val hasKhr = extensions.contains("EGL_KHR_gl_colorspace")
        val hasHlg = extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg")
        supportsHlgColorSpace = hasKhr && hasHlg
        Log.d(TAG, "EGL Colorspace query: hasKhr=$hasKhr, hasHlg=$hasHlg")

        // 1. Try choosing 10-bit EGL config (RGBA 10:10:10:2)
        val config10BitAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        var foundConfig = (EGL14.eglChooseConfig(
            eglDisplay, config10BitAttribs, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0)

        if (!foundConfig) {
            Log.w(TAG, "10-bit EGLConfig not found, falling back to 8-bit RGBA_8888")
            val config8BitAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            foundConfig = (EGL14.eglChooseConfig(
                eglDisplay, config8BitAttribs, 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0)
        }

        if (!foundConfig || configs[0] == null) {
            throw RuntimeException("Failed to find suitable EGLConfig")
        }
        eglConfig = configs[0]

        // 2. Create OpenGL ES 3.0 context
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create GLES 3.0 EGLContext")
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

        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader $type: $error")
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

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                glHandler?.post {
                    renderFrame()
                }
            }, glHandler)
        }

        inputSurface = Surface(surfaceTexture)
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
        if (!isReady) return
        val handler = glHandler ?: return
        handler.post {
            try {
                destroyPreviewSurface()
                if (surface != null && surface.isValid) {
                    val eglSurf = createHlgWindowSurface(surface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        calculateViewport(viewWidth, viewHeight, streamWidth, streamHeight)
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
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int) {
        if (!isReady) return
        val handler = glHandler ?: return
        handler.post {
            try {
                destroyEncoderSurface()
                if (surface != null && surface.isValid) {
                    encoderWidth = width
                    encoderHeight = height
                    val eglSurf = createHlgWindowSurface(surface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        encoderEglSurface = eglSurf
                        Log.i(TAG, "Encoder EGL surface attached (${width}x$height)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error setting encoder surface", e)
            }
        }
    }

    private fun createHlgWindowSurface(surface: Surface): EGLSurface {
        val config = eglConfig ?: run {
            Log.w(TAG, "Cannot create window surface: EGLConfig is null")
            return EGL14.EGL_NO_SURFACE
        }
        if (!surface.isValid) {
            Log.w(TAG, "Cannot create window surface: Surface is invalid")
            return EGL14.EGL_NO_SURFACE
        }

        try {
            // 1. Only pass EGL_GL_COLORSPACE_BT2020_HLG_EXT if extension is actually supported!
            if (supportsHlgColorSpace) {
                val hlgAttribs = intArrayOf(
                    EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT,
                    EGL14.EGL_NONE
                )
                val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, hlgAttribs, 0)
                if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
                    return eglSurf
                }
            }

            // 2. Clean window surface without colorspace extension attributes
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

    private fun calculateViewport(viewWidth: Int, viewHeight: Int, streamWidth: Int, streamHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            viewportX = 0
            viewportY = 0
            viewportW = viewWidth
            viewportH = viewHeight
            return
        }

        val streamAspect = streamWidth.toFloat() / streamHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        if (viewAspect > streamAspect) {
            viewportH = viewHeight
            viewportW = (viewHeight * streamAspect).toInt()
            viewportX = (viewWidth - viewportW) / 2
            viewportY = 0
        } else {
            viewportW = viewWidth
            viewportH = (viewWidth / streamAspect).toInt()
            viewportX = 0
            viewportY = (viewHeight - viewportH) / 2
        }
    }

    /**
     * Executes the HLG shader render pass when a new Camera2 frame arrives.
     */
    private fun renderFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val timestampNs = st.timestamp

            // 1. Render to Viewfinder
            val pSurf = previewEglSurface
            if (pSurf != null && pSurf != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, pSurf, pSurf, eglContext)) {
                    GLES20.glViewport(viewportX, viewportY, viewportW, viewportH)
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    drawQuad()
                    EGL14.eglSwapBuffers(eglDisplay, pSurf)
                }
            }

            // 2. Render to MediaCodec HEVC Encoder
            val eSurf = encoderEglSurface
            if (eSurf != null && eSurf != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, eSurf, eSurf, eglContext)) {
                    GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    drawQuad()
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eSurf, timestampNs)
                    EGL14.eglSwapBuffers(eglDisplay, eSurf)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error rendering HLG frame", e)
        }
    }

    private fun drawQuad() {
        GLES20.glUseProgram(programId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    private fun destroyPreviewSurface() {
        previewEglSurface?.let {
            if (it != EGL14.EGL_NO_SURFACE) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error destroying preview EGLSurface", e)
                }
            }
            previewEglSurface = null
        }
    }

    private fun destroyEncoderSurface() {
        encoderEglSurface?.let {
            if (it != EGL14.EGL_NO_SURFACE) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error destroying encoder EGLSurface", e)
                }
            }
            encoderEglSurface = null
        }
    }

    fun release() {
        val handler = glHandler
        if (handler != null) {
            handler.post {
                destroyPreviewSurface()
                destroyEncoderSurface()

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

        glThread?.quitSafely()
        try {
            glThread?.join(1000)
            glThread = null
            glHandler = null
        } catch (e: InterruptedException) {
            Log.w(TAG, "Error stopping GL thread", e)
        }
    }
}
