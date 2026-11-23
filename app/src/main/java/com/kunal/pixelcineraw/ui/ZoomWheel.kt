package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * Sleek Quick-Tap Lens Hub (0.5x, 1x, 2x, 5x).
 * Pure non-resizing horizontal pill bar with 300ms cubic ease-out.
 */
@Composable
fun CinemaZoomHub(
    state: CinemaState,
    onZoomChanged: (Float) -> Unit,
    onSliderVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val minZoom = state.minZoomRatio
    val maxZoom = state.maxZoomRatio

    val animatableNorm = remember {
        Animatable(ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom)).apply {
            updateBounds(0f, 1f)
        }
    }
    var isAnimatingPreset by remember { mutableStateOf(false) }

    fun animateToZoom(targetZoom: Float) {
        val targetNorm = ZoomMath.zoomToNormalized(targetZoom, minZoom, maxZoom)
        onSliderVisibilityChanged(true)

        coroutineScope.launch {
            isAnimatingPreset = true
            try {
                animatableNorm.animateTo(
                    targetValue = targetNorm,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                ) {
                    val computedZoom = ZoomMath.normalizedToZoom(value, minZoom, maxZoom)
                    onZoomChanged(computedZoom)
                }
                onZoomChanged(targetZoom)
            } finally {
                isAnimatingPreset = false
            }
        }
    }

    // Sync external zoom updates into animatableNorm
    LaunchedEffect(state.zoomRatio) {
        if (!isAnimatingPreset) {
            val targetNorm = ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom)
            if (abs(animatableNorm.value - targetNorm) > 0.002f) {
                animatableNorm.snapTo(targetNorm)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0xD9101216), RoundedCornerShape(20.dp))
            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { preset ->
            val isSelected = abs(state.zoomRatio - preset) < 0.12f
            val label = if (isSelected) {
                String.format(Locale.US, "%.1fx", state.zoomRatio)
            } else {
                "${if (preset == 0.5f) "0.5" else preset.toInt()}x"
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = if (isSelected) 52.dp else 36.dp, height = 30.dp)
                    .background(
                        color = if (isSelected) Color(0xFFFFCC00) else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        animateToZoom(preset)
                    }
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Live 35mm Focal Length readout & Slider Expander
        Box(
            modifier = Modifier
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    onSliderVisibilityChanged(!state.isZoomSliderVisible)
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ZoomMath.formatFocalLength(state.zoomRatio),
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Sleek, lightweight Apple/Blackmagic-style Canvas Rotary Wheel.
 * Rendered strictly in Canvas draw phase (zero Compose layout recompositions at 120Hz).
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

    // Drag sweep calibration: 260dp sweeps full range 0.5x -> 8.0x in ln(zoom) space
    val sweepDistanceDp = 260.dp
    val sweepPx = with(density) { sweepDistanceDp.toPx() }

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

    // Pre-measure tick labels once to avoid allocations during 120Hz draw loop
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
                    color = Color(0xCCFFFFFF),
                    fontSize = 8.5.sp,
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
            .background(Color(0xE60A0C10), RoundedCornerShape(12.dp))
            .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
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

                        // Direct 1:1 responsive drag in logarithmic space (zero spring lag)
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
                            // Inertial decay glide: flick coasts smoothly like physical mechanical wheel
                            flingJob = coroutineScope.launch {
                                val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)
                                val initialNormVelocity = -velocity / sweepPx
                                val decaySpec = exponentialDecay<Float>(frictionMultiplier = 1.35f)
                                animatableNorm.snapTo(currentNorm)
                                animatableNorm.animateDecay(
                                    initialVelocity = initialNormVelocity,
                                    animationSpec = decaySpec
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
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
        ) {
            val cx = size.width / 2f
            val h = size.height
            val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)

            // 1. Minor logarithmic ticks (spaced every 0.02 in norm space)
            val minorTickCount = 50
            for (i in 0..minorTickCount) {
                val tickNorm = i / minorTickCount.toFloat()
                val tickX = cx + (tickNorm - currentNorm) * sweepPx
                if (tickX in 0f..size.width) {
                    drawLine(
                        color = Color(0x33FFFFFF),
                        start = Offset(tickX, 10.dp.toPx()),
                        end = Offset(tickX, 20.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            // 2. Medium intermediate ticks
            for (mZoom in mediumStopZooms) {
                val mNorm = ZoomMath.zoomToNormalized(mZoom, minZoom, maxZoom)
                val mX = cx + (mNorm - currentNorm) * sweepPx
                if (mX in 0f..size.width) {
                    drawLine(
                        color = Color(0x77FFFFFF),
                        start = Offset(mX, 7.dp.toPx()),
                        end = Offset(mX, 23.dp.toPx()),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
            }

            // 3. Major stops with measured text labels
            for ((stopZoom, _, layout) in measuredLabels) {
                val sNorm = ZoomMath.zoomToNormalized(stopZoom, minZoom, maxZoom)
                val sX = cx + (sNorm - currentNorm) * sweepPx
                if (sX in -40f..(size.width + 40f)) {
                    // Major tick line
                    drawLine(
                        color = Color.White,
                        start = Offset(sX, 4.dp.toPx()),
                        end = Offset(sX, 24.dp.toPx()),
                        strokeWidth = 1.8.dp.toPx()
                    )
                    // Text label below tick
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(sX - layout.size.width / 2f, 26.dp.toPx())
                    )
                }
            }

            // 4. Center indicator needle (Cinema Gold with top indicator triangle notch)
            drawLine(
                color = Color(0xFFFFCC00),
                start = Offset(cx, 0f),
                end = Offset(cx, h),
                strokeWidth = 2.dp.toPx()
            )

            val notchPath = Path().apply {
                moveTo(cx - 4.dp.toPx(), 0f)
                lineTo(cx + 4.dp.toPx(), 0f)
                lineTo(cx, 4.5.dp.toPx())
                close()
            }
            drawPath(notchPath, Color(0xFFFFCC00))

            // 5. Edge fade vignettes (smoothly fades ticks at container borders)
            val fadeWidth = 36.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xF20A0C10), Color.Transparent),
                    startX = 0f,
                    endX = fadeWidth
                ),
                size = Size(fadeWidth, h)
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xF20A0C10)),
                    startX = size.width - fadeWidth,
                    endX = size.width
                ),
                topLeft = Offset(size.width - fadeWidth, 0f),
                size = Size(fadeWidth, h)
            )
        }
    }
}
