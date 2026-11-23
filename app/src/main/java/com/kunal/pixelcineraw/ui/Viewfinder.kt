package com.kunal.pixelcineraw.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.CinemaViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Cinema Viewfinder powered by Google's official AutoFitSurfaceView pattern.
 *
 * Guarantees zero distortion across all video resolutions and aspect ratios (16:9, 4:3 Open Gate,
 * 1.90:1 DCI, etc.) because Android's SurfaceView hardware layer natively handles sensor orientation
 * and onMeasure enforces exact aspect-ratio proportions without any artificial matrix stretching.
 *
 * Both the gesture listener and the animated focus reticle live in the exact same AutoFitSurfaceView
 * overlay container, ensuring perfect 1:1 reticle alignment dead-center on the touched pixel.
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
    var reticleAutoHideJob by remember { mutableStateOf<Job?>(null) }

    var surfaceViewRef by remember { mutableStateOf<AutoFitSurfaceView?>(null) }

    // Dynamically update AutoFitSurfaceView aspect ratio whenever sensor spec or video resolution changes
    LaunchedEffect(state.currentSensorSpec, state.videoResolution) {
        val vRes = state.videoResolution
        val w = maxOf(vRes.width, vRes.height)
        val h = minOf(vRes.width, vRes.height)
        surfaceViewRef?.let { sv ->
            sv.setAspectRatio(w, h)
            sv.holder.setFixedSize(w, h)
        }
    }

    // Apply native HDR extended range brightness and BT.2020 HLG/PQ data space on Pixel 9a OLED
    LaunchedEffect(state.colorProfile) {
        surfaceViewRef?.setHdrMode(state.colorProfile)
    }

    val context = LocalContext.current
    DisposableEffect(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val rotation = viewModel.cameraController.getDisplayRotation()
                viewModel.cameraController.setDisplayRotation(rotation)
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            displayManager?.unregisterDisplayListener(listener)
        }
    }

    var pinchAutoHideJob by remember { mutableStateOf<Job?>(null) }

    val vRes = state.videoResolution
    val resWidth = maxOf(vRes.width, vRes.height)
    val resHeight = minOf(vRes.width, vRes.height)
    val streamAspect = resWidth.toFloat() / resHeight.toFloat()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Viewport container matching exact aspect ratio of the camera stream
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .aspectRatio(streamAspect),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    AutoFitSurfaceView(ctx).apply {
                        surfaceViewRef = this
                        val curW = maxOf(vRes.width, vRes.height)
                        val curH = minOf(vRes.width, vRes.height)
                        setAspectRatio(curW, curH)
                        holder.setFixedSize(curW, curH)
                        setHdrMode(state.colorProfile)

                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceViewRef = this@apply
                                setHdrMode(state.colorProfile)
                                display?.rotation?.let { viewModel.cameraController.setDisplayRotation(it) }
                                val surface = holder.surface
                                if (surface.isValid) {
                                    viewModel.attachPreviewSurface(surface)
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                val curRes = viewModel.state.value.videoResolution
                                val cW = maxOf(curRes.width, curRes.height)
                                val cH = minOf(curRes.width, curRes.height)
                                setAspectRatio(cW, cH)
                                setHdrMode(state.colorProfile)
                                display?.rotation?.let { viewModel.cameraController.setDisplayRotation(it) }
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.detachPreviewSurface()
                            }
                        })
                    }
                },
                update = { view ->
                    surfaceViewRef = view
                    val curW = maxOf(vRes.width, vRes.height)
                    val curH = minOf(vRes.width, vRes.height)
                    view.setAspectRatio(curW, curH)
                    view.holder.setFixedSize(curW, curH)
                    view.setHdrMode(state.colorProfile)
                    view.display?.rotation?.let { viewModel.cameraController.setDisplayRotation(it) }
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

            // Unified Touch & Reticle Overlay Box (Exactly matching AutoFitSurfaceView bounds)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.minZoomRatio, state.maxZoomRatio) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1.0f) {
                                val scaleFactor = 1.0f + (zoom - 1.0f) * 0.75f
                                val currentZoom = state.zoomRatio
                                val nextZoom = (currentZoom * scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)

                                viewModel.cameraController.setZoomSliderVisibility(true)
                                pinchAutoHideJob?.cancel()
                                pinchAutoHideJob = coroutineScope.launch {
                                    delay(1200L)
                                    viewModel.cameraController.setZoomSliderVisibility(false)
                                }

                                viewModel.cameraController.setZoomRatio(nextZoom)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                            viewModel.cameraController.tapToFocus(normX, normY)

                            tapOffset = offset
                            reticleAutoHideJob?.cancel()
                            reticleAutoHideJob = coroutineScope.launch {
                                reticleScale.snapTo(1.3f)
                                reticleAlpha.snapTo(1.0f)
                                reticleScale.animateTo(1.0f, tween(200))
                                delay(3500L)
                                reticleAlpha.animateTo(0.0f, tween(400))
                                tapOffset = null
                            }
                        }
                    }
            ) {
                val maxWidthPx = constraints.maxWidth.toFloat()
                val maxHeightPx = constraints.maxHeight.toFloat()

                // Animated Tap-to-Focus Cinema Reticle with Vertical EV Slider & AE/AF Lock
                tapOffset?.let { offset ->
                    val scale = reticleScale.value
                    val alpha = reticleAlpha.value
                    val density = LocalDensity.current

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { this.alpha = alpha }
                    ) {
                        // 1. Center Cinema Corner Bracket Reticle [ + ]
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val boxSize = 64.dp.toPx() * scale
                            val half = boxSize / 2f
                            val bracketLen = 14.dp.toPx()
                            val strokeW = 2.dp.toPx()
                            val bracketColor = if (state.isAeAfLocked) Color(0xFFFF3344) else Color(0xFFFFCC00)

                            // Top-Left
                            drawLine(bracketColor, Offset(offset.x - half, offset.y - half), Offset(offset.x - half + bracketLen, offset.y - half), strokeW)
                            drawLine(bracketColor, Offset(offset.x - half, offset.y - half), Offset(offset.x - half, offset.y - half + bracketLen), strokeW)

                            // Top-Right
                            drawLine(bracketColor, Offset(offset.x + half, offset.y - half), Offset(offset.x + half - bracketLen, offset.y - half), strokeW)
                            drawLine(bracketColor, Offset(offset.x + half, offset.y - half), Offset(offset.x + half, offset.y - half + bracketLen), strokeW)

                            // Bottom-Left
                            drawLine(bracketColor, Offset(offset.x - half, offset.y + half), Offset(offset.x - half + bracketLen, offset.y + half), strokeW)
                            drawLine(bracketColor, Offset(offset.x - half, offset.y + half), Offset(offset.x - half, offset.y + half - bracketLen), strokeW)

                            // Bottom-Right
                            drawLine(bracketColor, Offset(offset.x + half, offset.y + half), Offset(offset.x + half - bracketLen, offset.y + half), strokeW)
                            drawLine(bracketColor, Offset(offset.x + half, offset.y + half), Offset(offset.x + half, offset.y + half - bracketLen), strokeW)

                            // Center crosshair
                            val cross = 6.dp.toPx()
                            drawLine(bracketColor, Offset(offset.x - cross, offset.y), Offset(offset.x + cross, offset.y), 1.5.dp.toPx())
                            drawLine(bracketColor, Offset(offset.x, offset.y - cross), Offset(offset.x, offset.y + cross), 1.5.dp.toPx())
                        }

                        // 2. Vertical EV Drag Slider beside Reticle
                        val offset44 = with(density) { 44.dp.toPx() }
                        val offset45 = with(density) { 45.dp.toPx() }
                        val sliderX = (offset.x + offset44).coerceAtMost(maxWidthPx - with(density) { 44.dp.toPx() })
                        val sliderY = (offset.y - offset45).coerceIn(10f, maxHeightPx - with(density) { 95.dp.toPx() })

                        Column(
                            modifier = Modifier
                                .offset { IntOffset(sliderX.roundToInt(), sliderY.roundToInt()) }
                                .width(36.dp)
                                .height(90.dp)
                                .background(Color(0xD9101216), RoundedCornerShape(18.dp))
                                .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { _, dragAmount ->
                                        val evDelta = -dragAmount / 40f
                                        val newEv = (state.evCompensation + evDelta).coerceIn(-2.0f, 2.0f)
                                        viewModel.cameraController.setEvCompensation(newEv)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = "Exposure",
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (state.evCompensation > 0f) "+%.1f".format(state.evCompensation) else "%.1f".format(state.evCompensation),
                                color = Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // 3. Quick AE/AF LOCK Pill directly above the bracket
                        val offset30 = with(density) { 30.dp.toPx() }
                        val offset54 = with(density) { 54.dp.toPx() }
                        val lockX = (offset.x - offset30).coerceIn(10f, maxWidthPx - with(density) { 80.dp.toPx() })
                        val lockY = (offset.y - offset54).coerceAtLeast(10f)
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(lockX.roundToInt(), lockY.roundToInt()) }
                                .background(if (state.isAeAfLocked) Color(0xFFFF3344) else Color(0xB3101216), RoundedCornerShape(4.dp))
                                .border(0.6.dp, if (state.isAeAfLocked) Color(0xFFFF6677) else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                                .clickable { viewModel.cameraController.toggleAeAfLock() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (state.isAeAfLocked) "AE/AF LOCKED" else "LOCK AE/AF",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
