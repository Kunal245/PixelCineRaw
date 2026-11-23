package com.kunal.pixelcineraw.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.CinemaViewModel
import com.kunal.pixelcineraw.camera.HistogramData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cinema Viewfinder with precise rotation and aspect-ratio matrix transformations
 * (keeping image upright in ROTATION_90 and ROTATION_270 without stretching)
 * and real-time downsampled frame histogram extraction.
 */
@Composable
fun Viewfinder(
    viewModel: CinemaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    val reticleScale = remember { Animatable(1.4f) }
    val reticleAlpha = remember { Animatable(1.0f) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    // Reapply matrix transform whenever sensor spec or optimal preview size changes
    LaunchedEffect(state.currentSensorSpec) {
        textureViewRef?.let { tv ->
            val w = tv.width
            val h = tv.height
            if (w > 0 && h > 0) {
                val previewSize = viewModel.cameraController.optimalPreviewSize
                applyViewfinderTransform(
                    textureView = tv,
                    viewWidth = w,
                    viewHeight = h,
                    sensorOrientation = state.currentSensorSpec?.sensorOrientation ?: 90,
                    previewWidth = previewSize.width,
                    previewHeight = previewSize.height
                )
            }
        }
    }

    // Register DisplayListener to dynamically handle 180° rotation flips between ROTATION_90 and ROTATION_270
    DisposableEffect(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val tv = textureViewRef ?: return
                val w = tv.width
                val h = tv.height
                if (w > 0 && h > 0) {
                    val previewSize = viewModel.cameraController.optimalPreviewSize
                    applyViewfinderTransform(
                        textureView = tv,
                        viewWidth = w,
                        viewHeight = h,
                        sensorOrientation = viewModel.state.value.currentSensorSpec?.sensorOrientation ?: 90,
                        previewWidth = previewSize.width,
                        previewHeight = previewSize.height
                    )
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        onDispose {
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    // Downsampled Real-Time Histogram Extraction from Live Viewfinder
    LaunchedEffect(state.showHistogram) {
        if (!state.showHistogram) return@LaunchedEffect

        val sampleW = 64
        val sampleH = 36
        val sampleBitmap = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
        val pixelArray = IntArray(sampleW * sampleH)

        while (isActive) {
            val tv = textureViewRef
            if (tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                try {
                    // Extract downsampled 64x36 frame directly from GPU texture (~0.2ms)
                    tv.getBitmap(sampleBitmap)
                    sampleBitmap.getPixels(pixelArray, 0, sampleW, 0, 0, sampleW, sampleH)

                    val histogramData = withContext(Dispatchers.Default) {
                        calculateHistogram(pixelArray)
                    }

                    viewModel.cameraController.updateHistogramData(histogramData)
                } catch (e: Exception) {
                    // Ignore transient surface recycling
                }
            }
            delay(100L) // 10 fps telemetry sampling - lightweight and smooth
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val normX = offset.x / size.width
                        val normY = offset.y / size.height
                        viewModel.cameraController.tapToFocus(normX, normY)

                        tapOffset = offset
                        coroutineScope.launch {
                            reticleScale.snapTo(1.4f)
                            reticleAlpha.snapTo(1.0f)
                            reticleScale.animateTo(1.0f, tween(250))
                            reticleAlpha.animateTo(0.0f, tween(1500))
                            tapOffset = null
                        }
                    }
                },
            factory = { ctx ->
                TextureView(ctx).apply {
                    textureViewRef = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                            val previewSize = viewModel.cameraController.optimalPreviewSize
                            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                            val surface = Surface(surfaceTexture)
                            viewModel.attachPreviewSurface(surface)

                            applyViewfinderTransform(
                                textureView = this@apply,
                                viewWidth = width,
                                viewHeight = height,
                                sensorOrientation = viewModel.state.value.currentSensorSpec?.sensorOrientation ?: 90,
                                previewWidth = previewSize.width,
                                previewHeight = previewSize.height
                            )
                        }

                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                            val previewSize = viewModel.cameraController.optimalPreviewSize
                            applyViewfinderTransform(
                                textureView = this@apply,
                                viewWidth = width,
                                viewHeight = height,
                                sensorOrientation = viewModel.state.value.currentSensorSpec?.sensorOrientation ?: 90,
                                previewWidth = previewSize.width,
                                previewHeight = previewSize.height
                            )
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            viewModel.detachPreviewSurface()
                            textureViewRef = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                    }

                    addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        val w = right - left
                        val h = bottom - top
                        if (w > 0 && h > 0) {
                            val previewSize = viewModel.cameraController.optimalPreviewSize
                            applyViewfinderTransform(
                                textureView = this,
                                viewWidth = w,
                                viewHeight = h,
                                sensorOrientation = viewModel.state.value.currentSensorSpec?.sensorOrientation ?: 90,
                                previewWidth = previewSize.width,
                                previewHeight = previewSize.height
                            )
                        }
                    }
                }
            }
        )

        // Animated Tap-to-Focus Cinema Reticle
        tapOffset?.let { offset ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = reticleScale.value
                val alpha = reticleAlpha.value
                val boxSize = 72.dp.toPx() * scale
                val half = boxSize / 2f

                // Outer square
                drawRect(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    topLeft = Offset(offset.x - half, offset.y - half),
                    size = Size(boxSize, boxSize),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Center crosshairs
                val crossLength = 8.dp.toPx()
                drawLine(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    start = Offset(offset.x - crossLength, offset.y),
                    end = Offset(offset.x + crossLength, offset.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    start = Offset(offset.x, offset.y - crossLength),
                    end = Offset(offset.x, offset.y + crossLength),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
    }
}

/**
 * Computes and applies a matrix transformation to ensure:
 * 1. Image is upright in both landscape orientations (ROTATION_90 and ROTATION_270).
 * 2. Aspect ratio is preserved (16:9 pillarboxed/letterboxed, never stretched).
 */
fun applyViewfinderTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    sensorOrientation: Int,
    previewWidth: Int = 1920,
    previewHeight: Int = 1080
) {
    if (viewWidth == 0 || viewHeight == 0) return

    val display = textureView.display
        ?: (textureView.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    val displayRotation = display?.rotation ?: Surface.ROTATION_90

    val surfaceRotationDegrees = when (displayRotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 90
    }

    // Official Android Camera2 formula for back-facing camera:
    // (sensorOrientation + surfaceRotationDegrees) % 360
    val relativeRotation = (sensorOrientation + surfaceRotationDegrees) % 360

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // 16:9 buffer aspect ratio
    val bufferAspect = previewWidth.toFloat() / previewHeight.toFloat()
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

    val scaleX: Float
    val scaleY: Float

    if (viewAspect > bufferAspect) {
        // View is wider than 16:9 (e.g. 20:9 phone screen) -> Pillarbox (bars on left/right)
        scaleX = (viewHeight * bufferAspect) / viewWidth
        scaleY = 1.0f
    } else {
        // View is taller than 16:9 -> Letterbox (bars on top/bottom)
        scaleX = 1.0f
        scaleY = (viewWidth / bufferAspect) / viewHeight
    }

    // 1. Center and scale to preserve 16:9 without stretching
    matrix.setScale(scaleX, scaleY, centerX, centerY)

    // 2. Rotate around center to keep upright in both landscape orientations
    if (relativeRotation != 0) {
        matrix.postRotate(relativeRotation.toFloat(), centerX, centerY)
    }

    textureView.setTransform(matrix)
}

/**
 * Calculates real 32-bin normalized histograms for Red, Green, Blue, and Luma.
 */
private fun calculateHistogram(pixels: IntArray): HistogramData {
    val rCounts = IntArray(32)
    val gCounts = IntArray(32)
    val bCounts = IntArray(32)
    val lCounts = IntArray(32)

    var maxCount = 1

    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)

        val rBin = r shr 3 // 0..31
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

    return HistogramData(redBins, greenBins, blueBins, lumaBins)
}
