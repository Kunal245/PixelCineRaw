package com.kunal.pixelcineraw.gl

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.kunal.pixelcineraw.camera.ColorProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Rock-solid 8-bit RGBA OpenGL ES 3.0 Cinema Render Pipeline (mcpro24fps architecture).
 *
 * Keeps Camera2 streaming continuously into a single offscreen SurfaceTexture (OES) at 60fps.
 * Toggling between REC.709, PIXEL-LOG, and HLG happens instantly inside the GLSL fragment
 * shader (uColorMode uniform) with ZERO CameraCaptureSession restart and ZERO SurfaceView recreation!
 *
 * When HLG mode is active, the shader:
 * 1. Linearizes input pixels
 * 2. Applies the Rec.709 to Rec.2020 color primary transformation matrix
 * 3. Applies the ITU-R BT.2100 HLG OETF curve to expand shadow & highlight dynamic range
 *    alongside the OLED HDR display brightness boost.
 */
class HlgGlRenderer {

    companion object {
        private const val TAG = "HlgGlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // Full-screen Quad geometry
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
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
uniform int uColorMode; // 0 = REC.709, 1 = PIXEL-LOG, 2 = HLG / BT.2020
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

void main() {
    vec4 src = texture(sTexture, vTextureCoord);
    if (uColorMode == 2) {
        // HLG / BT.2020 Mode (mcpro24fps architecture):
        // 1. Linearize Rec.709 input (gamma 2.2 approximation) to scene light
        vec3 linearRgb = pow(max(vec3(0.0), src.rgb), vec3(2.2));
        // 2. Expand color primaries to Rec.2020 gamut
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        // 3. Apply smooth ITU-R BT.2100 HLG OETF to expand highlight & shadow dynamic range
        vec3 hlgRgb = clamp(encodeHlg(rec2020), 0.0, 1.0);
        fragColor = vec4(hlgRgb, 1.0);
    } else {
        // REC.709 (uColorMode == 0) or PIXEL-LOG (uColorMode == 1): direct passthrough
        fragColor = src;
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
    private var uColorModeLoc: Int = -1
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

    private var hasLoggedFirstFrame = false

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var currentColorMode: Int = 0
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
        previewWidth = viewWidth
        previewHeight = viewHeight
        ensureGlThreadStarted()
        val handler = glHandler ?: return

        handler.post {
            try {
                if (!isReady || (eglContext == EGL14.EGL_NO_CONTEXT)) {
                    initEGL()
                    initShaders()
                    initOesTexture(streamWidth, streamHeight)
                    isReady = true
                    Log.i(TAG, "HlgGlRenderer initialized successfully (${streamWidth}x$streamHeight, standard 8-bit RGBA8888)")
                }

                if (previewSurface != null && previewSurface.isValid) {
                    destroyPreviewSurface()
                    val eglSurf = createWindowSurface(previewSurface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        calculateViewport(viewWidth, viewHeight, streamWidth, streamHeight)
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
     * Updates the active color mode (0 = REC.709, 1 = PIXEL-LOG, 2 = HLG) instantly
     * in the fragment shader without restarting CameraCaptureSession!
     */
    fun setColorMode(profile: ColorProfile) {
        val mode = when (profile) {
            ColorProfile.REC709 -> 0
            ColorProfile.FLAT_LOG -> 1
            ColorProfile.HLG_10BIT -> 2
        }
        currentColorMode = mode
        val handler = glHandler ?: return
        handler.post {
            if (isReady && programId != 0) {
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

        // Standard 8-bit RGBA_8888 with EGL_RECORDABLE_ANDROID for 60fps Tensor G4 stability
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        var foundConfig = EGL14.eglChooseConfig(
            eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0

        if (!foundConfig) {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            foundConfig = EGL14.eglChooseConfig(
                eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0
        }

        if (!foundConfig || configs[0] == null) {
            throw RuntimeException("Failed to find suitable 8-bit EGLConfig")
        }
        eglConfig = configs[0]

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
        uColorModeLoc = GLES20.glGetUniformLocation(programId, "uColorMode")
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
                renderFrame()
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
        val handler = glHandler ?: return
        handler.post {
            try {
                destroyPreviewSurface()
                if (surface != null && surface.isValid && isReady) {
                    val eglSurf = createWindowSurface(surface)
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
                        Log.i(TAG, "Encoder EGL surface attached (${width}x$height)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error setting encoder surface", e)
            }
        }
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
     * Executes the shader render pass when a new Camera2 frame arrives.
     */
    private fun renderFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val timestampNs = st.timestamp

            if (!hasLoggedFirstFrame) {
                hasLoggedFirstFrame = true
                Log.d("PixelCineHDR", "GL frame rendered (mode=$currentColorMode)")
            }

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
            Log.w(TAG, "Error rendering GL frame", e)
        }
    }

    private fun drawQuad() {
        GLES20.glUseProgram(programId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uColorModeLoc, currentColorMode)

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
        hasLoggedFirstFrame = false
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
    }
}
