package com.kunal.pixelcineraw.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "CinemaZoomHub"

/**
 * Logarithmic zoom math conversions:
 * normalized in [0, 1] <-> zoom in [minZoom, maxZoom]
 */
object ZoomMath {
    fun zoomToNormalized(zoom: Float, minZoom: Float = 0.5f, maxZoom: Float = 8.0f): Float {
        val safeZoom = zoom.coerceIn(minZoom, maxZoom)
        return (ln(safeZoom / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)
    }

    fun normalizedToZoom(progress: Float, minZoom: Float = 0.5f, maxZoom: Float = 8.0f): Float {
        val safeProgress = progress.coerceIn(0f, 1f)
        return minZoom * (maxZoom / minZoom).pow(safeProgress)
    }

    fun formatFocalLength(zoomRatio: Float): String {
        // Pixel 9a optical mapping:
        // 0.5x = 13mm, 1x = 25mm, 2x = 50mm, 5x = 125mm, 8x = 200mm
        val mm = when {
            zoomRatio < 0.75f -> (13f * (zoomRatio / 0.5f)).roundToInt()
            else -> (25f * zoomRatio).roundToInt()
        }
        return "${mm}mm"
    }
}

/**
 * Unified iPhone Pro Camera-style Zoom Wheel & Quick-Tap Hub.
 *
 * - Resting State (Compact Frosted Pill): 40dp height, Color(0xB8101216), 1dp border Color(0x24FFFFFF),
 *   housing circular preset badges (0.5, 1x, 2, 5), where the active lens sits in a clean circular highlight
 *   Color(0x2EFFFFFF) with golden-yellow tabular text (#FFD60A).
 *
 * - Unified Gesture (Zero Touch Conflict):
 *   - Tap: Detects which preset zone was tapped and smoothly glides to that exact ratio in 320ms.
 *   - Slide: Drag past touchSlop immediately morphs the bar into the 280dp cylindrical perspective tick wheel
 *     without requiring finger lift, tracking logarithmic zoom 1:1 with inertial fling decay on release.
 *   - Auto-collapses back to compact pill 1.2s after finger release.
 */
@Composable
fun CinemaZoomHub(
    state: CinemaState,
    onZoomChanged: (Float) -> Unit,
    onSliderVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val minZoom = state.minZoomRatio
    val maxZoom = state.maxZoomRatio
    val currentZoom = state.zoomRatio

    val currentZoomState = rememberUpdatedState(currentZoom)
    val minZoomState = rememberUpdatedState(minZoom)
    val maxZoomState = rememberUpdatedState(maxZoom)
    val onZoomChangedState = rememberUpdatedState(onZoomChanged)

    var isUserInteracting by remember { mutableStateOf(false) }
    val isExpanded = isUserInteracting || state.isZoomSliderVisible
    val isExpandedState = rememberUpdatedState(isExpanded)

    var autoCollapseJob by remember { mutableStateOf<Job?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var presetGlideJob by remember { mutableStateOf<Job?>(null) }
    var lastHapticMilestone by remember { mutableIntStateOf(-1) }

    fun checkHaptics(zoom: Float) {
        val milestone = when {
            abs(zoom - 0.5f) < 0.04f -> 0
            abs(zoom - 1.0f) < 0.04f -> 1
            abs(zoom - 2.0f) < 0.04f -> 2
            abs(zoom - 3.0f) < 0.04f -> 3
            abs(zoom - 5.0f) < 0.05f -> 5
            abs(zoom - 8.0f) < 0.06f -> 8
            else -> -1
        }
        if (milestone != -1 && milestone != lastHapticMilestone) {
            lastHapticMilestone = milestone
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (milestone == -1) {
            lastHapticMilestone = -1
        }
    }

    fun scheduleAutoCollapse() {
        autoCollapseJob?.cancel()
        autoCollapseJob = coroutineScope.launch {
            delay(1200L)
            isUserInteracting = false
            onSliderVisibilityChanged(false)
        }
    }

    // Auto-collapse when pinch-to-zoom finishes externally
    LaunchedEffect(state.isZoomSliderVisible) {
        if (state.isZoomSliderVisible && !isUserInteracting) {
            scheduleAutoCollapse()
        }
    }

    val containerWidth by animateDpAsState(
        targetValue = if (isExpanded) 280.dp else 192.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "zoomWidth"
    )
    val containerHeight by animateDpAsState(
        targetValue = if (isExpanded) 54.dp else 40.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "zoomHeight"
    )
    val containerRadius by animateDpAsState(
        targetValue = if (isExpanded) 16.dp else 20.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "zoomRadius"
    )
    val expandProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "zoomExpandProgress"
    )

    // Sweep calibration: 3.5x heavy damped precision gear ratio (700dp sweeps normalized [0, 1] range)
    val sweepPx = with(density) { 700.dp.toPx() }

    fun animateToZoom(targetZoom: Float) {
        presetGlideJob?.cancel()
        presetGlideJob = coroutineScope.launch {
            val cZoom = currentZoomState.value
            val mMin = minZoomState.value
            val mMax = maxZoomState.value
            val startNorm = ZoomMath.zoomToNormalized(cZoom, mMin, mMax)
            val targetNorm = ZoomMath.zoomToNormalized(targetZoom, mMin, mMax)
            val anim = Animatable(startNorm).apply { updateBounds(0f, 1f) }
            anim.animateTo(
                targetValue = targetNorm,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            ) {
                val z = ZoomMath.normalizedToZoom(value, mMin, mMax)
                onZoomChangedState.value(z)
            }
            onZoomChangedState.value(targetZoom)
        }
    }

    // Pre-measure tick labels for major stops
    val majorStops = remember {
        listOf(
            0.5f to "0.5x",
            1.0f to "1x",
            2.0f to "2x",
            3.0f to "3x",
            5.0f to "5x",
            8.0f to "8x"
        )
    }

    val measuredLabels = remember(textMeasurer) {
        majorStops.map { (stopZoom, label) ->
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Triple(stopZoom, label, layout)
        }
    }

    val mediumStopZooms = remember {
        listOf(0.7f, 1.5f, 2.5f, 4.0f, 6.5f)
    }

    Box(
        modifier = modifier.wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        // Floating Readout Badge (Anchored above center needle when expanded)
        if (expandProgress > 0.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-32).dp)
                    .graphicsLayer {
                        alpha = expandProgress
                        translationY = (1f - expandProgress) * 8f
                    }
                    .background(Color(0xE6101216), RoundedCornerShape(8.dp))
                    .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx · %s", currentZoom, ZoomMath.formatFocalLength(currentZoom)),
                    color = Color(0xFFFFD60A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Morphing Capsule
        Box(
            modifier = Modifier
                .width(containerWidth)
                .height(containerHeight)
                .background(Color(0xB8101216), RoundedCornerShape(containerRadius))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(containerRadius))
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downX = down.position.x
                        val totalWidth = size.width.toFloat()

                        autoCollapseJob?.cancel()
                        flingJob?.cancel()
                        presetGlideJob?.cancel()

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        var isDrag = isExpandedState.value
                        if (isDrag) {
                            isUserInteracting = true
                            down.consume()
                        }

                        var prevX = down.position.x
                        var dragDispatched = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!change.pressed) {
                                change.consume()
                                if (!dragDispatched) {
                                    // Tap on preset badge
                                    val quadrantWidth = totalWidth / 4f
                                    val quadrantIndex = (downX / quadrantWidth).toInt().coerceIn(0, 3)
                                    val targetPreset = when (quadrantIndex) {
                                        0 -> 0.5f
                                        1 -> 1.0f
                                        2 -> 2.0f
                                        3 -> 5.0f
                                        else -> 1.0f
                                    }
                                    Log.d(TAG, "Tap preset: downX=$downX, quadrant=$quadrantIndex -> $targetPreset")
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    animateToZoom(targetPreset)
                                } else {
                                    // Drag released: apply inertial fling decay
                                    val velocity = velocityTracker.calculateVelocity().x
                                    if (abs(velocity) > 80f) {
                                        flingJob = coroutineScope.launch {
                                            val cZoom = currentZoomState.value
                                            val mMin = minZoomState.value
                                            val mMax = maxZoomState.value
                                            val curNorm = ZoomMath.zoomToNormalized(cZoom, mMin, mMax)
                                            val anim = Animatable(curNorm).apply { updateBounds(0f, 1f) }
                                            val initialNormVel = -velocity / sweepPx
                                            anim.animateDecay(
                                                initialVelocity = initialNormVel,
                                                animationSpec = exponentialDecay(frictionMultiplier = 1.35f)
                                            ) {
                                                val z = ZoomMath.normalizedToZoom(value, mMin, mMax)
                                                onZoomChangedState.value(z)
                                                checkHaptics(z)
                                            }
                                            scheduleAutoCollapse()
                                        }
                                    } else {
                                        scheduleAutoCollapse()
                                    }
                                }
                                break
                            }

                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val currentX = change.position.x
                            val deltaFromDown = currentX - downX

                            if (!dragDispatched) {
                                if (abs(deltaFromDown) > touchSlop || isDrag) {
                                    dragDispatched = true
                                    isUserInteracting = true
                                    onSliderVisibilityChanged(true)
                                    change.consume()
                                    prevX = currentX
                                    Log.d(TAG, "Drag started: deltaFromDown=$deltaFromDown")
                                }
                            } else {
                                change.consume()
                                val deltaX = currentX - prevX
                                prevX = currentX

                                val deltaNorm = -deltaX / sweepPx
                                val cZoom = currentZoomState.value
                                val mMin = minZoomState.value
                                val mMax = maxZoomState.value
                                val curNorm = ZoomMath.zoomToNormalized(cZoom, mMin, mMax)
                                val newNorm = (curNorm + deltaNorm).coerceIn(0f, 1f)
                                val newZoom = ZoomMath.normalizedToZoom(newNorm, mMin, mMax)
                                onZoomChangedState.value(newZoom)
                                checkHaptics(newZoom)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // LAYER 1: Resting State Preset Pills (0.5, 1x, 2, 5)
            if (expandProgress < 0.95f) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (1f - expandProgress * 2f).coerceIn(0f, 1f)
                        },
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val presets = listOf(
                        0.5f to "0.5",
                        1.0f to "1x",
                        2.0f to "2",
                        5.0f to "5"
                    )
                    val activeIndex = when {
                        currentZoom < 0.75f -> 0
                        currentZoom < 1.5f -> 1
                        currentZoom < 3.5f -> 2
                        else -> 3
                    }
                    presets.forEachIndexed { index, (presetVal, label) ->
                        val isSelected = (index == activeIndex)
                        val displayLabel = if (isSelected && abs(currentZoom - presetVal) > 0.15f) {
                            String.format(Locale.US, "%.1f", currentZoom)
                        } else {
                            label
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = if (isSelected) Color(0x2EFFFFFF) else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayLabel,
                                color = if (isSelected) Color(0xFFFFD60A) else Color(0xCCFFFFFF),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // LAYER 2: Expanded Cylindrical Perspective Tick Wheel (Canvas)
            if (expandProgress > 0.05f) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ((expandProgress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cylinderRadius = w / 2.3f

                    val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)

                    // 1. Minor logarithmic ticks (~55 ticks across [0, 1])
                    val minorTickCount = 55
                    for (i in 0..minorTickCount) {
                        val tickNorm = i / minorTickCount.toFloat()
                        val deltaNorm = tickNorm - currentNorm
                        val arcDist = deltaNorm * sweepPx
                        val theta = arcDist / cylinderRadius
                        if (theta > -PI / 2.0 && theta < PI / 2.0) {
                            val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                            val kappa = cos(theta.toDouble()).toFloat()
                            val tickH = 9.dp.toPx() * kappa
                            val startY = 8.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.28f * (kappa * kappa)),
                                start = Offset(xProjected, startY),
                                end = Offset(xProjected, startY + tickH),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    // 2. Medium intermediate ticks (0.7x, 1.5x, 2.5x, 4.0x, 6.5x)
                    for (mZoom in mediumStopZooms) {
                        val mNorm = ZoomMath.zoomToNormalized(mZoom, minZoom, maxZoom)
                        val deltaNorm = mNorm - currentNorm
                        val arcDist = deltaNorm * sweepPx
                        val theta = arcDist / cylinderRadius
                        if (theta > -PI / 2.0 && theta < PI / 2.0) {
                            val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                            val kappa = cos(theta.toDouble()).toFloat()
                            val tickH = 15.dp.toPx() * kappa
                            val startY = 6.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.60f * (kappa * kappa)),
                                start = Offset(xProjected, startY),
                                end = Offset(xProjected, startY + tickH),
                                strokeWidth = 1.2.dp.toPx()
                            )
                        }
                    }

                    // 3. Major stops with measured text labels (0.5x, 1x, 2x, 3x, 5x, 8x)
                    for ((stopZoom, _, layout) in measuredLabels) {
                        val sNorm = ZoomMath.zoomToNormalized(stopZoom, minZoom, maxZoom)
                        val deltaNorm = sNorm - currentNorm
                        val arcDist = deltaNorm * sweepPx
                        val theta = arcDist / cylinderRadius
                        if (theta > -PI / 2.0 && theta < PI / 2.0) {
                            val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                            val kappa = cos(theta.toDouble()).toFloat()
                            val tickH = 20.dp.toPx() * kappa
                            val startY = 4.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = (kappa * kappa).coerceIn(0f, 1f)),
                                start = Offset(xProjected, startY),
                                end = Offset(xProjected, startY + tickH),
                                strokeWidth = 1.8.dp.toPx() * kappa
                            )
                            if (kappa > 0.45f) {
                                val textAlpha = ((kappa - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                drawText(
                                    textLayoutResult = layout,
                                    topLeft = Offset(xProjected - layout.size.width / 2f, 27.dp.toPx()),
                                    alpha = textAlpha
                                )
                            }
                        }
                    }

                    // 4. Center indicator needle (#FFD60A)
                    drawLine(
                        color = Color(0xFFFFD60A),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 2.dp.toPx()
                    )

                    val notchPath = Path().apply {
                        moveTo(cx - 3.5.dp.toPx(), 0f)
                        lineTo(cx + 3.5.dp.toPx(), 0f)
                        lineTo(cx, 4.dp.toPx())
                        close()
                    }
                    drawPath(notchPath, Color(0xFFFFD60A))

                    // 5. Cylindrical edge vignettes
                    val fadeWidth = 40.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xF2101216), Color.Transparent),
                            startX = 0f,
                            endX = fadeWidth
                        ),
                        size = Size(fadeWidth, h)
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color(0xF2101216)),
                            startX = w - fadeWidth,
                            endX = w
                        ),
                        topLeft = Offset(w - fadeWidth, 0f),
                        size = Size(fadeWidth, h)
                    )
                }
            }
        }
    }
}

/**
 * Reusable standalone ZoomWheel for backward compatibility.
 */
@Composable
fun ZoomWheel(
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChanged: (Float) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val sweepPx = with(density) { 280.dp.toPx() }
    val animatableNorm = remember {
        Animatable(ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)).apply {
            updateBounds(0f, 1f)
        }
    }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var lastHapticMilestone by remember { mutableIntStateOf(-1) }

    fun checkHaptics(zoom: Float) {
        val milestone = when {
            abs(zoom - 0.5f) < 0.04f -> 0
            abs(zoom - 1.0f) < 0.04f -> 1
            abs(zoom - 2.0f) < 0.04f -> 2
            abs(zoom - 3.0f) < 0.04f -> 3
            abs(zoom - 5.0f) < 0.05f -> 5
            abs(zoom - 8.0f) < 0.06f -> 8
            else -> -1
        }
        if (milestone != -1 && milestone != lastHapticMilestone) {
            lastHapticMilestone = milestone
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val majorStops = remember {
        listOf(
            0.5f to "0.5x",
            1.0f to "1x",
            2.0f to "2x",
            3.0f to "3x",
            5.0f to "5x",
            8.0f to "8x"
        )
    }

    val measuredLabels = remember(textMeasurer) {
        majorStops.map { (stopZoom, label) ->
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Triple(stopZoom, label, layout)
        }
    }

    val mediumStopZooms = remember {
        listOf(0.7f, 1.5f, 2.5f, 4.0f, 6.5f)
    }

    val velocityTracker = remember { VelocityTracker() }

    Box(
        modifier = modifier
            .background(Color(0xB8101216), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp))
            .pointerInput(minZoom, maxZoom, sweepPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                        flingJob?.cancel()
                        onUserInteraction()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        onUserInteraction()

                        val deltaNorm = -dragAmount / sweepPx
                        val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)
                        val newNorm = (currentNorm + deltaNorm).coerceIn(0f, 1f)
                        val computedZoom = ZoomMath.normalizedToZoom(newNorm, minZoom, maxZoom)

                        onZoomChanged(computedZoom)
                        checkHaptics(computedZoom)
                    },
                    onDragEnd = {
                        onUserInteraction()
                        val velocity = velocityTracker.calculateVelocity().x
                        if (abs(velocity) > 80f) {
                            flingJob = coroutineScope.launch {
                                val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)
                                val initialNormVelocity = -velocity / sweepPx
                                animatableNorm.snapTo(currentNorm)
                                animatableNorm.animateDecay(
                                    initialVelocity = initialNormVelocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 1.35f)
                                ) {
                                    val clampedNorm = value.coerceIn(0f, 1f)
                                    val computedZoom = ZoomMath.normalizedToZoom(clampedNorm, minZoom, maxZoom)
                                    onZoomChanged(computedZoom)
                                    checkHaptics(computedZoom)
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        onUserInteraction()
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cylinderRadius = w / 2.3f
            val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)

            val minorTickCount = 55
            for (i in 0..minorTickCount) {
                val tickNorm = i / minorTickCount.toFloat()
                val deltaNorm = tickNorm - currentNorm
                val arcDist = deltaNorm * sweepPx
                val theta = arcDist / cylinderRadius
                if (theta > -PI / 2.0 && theta < PI / 2.0) {
                    val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                    val kappa = cos(theta.toDouble()).toFloat()
                    val tickH = 9.dp.toPx() * kappa
                    val startY = 8.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f * (kappa * kappa)),
                        start = Offset(xProjected, startY),
                        end = Offset(xProjected, startY + tickH),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            for (mZoom in mediumStopZooms) {
                val mNorm = ZoomMath.zoomToNormalized(mZoom, minZoom, maxZoom)
                val deltaNorm = mNorm - currentNorm
                val arcDist = deltaNorm * sweepPx
                val theta = arcDist / cylinderRadius
                if (theta > -PI / 2.0 && theta < PI / 2.0) {
                    val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                    val kappa = cos(theta.toDouble()).toFloat()
                    val tickH = 15.dp.toPx() * kappa
                    val startY = 6.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.60f * (kappa * kappa)),
                        start = Offset(xProjected, startY),
                        end = Offset(xProjected, startY + tickH),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
            }

            for ((stopZoom, _, layout) in measuredLabels) {
                val sNorm = ZoomMath.zoomToNormalized(stopZoom, minZoom, maxZoom)
                val deltaNorm = sNorm - currentNorm
                val arcDist = deltaNorm * sweepPx
                val theta = arcDist / cylinderRadius
                if (theta > -PI / 2.0 && theta < PI / 2.0) {
                    val xProjected = cx + cylinderRadius * sin(theta.toDouble()).toFloat()
                    val kappa = cos(theta.toDouble()).toFloat()
                    val tickH = 20.dp.toPx() * kappa
                    val startY = 4.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = (kappa * kappa).coerceIn(0f, 1f)),
                        start = Offset(xProjected, startY),
                        end = Offset(xProjected, startY + tickH),
                        strokeWidth = 1.8.dp.toPx() * kappa
                    )
                    if (kappa > 0.45f) {
                        val textAlpha = ((kappa - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(xProjected - layout.size.width / 2f, 27.dp.toPx()),
                            alpha = textAlpha
                        )
                    }
                }
            }

            drawLine(
                color = Color(0xFFFFD60A),
                start = Offset(cx, 0f),
                end = Offset(cx, h),
                strokeWidth = 2.dp.toPx()
            )

            val notchPath = Path().apply {
                moveTo(cx - 3.5.dp.toPx(), 0f)
                lineTo(cx + 3.5.dp.toPx(), 0f)
                lineTo(cx, 4.dp.toPx())
                close()
            }
            drawPath(notchPath, Color(0xFFFFD60A))

            val fadeWidth = 40.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xF2101216), Color.Transparent),
                    startX = 0f,
                    endX = fadeWidth
                ),
                size = Size(fadeWidth, h)
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xF2101216)),
                    startX = w - fadeWidth,
                    endX = w
                ),
                topLeft = Offset(w - fadeWidth, 0f),
                size = Size(fadeWidth, h)
            )
        }
    }
}
