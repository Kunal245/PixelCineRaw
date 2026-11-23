package com.kunal.pixelcineraw.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
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

enum class ActiveDial {
    NONE,
    FPS,
    SHUTTER,
    ISO,
    FOCUS,
    WHITE_BALANCE
}

/**
 * Cinema Rotary Dial / Horizontal Scrubber with tactile tick marks, haptic clicks,
 * monospaced tabular figures, and quick-selection presets.
 */
@Composable
fun <T> RotaryWheelScrubber(
    title: String,
    items: List<T>,
    selectedIndex: Int,
    onItemSelected: (Int, T) -> Unit,
    formatItem: (T) -> String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    presets: List<Pair<String, Int>> = emptyList(),
    headerActions: @Composable RowScope.() -> Unit = {}
) {
    val view = LocalView.current
    var dragAccumulator by remember(selectedIndex) { mutableFloatStateOf(0f) }
    val tickWidth = 32f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF2101317), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Bar: Title, Sub-actions, Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.uppercase(),
                    color = Color(0xFFFFCC00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(10.dp))
                headerActions()
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dial",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 2. Large Central Readout with Tabular Figures
        val currentItem = items.getOrNull(selectedIndex)
        Text(
            text = if (currentItem != null) formatItem(currentItem) else "--",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // 3. Quick-Jump Preset Buttons (if available)
        if (presets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { (label, targetIdx) ->
                    val isSelected = selectedIndex == targetIdx
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Color(0xFFFFCC00) else Color(0xFF1E2126),
                                RoundedCornerShape(4.dp)
                            )
                            .border(0.5.dp, if (isSelected) Color(0xFFFFCC00) else Color(0x2AFFFFFF), RoundedCornerShape(4.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onItemSelected(targetIdx, items[targetIdx])
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Horizontal Tactile Tick Wheel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(Color(0xFF08090B), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dragAccumulator += delta
                        val ticksMoved = (-dragAccumulator / tickWidth).toInt()
                        if (ticksMoved != 0) {
                            val newIndex = (selectedIndex + ticksMoved).coerceIn(0, items.size - 1)
                            if (newIndex != selectedIndex) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onItemSelected(newIndex, items[newIndex])
                                dragAccumulator = 0f
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                // Render neighboring tick marks
                val visibleCount = 18
                for (offset in -visibleCount..visibleCount) {
                    val idx = selectedIndex + offset
                    if (idx in items.indices) {
                        val tickX = cx + (offset * tickWidth.dp.toPx() / 2f)
                        val isCenter = offset == 0
                        val isMajor = idx % 5 == 0 || isCenter
                        val isMedium = idx % 2 == 0

                        val tickH = when {
                            isCenter -> h * 0.75f
                            isMajor -> h * 0.50f
                            isMedium -> h * 0.35f
                            else -> h * 0.20f
                        }

                        val tickColor = when {
                            isCenter -> Color(0xFFFFCC00)
                            isMajor -> Color(0xCCFFFFFF)
                            isMedium -> Color(0x77FFFFFF)
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
                    strokeWidth = 2.5.dp.toPx()
                )

                // Top notch triangle
                val trianglePath = Path().apply {
                    moveTo(cx - 5.dp.toPx(), 0f)
                    lineTo(cx + 5.dp.toPx(), 0f)
                    lineTo(cx, 6.dp.toPx())
                    close()
                }
                drawPath(trianglePath, Color(0xFFFFCC00))
            }
        }
    }
}
