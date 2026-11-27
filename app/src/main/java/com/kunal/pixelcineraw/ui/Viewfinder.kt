package com.kunal.pixelcineraw.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.CinemaViewModel
import com.kunal.pixelcineraw.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TabularViewfinderStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * Cinema Viewfinder powered by AutoFitSurfaceView.
 *
 * v2.0.1: Keeps the root screen 100% edge-to-edge behind transparent system bars while
 * fitting the active capture surface accurately to the selected recording aspect ratio
 * (`16:9`, `4:3`, `2.00:1`, `1:1`, `3:2`) with a subtle `0.5dp` `#FFFFFF` `18%` frame border.
 */
@Composable
fun Viewfinder(
    viewModel: CinemaViewModel,
    modifier: Modifier = Modifier,
    onDismissOverlays: () -> Boolean = { false }
) {
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    val reticleScale = remember { Animatable(1.28f) }
    val reticleAlpha = remember { Animatable(1.0f) }
    var reticleAutoHideJob by remember { mutableStateOf<Job?>(null) }

    var surfaceViewRef by remember { mutableStateOf<AutoFitSurfaceView?>(null) }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(state.currentSensorSpec, state.videoResolution, isPortrait) {
        val vRes = state.videoResolution
        val w = if (isPortrait) minOf(vRes.width, vRes.height) else maxOf(vRes.width, vRes.height)
        val h = if (isPortrait) maxOf(vRes.width, vRes.height) else minOf(vRes.width, vRes.height)
        surfaceViewRef?.setAspectRatio(w, h)
    }

    LaunchedEffect(state.colorProfile, state.isRecording, state.targetFps) {
        surfaceViewRef?.applyColorProfileSurfaceState(
            profile = state.colorProfile,
            isRecording = state.isRecording,
            targetFps = state.targetFps
        )
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
    val frameW = if (isPortrait) minOf(vRes.width, vRes.height).coerceAtLeast(1) else maxOf(vRes.width, vRes.height).coerceAtLeast(1)
    val frameH = if (isPortrait) maxOf(vRes.width, vRes.height).coerceAtLeast(1) else minOf(vRes.width, vRes.height).coerceAtLeast(1)
    val targetAspect = frameW.toFloat() / frameH.toFloat()
    val onDismissOverlaysState = rememberUpdatedState(onDismissOverlays)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    onDismissOverlaysState.value()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Active aspect-accurate capture container
        Box(
            modifier = Modifier.aspectRatio(targetAspect),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    AutoFitSurfaceView(ctx).apply {
                        surfaceViewRef = this
                        setFullBleed(false)
                        val curW = if (isPortrait) minOf(vRes.width, vRes.height) else maxOf(vRes.width, vRes.height)
                        val curH = if (isPortrait) maxOf(vRes.width, vRes.height) else minOf(vRes.width, vRes.height)
                        setAspectRatio(curW, curH)
                        applyColorProfileSurfaceState(
                            profile = state.colorProfile,
                            isRecording = state.isRecording,
                            targetFps = state.targetFps
                        )

                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceViewRef = this@apply
                                val curState = viewModel.state.value
                                applyColorProfileSurfaceState(
                                    profile = curState.colorProfile,
                                    force = true,
                                    isRecording = curState.isRecording,
                                    targetFps = curState.targetFps
                                )
                                display?.rotation?.let { viewModel.cameraController.setDisplayRotation(it) }
                                val surface = holder.surface
                                if (surface.isValid) {
                                    viewModel.attachPreviewSurface(surface)
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                val curState = viewModel.state.value
                                val curRes = curState.videoResolution
                                val isPort = display?.rotation == android.view.Surface.ROTATION_0 || display?.rotation == android.view.Surface.ROTATION_180 ||
                                        curState.orientationLockMode == com.kunal.pixelcineraw.camera.OrientationLockMode.LOCK_PORTRAIT
                                val cW = if (isPort) minOf(curRes.width, curRes.height) else maxOf(curRes.width, curRes.height)
                                val cH = if (isPort) maxOf(curRes.width, curRes.height) else minOf(curRes.width, curRes.height)
                                setAspectRatio(cW, cH)
                                applyColorProfileSurfaceState(
                                    profile = curState.colorProfile,
                                    force = false,
                                    isRecording = curState.isRecording,
                                    targetFps = curState.targetFps
                                )
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
                    view.setFullBleed(false)
                    val curW = if (isPortrait) minOf(vRes.width, vRes.height) else maxOf(vRes.width, vRes.height)
                    val curH = if (isPortrait) maxOf(vRes.width, vRes.height) else minOf(vRes.width, vRes.height)
                    view.setAspectRatio(curW, curH)
                    view.applyColorProfileSurfaceState(
                        profile = state.colorProfile,
                        isRecording = state.isRecording,
                        targetFps = state.targetFps
                    )
                    view.display?.rotation?.let { viewModel.cameraController.setDisplayRotation(it) }
                }
            )

            // Seamless Lens Transition Crossfade
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
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                }
            }

            // Unified Touch & Reticle Overlay Box (aligned to exact capture area)
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
                                delay(1500L)
                                viewModel.cameraController.setZoomSliderVisibility(false)
                            }

                            viewModel.cameraController.setZoomRatio(nextZoom)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (onDismissOverlaysState.value()) {
                            return@detectTapGestures
                        }

                        val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        viewModel.cameraController.tapToFocus(normX, normY)

                        tapOffset = offset
                        reticleAutoHideJob?.cancel()
                        reticleAutoHideJob = coroutineScope.launch {
                            reticleScale.snapTo(1.28f)
                            reticleAlpha.snapTo(1.0f)
                            reticleScale.animateTo(
                                targetValue = 1.0f,
                                animationSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    dampingRatio = 0.82f
                                )
                            )
                            delay(3500L)
                            reticleAlpha.animateTo(0.0f, tween(350))
                            tapOffset = null
                        }
                    }
                }
        ) {
            val maxWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val maxHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            tapOffset?.let { offset ->
                val density = LocalDensity.current
                val bracketColor = if (state.isAeAfLocked) CinemaCrimson else CinemaAmber

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = reticleAlpha.value }
                ) {
                    // 1. Leica / Halide Precision Focus Bracket Reticle with GPU graphicsLayer spring snap
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val s = reticleScale.value
                                scaleX = s
                                scaleY = s
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = (offset.x / maxWidthPx).coerceIn(0f, 1f),
                                    pivotFractionY = (offset.y / maxHeightPx).coerceIn(0f, 1f)
                                )
                            }
                    ) {
                        val boxSize = 60.dp.toPx()
                        val half = boxSize / 2f
                        val bracketLen = 13.dp.toPx()
                        val strokeW = 1.75.dp.toPx()

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

                        // Center hairline crosshair
                        val cross = 4.5.dp.toPx()
                        drawLine(bracketColor, Offset(offset.x - cross, offset.y), Offset(offset.x + cross, offset.y), 1.2.dp.toPx())
                        drawLine(bracketColor, Offset(offset.x, offset.y - cross), Offset(offset.x, offset.y + cross), 1.2.dp.toPx())
                    }

                    // 2. Vertical Micro-EV Drag Slider beside Reticle
                    val offset42 = with(density) { 42.dp.toPx() }
                    val offset45 = with(density) { 45.dp.toPx() }
                    val sliderX = (offset.x + offset42).coerceAtMost(maxWidthPx - with(density) { 42.dp.toPx() })
                    val sliderY = (offset.y - offset45).coerceIn(10f, maxHeightPx - with(density) { 95.dp.toPx() })

                    Column(
                        modifier = Modifier
                            .offset { IntOffset(sliderX.roundToInt(), sliderY.roundToInt()) }
                            .width(34.dp)
                            .height(88.dp)
                            .drawBehind {
                                val r = CornerRadius(17.dp.toPx())
                                drawRoundRect(color = CinemaCapsuleBg, cornerRadius = r)
                                drawRoundRect(color = CinemaSpecularTop, cornerRadius = r, style = Stroke(0.5.dp.toPx()))

                                val trackX = size.width / 2f
                                val topY = 24.dp.toPx()
                                val botY = size.height - 22.dp.toPx()
                                drawLine(CinemaBorderInactive, Offset(trackX, topY), Offset(trackX, botY), 1.5.dp.toPx())
                                val normEv = ((state.evCompensation + 2.0f) / 4.0f).coerceIn(0f, 1f)
                                val thumbY = botY - normEv * (botY - topY)
                                drawCircle(CinemaAmber, radius = 3.dp.toPx(), center = Offset(trackX, thumbY))
                            }
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
                            tint = CinemaAmber,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (state.evCompensation > 0f) "+%.1f".format(state.evCompensation) else "%.1f".format(state.evCompensation),
                            color = CinemaWhite,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = TabularViewfinderStyle
                        )
                    }

                    // 3. Quick AE/AF LOCK Pill above bracket
                    val offset30 = with(density) { 30.dp.toPx() }
                    val offset54 = with(density) { 54.dp.toPx() }
                    val lockX = (offset.x - offset30).coerceIn(10f, maxWidthPx - with(density) { 86.dp.toPx() })
                    val lockY = (offset.y - offset54).coerceAtLeast(10f)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(lockX.roundToInt(), lockY.roundToInt()) }
                            .drawBehind {
                                val r = CornerRadius(10.dp.toPx())
                                drawRoundRect(
                                    color = if (state.isAeAfLocked) CinemaCrimson else CinemaCapsuleBg,
                                    cornerRadius = r
                                )
                                drawRoundRect(
                                    color = if (state.isAeAfLocked) CinemaCrimson else CinemaSpecularTop,
                                    cornerRadius = r,
                                    style = Stroke(0.5.dp.toPx())
                                )
                            }
                            .clickable { viewModel.cameraController.toggleAeAfLock() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (state.isAeAfLocked) "AE/AF LOCKED" else "LOCK AE/AF",
                            color = CinemaWhite,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            }

            // Subtle 0.5dp #FFFFFF 18% frame border marking exact capture boundaries
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val strokeW = 0.5.dp.toPx()
                        val half = strokeW / 2f
                        drawRect(
                            color = CinemaSpecularTop,
                            topLeft = Offset(half, half),
                            size = Size(size.width - strokeW, size.height - strokeW),
                            style = Stroke(width = strokeW)
                        )
                    }
            )
        }
    }
}
