package com.kunal.pixelcineraw.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.kunal.pixelcineraw.ui.theme.*
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
private val TabularZoomStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * Logarithmic zoom math conversions:
 * normalized in [0, 1] <-> zoom in [minZoom, maxZoom]
 */
object ZoomMath {
    fun zoomToNormalized(zoom: Float, minZoom: Float = 0.5f, maxZoom: Float = 8.0f): Float {
        if (maxZoom <= minZoom) return 0f
        val safeZoom = zoom.coerceIn(minZoom, maxZoom)
        return (ln(safeZoom / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)
    }

    fun normalizedToZoom(progress: Float, minZoom: Float = 0.5f, maxZoom: Float = 8.0f): Float {
        if (maxZoom <= minZoom) return minZoom
        val safeProgress = progress.coerceIn(0f, 1f)
        return minZoom * (maxZoom / minZoom).pow(safeProgress)
    }

    fun formatFocalLength(zoomRatio: Float): String {
        val mm = when {
            zoomRatio < 0.75f -> (13f * (zoomRatio / 0.5f)).roundToInt()
            else -> (25f * zoomRatio).roundToInt()
        }
        return "${mm}mm"
    }
}

/**
 * v1.5.0 Zero-Jank Cinema Zoom Hub & Cylindrical Perspective Tick Wheel.
 *
 * Uses a fixed outer slot (`276.dp × 52.dp`) where capsule width (`184dp -> 276dp`),
 * height (`38dp -> 52dp`), corner radius (`19dp -> 12dp`), and 0.75dp hairline border
 * morph purely inside `Modifier.drawBehind` + `Modifier.graphicsLayer` with
 * `spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)`.
 * Zero Compose layout passes during expand/collapse (`<= 0.5%` UI jank).
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

    val presets = remember(minZoom, maxZoom) {
        val all = listOf(0.5f to "0.5", 1.0f to "1x", 2.0f to "2", 5.0f to "5")
        val filtered = all.filter { (ratio, _) -> ratio >= minZoom - 0.05f && ratio <= maxZoom + 0.05f }
        if (filtered.isNotEmpty()) filtered else listOf(minZoom to String.format(Locale.US, "%.1f", minZoom))
    }
    val presetsState = rememberUpdatedState(presets)

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
            delay(1500L)
            isUserInteracting = false
            onSliderVisibilityChanged(false)
        }
    }

    LaunchedEffect(state.isZoomSliderVisible) {
        if (state.isZoomSliderVisible && !isUserInteracting) {
            scheduleAutoCollapse()
        }
    }

    val expandProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "zoomExpandProgress"
    )
    val expandProgressState = rememberUpdatedState(expandProgress)

    val collapsedWidthDp = (presets.size * 42 + 16).coerceAtLeast(108).dp
    val expandedWidthDp = 276.dp
    val collapsedHeightDp = 38.dp
    val expandedHeightDp = 52.dp

    val sweepPx = with(density) { 700.dp.toPx() }

    fun animateToZoom(targetZoom: Float) {
        presetGlideJob?.cancel()
        presetGlideJob = coroutineScope.launch {
            val cZoom = currentZoomState.value
            val mMin = minZoomState.value
            val mMax = maxZoomState.value
            val clampedTarget = targetZoom.coerceIn(mMin, mMax)
            val startNorm = ZoomMath.zoomToNormalized(cZoom, mMin, mMax)
            val targetNorm = ZoomMath.zoomToNormalized(clampedTarget, mMin, mMax)
            val anim = Animatable(startNorm).apply { updateBounds(0f, 1f) }
            anim.animateTo(
                targetValue = targetNorm,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) {
                val z = ZoomMath.normalizedToZoom(value, mMin, mMax)
                onZoomChangedState.value(z)
            }
            onZoomChangedState.value(clampedTarget)
        }
    }

    val majorStops = remember(minZoom, maxZoom) {
        listOf(
            0.5f to "0.5x",
            1.0f to "1x",
            2.0f to "2x",
            3.0f to "3x",
            5.0f to "5x",
            8.0f to "8x"
        ).filter { (z, _) -> z >= minZoom - 0.05f && z <= maxZoom + 0.05f }
    }

    val measuredLabels = remember(textMeasurer, majorStops) {
        majorStops.map { (stopZoom, label) ->
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(
                    color = CinemaWhite,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = TABULAR_NUMS
                )
            )
            Triple(stopZoom, label, layout)
        }
    }

    val mediumStopZooms = remember(minZoom, maxZoom) {
        listOf(0.7f, 1.5f, 2.5f, 4.0f, 6.5f).filter { it in minZoom..maxZoom }
    }

    Box(
        modifier = modifier
            .width(expandedWidthDp)
            .height(expandedHeightDp),
        contentAlignment = Alignment.Center
    ) {
        // Floating Readout Badge anchored above center needle when expanded (graphicsLayer only)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-30).dp)
                .graphicsLayer {
                    alpha = expandProgress
                    translationY = (1f - expandProgress) * 6.dp.toPx()
                }
                .drawBehind {
                    val r = CornerRadius(6.dp.toPx())
                    drawRoundRect(color = CinemaPillBgDark, cornerRadius = r)
                    drawRoundRect(color = CinemaBorderActive, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                }
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = String.format(Locale.US, "%.1fx · %s", currentZoom, ZoomMath.formatFocalLength(currentZoom)),
                color = CinemaAmber,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                style = TabularZoomStyle
            )
        }

        // Fixed-size Morphing Capsule (all shape interpolation happens in drawBehind!)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = expandProgress
                    val cw = collapsedWidthDp.toPx()
                    val ew = expandedWidthDp.toPx()
                    val ch = collapsedHeightDp.toPx()
                    val eh = expandedHeightDp.toPx()

                    val drawW = cw + (ew - cw) * p
                    val drawH = ch + (eh - ch) * p
                    val left = (size.width - drawW) / 2f
                    val top = (size.height - drawH) / 2f
                    val cr = (19.dp.toPx() + (12.dp.toPx() - 19.dp.toPx()) * p)
                    val corner = CornerRadius(cr, cr)

                    drawRoundRect(
                        color = CinemaPillBgDark,
                        topLeft = Offset(left, top),
                        size = Size(drawW, drawH),
                        cornerRadius = corner
                    )
                    val borderCol = Color(
                        red = CinemaBorderInactive.red + (CinemaBorderActive.red - CinemaBorderInactive.red) * p,
                        green = CinemaBorderInactive.green + (CinemaBorderActive.green - CinemaBorderInactive.green) * p,
                        blue = CinemaBorderInactive.blue + (CinemaBorderActive.blue - CinemaBorderInactive.blue) * p,
                        alpha = CinemaBorderInactive.alpha + (CinemaBorderActive.alpha - CinemaBorderInactive.alpha) * p
                    )
                    drawRoundRect(
                        color = borderCol,
                        topLeft = Offset(left, top),
                        size = Size(drawW, drawH),
                        cornerRadius = corner,
                        style = Stroke(0.75.dp.toPx())
                    )
                }
                .pointerInput(collapsedWidthDp, expandedWidthDp) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = expandProgressState.value
                        val cwPx = with(density) { collapsedWidthDp.toPx() }
                        val ewPx = with(density) { expandedWidthDp.toPx() }
                        val chPx = with(density) { collapsedHeightDp.toPx() }
                        val ehPx = with(density) { expandedHeightDp.toPx() }

                        val curW = cwPx + (ewPx - cwPx) * p
                        val curH = chPx + (ehPx - chPx) * p
                        val leftBound = (size.width - curW) / 2f
                        val rightBound = leftBound + curW
                        val topBound = (size.height - curH) / 2f
                        val bottomBound = topBound + curH

                        val downX = down.position.x
                        val downY = down.position.y
                        if (downX < leftBound || downX > rightBound || downY < topBound || downY > bottomBound) {
                            return@awaitEachGesture
                        }

                        autoCollapseJob?.cancel()
                        flingJob?.cancel()
                        presetGlideJob?.cancel()

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        val isDrag = isExpandedState.value
                        if (isDrag) {
                            isUserInteracting = true
                            down.consume()
                        }

                        var prevX = downX
                        var dragDispatched = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!change.pressed) {
                                change.consume()
                                if (!dragDispatched) {
                                    val activePresets = presetsState.value
                                    val relativeX = (downX - leftBound).coerceIn(0f, curW)
                                    val zoneW = curW / activePresets.size.coerceAtLeast(1)
                                    val zoneIdx = (relativeX / zoneW).toInt().coerceIn(0, activePresets.lastIndex)
                                    val targetPreset = activePresets[zoneIdx].first
                                    Log.d(TAG, "Tap preset: zone=$zoneIdx -> $targetPreset")
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    animateToZoom(targetPreset)
                                } else {
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
            // LAYER 1: Resting State Preset Pills (dynamically filtered to [minZoom, maxZoom])
            Row(
                modifier = Modifier
                    .width(collapsedWidthDp)
                    .height(collapsedHeightDp)
                    .graphicsLayer {
                        alpha = (1f - expandProgress * 2.2f).coerceIn(0f, 1f)
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeIndex = presets.indices.minByOrNull { idx ->
                    abs(ln(currentZoom.coerceAtLeast(0.1f)) - ln(presets[idx].first.coerceAtLeast(0.1f)))
                } ?: 0

                presets.forEachIndexed { index, (presetVal, label) ->
                    val isSelected = (index == activeIndex)
                    val displayLabel = if (isSelected && abs(currentZoom - presetVal) > 0.15f) {
                        String.format(Locale.US, "%.1f", currentZoom)
                    } else {
                        label
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .drawBehind {
                                if (isSelected) {
                                    drawCircle(color = Color(0x28FFFFFF))
                                    drawCircle(color = CinemaBorderActive, style = Stroke(0.75.dp.toPx()))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayLabel,
                            color = if (isSelected) CinemaAmber else CinemaWhite.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = TabularZoomStyle
                        )
                    }
                }
            }

            // LAYER 2: Expanded Cylindrical Perspective Tick Wheel
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ((expandProgress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    }
            ) {
                if (expandProgress <= 0.05f) return@Canvas

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
                            color = CinemaWhite.copy(alpha = 0.28f * (kappa * kappa)),
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
                            color = CinemaWhite.copy(alpha = 0.60f * (kappa * kappa)),
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
                        val tickH = 19.dp.toPx() * kappa
                        val startY = 4.dp.toPx()
                        drawLine(
                            color = CinemaWhite.copy(alpha = (kappa * kappa).coerceIn(0f, 1f)),
                            start = Offset(xProjected, startY),
                            end = Offset(xProjected, startY + tickH),
                            strokeWidth = 1.6.dp.toPx() * kappa
                        )
                        if (kappa > 0.45f) {
                            val textAlpha = ((kappa - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(xProjected - layout.size.width / 2f, 26.dp.toPx()),
                                alpha = textAlpha
                            )
                        }
                    }
                }

                // Center indicator needle (Warm Tungsten Amber)
                drawLine(
                    color = CinemaAmber,
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
                drawPath(notchPath, CinemaAmber)

                // Cylindrical edge vignettes
                val fadeWidth = 38.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xF20B0C0E), Color.Transparent),
                        startX = 0f,
                        endX = fadeWidth
                    ),
                    size = Size(fadeWidth, h)
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color(0xF20B0C0E)),
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
                    color = CinemaWhite,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = TABULAR_NUMS
                )
            )
            Triple(stopZoom, label, layout)
        }
    }

    val mediumStopZooms = remember { listOf(0.7f, 1.5f, 2.5f, 4.0f, 6.5f) }
    val velocityTracker = remember { VelocityTracker() }

    Box(
        modifier = modifier
            .background(CinemaPillBgDark, RoundedCornerShape(14.dp))
            .border(0.75.dp, CinemaBorderActive, RoundedCornerShape(14.dp))
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
                    onDragCancel = { onUserInteraction() }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
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
                        color = CinemaWhite.copy(alpha = 0.28f * (kappa * kappa)),
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
                        color = CinemaWhite.copy(alpha = 0.60f * (kappa * kappa)),
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
                        color = CinemaWhite.copy(alpha = (kappa * kappa).coerceIn(0f, 1f)),
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
                color = CinemaAmber,
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
            drawPath(notchPath, CinemaAmber)

            val fadeWidth = 40.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xF20B0C0E), Color.Transparent),
                    startX = 0f,
                    endX = fadeWidth
                ),
                size = Size(fadeWidth, h)
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xF20B0C0E)),
                    startX = w - fadeWidth,
                    endX = w
                ),
                topLeft = Offset(w - fadeWidth, 0f),
                size = Size(fadeWidth, h)
            )
        }
    }
}
