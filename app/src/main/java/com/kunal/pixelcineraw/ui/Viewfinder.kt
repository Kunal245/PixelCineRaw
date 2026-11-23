package com.kunal.pixelcineraw.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.CinemaViewModel
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.HistogramData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cinema Viewfinder powered by Google's official AutoFitSurfaceView pattern.
 *
 * Guarantees zero distortion across all video resolutions and aspect ratios (16:9, 4:3 Open Gate,
 * 1.90:1 DCI, etc.) because Android's SurfaceView hardware layer natively handles sensor orientation
 * and onMeasure enforces exact aspect-ratio proportions without any artificial matrix stretching.
 */
@Composable
fun Viewfinder(
    viewModel: CinemaViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    val reticleScale = remember { Animatable(1.4f) }
    val reticleAlpha = remember { Animatable(1.0f) }

    var surfaceViewRef by remember { mutableStateOf<AutoFitSurfaceView?>(null) }

    // Dynamically update AutoFitSurfaceView aspect ratio whenever sensor spec or video resolution changes
    LaunchedEffect(state.currentSensorSpec, state.videoResolution) {
        val previewSize = viewModel.cameraController.optimalPreviewSize
        surfaceViewRef?.let { sv ->
            sv.holder.setFixedSize(previewSize.width, previewSize.height)
            sv.setAspectRatio(previewSize.width, previewSize.height)
        }
    }

    // Configure 10-bit HDR or 8-bit SDR surface mode on AutoFitSurfaceView
    LaunchedEffect(state.colorProfile) {
        val isHdr = state.colorProfile == ColorProfile.HLG_10BIT
        surfaceViewRef?.setHdrMode(isHdr)
    }

    var pinchAutoHideJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1.0f) {
                            val currentZoom = viewModel.state.value.zoomRatio
                            val newZoom = currentZoom * zoom
                            viewModel.cameraController.setZoomRatio(newZoom)
                            viewModel.cameraController.setZoomSliderVisibility(true)

                            pinchAutoHideJob?.cancel()
                            pinchAutoHideJob = coroutineScope.launch {
                                delay(1500L)
                                viewModel.cameraController.setZoomSliderVisibility(false)
                            }
                        }
                    }
                }
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
                AutoFitSurfaceView(ctx).apply {
                    surfaceViewRef = this
                    val initialSize = viewModel.cameraController.optimalPreviewSize
                    setAspectRatio(initialSize.width, initialSize.height)
                    holder.setFixedSize(initialSize.width, initialSize.height)
                    setHdrMode(state.colorProfile == ColorProfile.HLG_10BIT)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val surface = holder.surface
                            if (surface.isValid) {
                                viewModel.attachPreviewSurface(surface)
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            val pSize = viewModel.cameraController.optimalPreviewSize
                            setAspectRatio(pSize.width, pSize.height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.detachPreviewSurface()
                            surfaceViewRef = null
                        }
                    })
                }
            },
            update = { view ->
                val currentSize = viewModel.cameraController.optimalPreviewSize
                view.holder.setFixedSize(currentSize.width, currentSize.height)
                view.setAspectRatio(currentSize.width, currentSize.height)
                view.setHdrMode(state.colorProfile == ColorProfile.HLG_10BIT)
            }
        )

        // Seamless Lens Transition Crossfade and Scale-Match Mask (eliminates black flash/freeze)
        val transitionScaleAnim by animateFloatAsState(
            targetValue = if (state.isTransitioningLens) state.transitionScale else 1.0f,
            animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            label = "LensTransitionScale"
        )

        AnimatedVisibility(
            visible = state.isTransitioningLens,
            enter = fadeIn(animationSpec = tween(durationMillis = 40)),
            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transitionScaleAnim
                        scaleY = transitionScaleAnim
                    },
                contentAlignment = Alignment.Center
            ) {
                state.transitionBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                )
            }
        }

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
