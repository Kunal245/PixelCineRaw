package com.kunal.pixelcineraw.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Cinema Fluid Zoom Engine:
 * - Direct, unintercepted taps on 0.5x, 1.0x, 2x, 5x with 380ms FastOutSlowIn spring
 * - Wide Cinema Focal Length Ruler with precision gear ratio (0.35x sensitivity)
 * - 120Hz critically damped spring (dampingRatio = NoBouncy, stiffness = 220f)
 */
@Composable
fun CinemaZoomHub(
    state: CinemaState,
    onZoomChanged: (Float) -> Unit,
    onSliderVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val minZoom = state.minZoomRatio
    val maxZoom = state.maxZoomRatio

    // Critically damped spring for silky-smooth fluid cinema zoom
    val animatableProgress = remember {
        Animatable(ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom))
    }

    var isAnimatingByButton by remember { mutableStateOf(false) }
    var autoHideJob by remember { mutableStateOf<Job?>(null) }
    var lastHapticMilestone by remember { mutableIntStateOf(-1) }

    fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = coroutineScope.launch {
            delay(1200L)
            onSliderVisibilityChanged(false)
        }
    }

    fun animateToZoom(targetZoom: Float) {
        val targetNorm = ZoomMath.zoomToNormalized(targetZoom, minZoom, maxZoom)
        autoHideJob?.cancel()
        onSliderVisibilityChanged(true)
        scheduleAutoHide()

        coroutineScope.launch {
            isAnimatingByButton = true
            try {
                animatableProgress.animateTo(
                    targetValue = targetNorm,
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                ) {
                    val computedZoom = ZoomMath.normalizedToZoom(value, minZoom, maxZoom)
                    onZoomChanged(computedZoom)
                }
                onZoomChanged(targetZoom)
            } finally {
                isAnimatingByButton = false
            }
        }
    }

    // Sync external zoom updates (e.g. pinch) to animatableProgress without cancelling button animations
    LaunchedEffect(state.zoomRatio) {
        if (!isAnimatingByButton) {
            val targetNorm = ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom)
            if (abs(animatableProgress.value - targetNorm) > 0.002f) {
                animatableProgress.snapTo(targetNorm)
            }
        }

        // Haptic feedback at major lens milestones (0.5x, 1.0x, 2.0x, 5.0x)
        val milestone = when {
            abs(state.zoomRatio - 0.5f) < 0.04f -> 0
            abs(state.zoomRatio - 1.0f) < 0.04f -> 1
            abs(state.zoomRatio - 2.0f) < 0.04f -> 2
            abs(state.zoomRatio - 5.0f) < 0.04f -> 5
            else -> -1
        }
        if (milestone != -1 && milestone != lastHapticMilestone) {
            lastHapticMilestone = milestone
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Expandable Smooth Cinema Zoom Slider / Ruler (Collapses 1.2s after release)
        AnimatedVisibility(
            visible = state.isZoomSliderVisible,
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
        ) {
            CinemaZoomSliderRuler(
                currentZoom = state.zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onZoomDelta = { deltaNorm ->
                    autoHideJob?.cancel()
                    scheduleAutoHide()
                    coroutineScope.launch {
                        val newNorm = (animatableProgress.targetValue + deltaNorm).coerceIn(0f, 1f)
                        animatableProgress.animateTo(
                            targetValue = newNorm,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 220f
                            )
                        ) {
                            val computedZoom = ZoomMath.normalizedToZoom(value, minZoom, maxZoom)
                            onZoomChanged(computedZoom)
                        }
                    }
                },
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(320.dp)
                    .height(52.dp)
            )
        }

        // Sleek Quick-Tap Lens Pills (0.5x, 1x, 2x, 5x) with dynamic active readout
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
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
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
                        if (!state.isZoomSliderVisible) scheduleAutoHide()
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
}

/**
 * Cinema Zoom Slider / Ruler with fine logarithmic tick marks and center reticle
 */
@Composable
private fun CinemaZoomSliderRuler(
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val norm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Color(0xF20A0C10), RoundedCornerShape(14.dp))
            .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPixels ->
                    // Precision Gear Ratio: 0.35x sensitivity multiplier across 320dp virtual track
                    val deltaNorm = (-deltaPixels / 320f) * 0.35f
                    onZoomDelta(deltaNorm)
                }
            )
            .padding(vertical = 4.dp)
    ) {
        // Tabular Live Focal Length and Zoom Readout
        Text(
            text = "${String.format(Locale.US, "%.2fx", currentZoom)} · ${ZoomMath.formatFocalLength(currentZoom)}",
            color = Color(0xFFFFCC00),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // Horizontal Tick Ruler Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                // Render logarithmic fine tick marks
                val totalTicks = 60
                val tickSpacingPx = 8.dp.toPx()

                for (i in -totalTicks..totalTicks) {
                    val tickNorm = (norm + i * 0.015f).coerceIn(0f, 1f)
                    val tickZoom = ZoomMath.normalizedToZoom(tickNorm, minZoom, maxZoom)
                    val tickX = cx + (i * tickSpacingPx)

                    if (tickX in 0f..size.width) {
                        val isMajor = abs(tickZoom - 0.5f) < 0.04f ||
                                abs(tickZoom - 1.0f) < 0.04f ||
                                abs(tickZoom - 2.0f) < 0.04f ||
                                abs(tickZoom - 3.0f) < 0.04f ||
                                abs(tickZoom - 5.0f) < 0.06f ||
                                abs(tickZoom - 8.0f) < 0.08f

                        val isMedium = !isMajor && (i % 5 == 0)

                        val tickH = when {
                            isMajor -> h * 0.75f
                            isMedium -> h * 0.45f
                            else -> h * 0.25f
                        }

                        val tickColor = when {
                            isMajor -> Color.White
                            isMedium -> Color(0x99FFFFFF)
                            else -> Color(0x33FFFFFF)
                        }

                        drawLine(
                            color = tickColor,
                            start = Offset(tickX, (h - tickH) / 2f),
                            end = Offset(tickX, (h + tickH) / 2f),
                            strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                        )
                    }
                }

                // Center indicator needle (Cinema Gold with top indicator notch)
                drawLine(
                    color = Color(0xFFFFCC00),
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = 2.dp.toPx()
                )

                // Top notch triangle
                val trianglePath = Path().apply {
                    moveTo(cx - 4.dp.toPx(), 0f)
                    lineTo(cx + 4.dp.toPx(), 0f)
                    lineTo(cx, 4.dp.toPx())
                    close()
                }
                drawPath(trianglePath, Color(0xFFFFCC00))
            }
        }
    }
}
