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
class HlgGlRenderer {

    companion object {
        private const val TAG = "HlgGlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

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
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

        private const val FRAGMENT_SHADER_CODE = """
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES sTexture;
uniform int uColorMode; // 0 = REC.709, 1 = PIXEL-LOG, 2 = HLG / BT.2020
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

void main() {
    vec4 src = texture2D(sTexture, vTextureCoord);
    if (uColorMode == 2) {
        // HLG / BT.2020 Mode (mcpro24fps architecture):
        // 1. Linearize Rec.709 input (gamma 2.2 approximation) to scene light
        vec3 linearRgb = pow(max(vec3(0.0), src.rgb), vec3(2.2));
        // 2. Expand color primaries to Rec.2020 gamut
        vec3 rec2020 = max(vec3(0.0), REC709_TO_REC2020 * linearRgb);
        // 3. Apply smooth ITU-R BT.2100 HLG OETF to expand highlight & shadow dynamic range
        vec3 hlgRgb = clamp(encodeHlg(rec2020), 0.0, 1.0);
        gl_FragColor = vec4(hlgRgb, 1.0);
    } else {
        // REC.709 (uColorMode == 0) or PIXEL-LOG (uColorMode == 1): direct passthrough
        gl_FragColor = src;
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
    private var uColorModeLoc: Int = -1
    private val texMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // Surface Dimensions
    private var previewWidth = 1920
    private var previewHeight = 1080

    private var encoderWidth = 3840
    private var encoderHeight = 2160

    private var hasLoggedFirstFrame = false

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var currentColorMode: Int = 0
        private set

    @Volatile
    var displayRotation: Int = Surface.ROTATION_90
        private set

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    private val previewMatrix = FloatArray(16)

    private fun computePreviewMatrix(stMatrix: FloatArray, rotation: Int): FloatArray {
        System.arraycopy(stMatrix, 0, previewMatrix, 0, 16)
        val rotationDeg = when (rotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_270 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> -90f // Default landscape
        }
        if (rotationDeg != 0f) {
            Matrix.translateM(previewMatrix, 0, 0.5f, 0.5f, 0.0f)
            Matrix.rotateM(previewMatrix, 0, rotationDeg, 0.0f, 0.0f, 1.0f)
            Matrix.translateM(previewMatrix, 0, -0.5f, -0.5f, 0.0f)
        }
        return previewMatrix
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
                    currentSurface = previewSurface
                    val eglSurf = createWindowSurface(previewSurface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Preview surface initialized (${viewWidth}x$viewHeight)")
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

        // Standard 8-bit RGBA_8888 with EGL_RECORDABLE_ANDROID and PBUFFER support
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR or EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
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
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR or EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
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
        uColorModeLoc = GLES20.glGetUniformLocation(programId, "uColorMode")
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
        val handler = glHandler ?: return
        handler.post {
            try {
                if (currentSurface == surface && previewEglSurface != null && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    previewWidth = viewWidth
                    previewHeight = viewHeight
                    return@post
                }
                destroyPreviewSurface()
                if (surface != null && surface.isValid && isReady) {
                    currentSurface = surface
                    previewWidth = viewWidth
                    previewHeight = viewHeight
                    val eglSurf = createWindowSurface(surface)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) {
                        previewEglSurface = eglSurf
                        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)
                        Log.i(TAG, "Preview EGL surface set successfully (${viewWidth}x$viewHeight)")
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
            val timestampNs = st.timestamp

            if (!hasLoggedFirstFrame) {
                hasLoggedFirstFrame = true
                Log.d("PixelCineHDR", "GL frame rendered successfully (mode=$currentColorMode)")
            }

            // 3. Render directly to Viewfinder using full SurfaceView dimensions
            val surfWidth = IntArray(1)
            val surfHeight = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_WIDTH, surfWidth, 0)
            EGL14.eglQuerySurface(eglDisplay, pSurf, EGL14.EGL_HEIGHT, surfHeight, 0)
            val w = if (surfWidth[0] > 0) surfWidth[0] else previewWidth
            val h = if (surfHeight[0] > 0) surfHeight[0] else previewHeight

            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val pMatrix = computePreviewMatrix(texMatrix, displayRotation)
            drawQuad(pMatrix)
            checkGlError("drawQuad preview")
            EGL14.eglSwapBuffers(eglDisplay, pSurf)

            // 4. Render to MediaCodec HEVC Encoder if recording (clean sensor landscape matrix)
            if (eSurf != null && eSurf != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, eSurf, eSurf, eglContext)) {
                    GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    drawQuad(texMatrix)
                    checkGlError("drawQuad encoder")
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eSurf, timestampNs)
                    EGL14.eglSwapBuffers(eglDisplay, eSurf)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error rendering GL frame", e)
        }
    }

    private fun drawQuad(matrix: FloatArray) {
        GLES20.glUseProgram(programId)

        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, matrix, 0)
        GLES20.glUniform1i(uColorModeLoc, currentColorMode)

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
