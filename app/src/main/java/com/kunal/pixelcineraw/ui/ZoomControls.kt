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
import kotlin.math.ln
import kotlin.math.pow

/**
 * Logarithmic zoom math conversions:
 * normalized in [0, 1] <-> zoom in [minZoom, maxZoom]
 */
object ZoomMath {
    fun zoomToNormalized(zoom: Float, minZoom: Float, maxZoom: Float): Float {
        val safeZoom = zoom.coerceIn(minZoom, maxZoom)
        return (ln(safeZoom / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)
    }

    fun normalizedToZoom(progress: Float, minZoom: Float, maxZoom: Float): Float {
        val safeProgress = progress.coerceIn(0f, 1f)
        return minZoom * (maxZoom / minZoom).pow(safeProgress)
    }

    fun formatFocalLength(zoomRatio: Float): String {
        // Pixel 9a main wide is 25mm equivalent (at 1x)
        // 0.5x = 13mm, 1x = 25mm, 2x = 50mm, 5x = 125mm, 8x = 200mm
        val mm = (25f * zoomRatio).toInt()
        return "${mm}mm"
    }
}

/**
 * Cinema Zoom Hub: Quick lens buttons, live zoom ratio badge, and interactive cinema zoom slider
 * with critically damped spring interpolation and tactile haptics.
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

    // Critically damped spring for iOS-smooth zooming with zero jitter
    val animatableProgress = remember {
        Animatable(
            initialValue = ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom)
        )
    }

    var autoHideJob by remember { mutableStateOf<Job?>(null) }
    var lastHapticMilestone by remember { mutableIntStateOf(1) }

    fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = coroutineScope.launch {
            delay(2000L)
            onSliderVisibilityChanged(false)
        }
    }

    fun animateToZoom(targetZoom: Float) {
        val targetNorm = ZoomMath.zoomToNormalized(targetZoom, minZoom, maxZoom)
        autoHideJob?.cancel()
        onSliderVisibilityChanged(true)
        scheduleAutoHide()

        coroutineScope.launch {
            animatableProgress.animateTo(
                targetValue = targetNorm,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) {
                val computedZoom = ZoomMath.normalizedToZoom(value, minZoom, maxZoom)
                onZoomChanged(computedZoom)
            }
        }
    }

    // Sync state changes from pinch or external updates to animatable
    LaunchedEffect(state.zoomRatio) {
        val targetNorm = ZoomMath.zoomToNormalized(state.zoomRatio, minZoom, maxZoom)
        if (Math.abs(animatableProgress.value - targetNorm) > 0.005f) {
            animatableProgress.snapTo(targetNorm)
        }

        // Haptic ticks at lens milestones (0.5x, 1x, 2x, 3x, 5x, 8x)
        val milestone = when {
            state.zoomRatio < 0.8f -> 0
            state.zoomRatio in 0.95f..1.1f -> 1
            state.zoomRatio in 1.95f..2.1f -> 2
            state.zoomRatio in 2.95f..3.1f -> 3
            state.zoomRatio in 4.95f..5.1f -> 5
            state.zoomRatio >= 7.9f -> 8
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
        // Expandable Smooth Cinema Zoom Slider / Ruler
        AnimatedVisibility(
            visible = state.isZoomSliderVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CinemaZoomSliderRuler(
                currentZoom = state.zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onZoomDelta = { deltaNorm ->
                    autoHideJob?.cancel()
                    scheduleAutoHide()
                    coroutineScope.launch {
                        val newNorm = (animatableProgress.value + deltaNorm).coerceIn(0f, 1f)
                        animatableProgress.snapTo(newNorm)
                        val newZoom = ZoomMath.normalizedToZoom(newNorm, minZoom, maxZoom)
                        onZoomChanged(newZoom)
                    }
                },
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .width(280.dp)
                    .height(44.dp)
            )
        }

        // Sleek Quick-Tap Lens Pills (0.5x, 1x, 2x, 5x) with dynamic active readout
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(Color(0xD9101216), RoundedCornerShape(20.dp))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { preset ->
                val isSelected = Math.abs(state.zoomRatio - preset) < 0.15f
                val label = if (isSelected) {
                    String.format(Locale.US, "%.1fx", state.zoomRatio)
                } else {
                    "${if (preset == 0.5f) "0.5" else preset.toInt()}x"
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = if (isSelected) 48.dp else 34.dp, height = 30.dp)
                        .background(
                            color = if (isSelected) Color(0xFFFFD700) else Color.Transparent,
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

            // Live 35mm Focal Length readout
            Text(
                text = ZoomMath.formatFocalLength(state.zoomRatio),
                color = Color(0x99FFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clickable {
                        onSliderVisibilityChanged(!state.isZoomSliderVisible)
                        if (!state.isZoomSliderVisible) scheduleAutoHide()
                    }
            )
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color(0xE60D0F12), RoundedCornerShape(12.dp))
            .border(0.5.dp, Color(0x44FFFFFF), RoundedCornerShape(12.dp))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPixels ->
                    // Sensitivity: drag 240dp horizontally traverses the full zoom range
                    val deltaNorm = -deltaPixels / 240f
                    onZoomDelta(deltaNorm)
                }
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Baseline
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.dp.toPx()
            )

            // Tick marks mapped logarithmically
            val ticks = 24
            val currentNorm = ZoomMath.zoomToNormalized(currentZoom, minZoom, maxZoom)

            for (i in 0..ticks) {
                val tickNorm = i.toFloat() / ticks
                val x = (tickNorm - currentNorm) * w + (w / 2f)

                if (x in 0f..w) {
                    val isMajor = i % 4 == 0
                    val tickH = if (isMajor) 14.dp.toPx() else 7.dp.toPx()
                    val tickColor = if (isMajor) Color(0xBBFFFFFF) else Color(0x55FFFFFF)

                    drawLine(
                        color = tickColor,
                        start = Offset(x, midY - tickH / 2f),
                        end = Offset(x, midY + tickH / 2f),
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }
            }

            // Center indicator needle (Cinema Gold)
            val needleX = w / 2f
            drawLine(
                color = Color(0xFFFFD700),
                start = Offset(needleX, 6.dp.toPx()),
                end = Offset(needleX, h - 6.dp.toPx()),
                strokeWidth = 2.5.dp.toPx()
            )
        }
    }
}
