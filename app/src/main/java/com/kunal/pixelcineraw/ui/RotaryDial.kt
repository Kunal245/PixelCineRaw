package com.kunal.pixelcineraw.ui

import android.util.Range
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val TabularDialStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

enum class ActiveDial {
    NONE,
    FPS,
    SHUTTER,
    ISO,
    FOCUS,
    WHITE_BALANCE,
    EV,
    BITRATE,
    LUT,
    TOOLS
}

/**
 * v1.5.0 Cinema Rotary Dial / Horizontal Scrubber.
 *
 * Matte Obsidian (#EB0B0C0E), 0.75dp hairline borders, Warm Tungsten Amber (#F5A623)
 * active accents, tabular monospace figures (tnum), and inertial fling decay.
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
    gearRatio: Float = 3.5f,
    presets: List<Pair<String, Int>> = emptyList(),
    headerActions: @Composable RowScope.() -> Unit = {}
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragAccumulator by remember(selectedIndex) { mutableFloatStateOf(0f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val tickWidth = with(density) { (16.dp * gearRatio).toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaGlassSheetBg, RoundedCornerShape(22.dp))
            .border(0.5.dp, CinemaSpecularTop, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.uppercase(),
                    color = CinemaAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                headerActions()
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dial",
                    tint = CinemaWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 2. Central Readout with Tabular Figures
        val currentItem = items.getOrNull(selectedIndex)
        Text(
            text = if (currentItem != null) formatItem(currentItem) else "--",
            color = CinemaWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularDialStyle
        )

        // 3. Quick-Jump Preset Buttons
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
                            .drawBehind {
                                val r = CornerRadius(5.dp.toPx())
                                drawRoundRect(color = if (isSelected) CinemaAmber else CinemaElevated, cornerRadius = r)
                                drawRoundRect(
                                    color = if (isSelected) CinemaAmber else CinemaBorderInactive,
                                    cornerRadius = r,
                                    style = Stroke(0.75.dp.toPx())
                                )
                            }
                            .clickable {
                                flingJob?.cancel()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onItemSelected(targetIdx, items[targetIdx])
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) CinemaObsidian else CinemaWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = TabularDialStyle
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
                .height(46.dp)
                .background(Color(0x66060709), RoundedCornerShape(12.dp))
                .border(0.5.dp, CinemaSpecularBottom, RoundedCornerShape(12.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        flingJob?.cancel()
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
                    },
                    onDragStopped = { velocity ->
                        if (abs(velocity) > 150f && items.size > 1) {
                            flingJob = coroutineScope.launch {
                                var lastAnim = 0f
                                var currentIdx = selectedIndex
                                Animatable(0f).animateDecay(
                                    initialVelocity = -velocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 2.0f)
                                ) {
                                    val step = value - lastAnim
                                    lastAnim = value
                                    dragAccumulator += step
                                    val ticks = (dragAccumulator / tickWidth).toInt()
                                    if (ticks != 0) {
                                        val newIdx = (currentIdx + ticks).coerceIn(0, items.size - 1)
                                        if (newIdx != currentIdx) {
                                            currentIdx = newIdx
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            onItemSelected(newIdx, items[newIdx])
                                            dragAccumulator = 0f
                                        }
                                    }
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                val visibleCount = 18
                for (offset in -visibleCount..visibleCount) {
                    val idx = selectedIndex + offset
                    if (idx in items.indices) {
                        val tickX = cx + (offset * tickWidth / 2f)
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
                            isCenter -> CinemaAmber
                            isMajor -> CinemaWhite.copy(alpha = 0.8f)
                            isMedium -> CinemaWhite.copy(alpha = 0.45f)
                            else -> CinemaWhite.copy(alpha = 0.20f)
                        }

                        drawLine(
                            color = tickColor,
                            start = Offset(tickX, (h - tickH) / 2f),
                            end = Offset(tickX, (h + tickH) / 2f),
                            strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                        )
                    }
                }

                drawLine(
                    color = CinemaAmber,
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = 2.dp.toPx()
                )

                val trianglePath = Path().apply {
                    moveTo(cx - 4.5.dp.toPx(), 0f)
                    lineTo(cx + 4.5.dp.toPx(), 0f)
                    lineTo(cx, 5.5.dp.toPx())
                    close()
                }
                drawPath(trianglePath, CinemaAmber)
            }
        }
    }
}

/**
 * Cinema Non-Linear Follow-Focus Wheel Scrubber.
 */
@Composable
fun CinemaFocusWheelScrubber(
    currentFocusDistance: Float,
    maxDiopters: Float = 10.0f,
    onFocusDistanceChanged: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {}
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val barrelWidthPx = with(density) { 1400.dp.toPx() }

    val safeMaxD = max(0.1f, maxDiopters)
    val currentProgress = (currentFocusDistance / safeMaxD).coerceIn(0f, 1f).pow(1f / 2.4f)
    var dragProgress by remember(currentFocusDistance) { mutableFloatStateOf(currentProgress) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val presetMarks = remember(safeMaxD) {
        listOf(
            "∞ INF" to 0.0f,
            "5.0m" to 0.2f,
            "2.5m" to 0.4f,
            "1.5m" to 0.67f,
            "1.0m" to 1.0f,
            "0.5m" to 2.0f,
            "0.2m" to 5.0f,
            "MACRO" to safeMaxD
        )
    }

    val barrelMarks = remember(safeMaxD) {
        listOf(
            0.0f to "∞",
            0.2f to "5.0m",
            0.4f to "2.5m",
            0.67f to "1.5m",
            1.0f to "1.0m",
            1.43f to "0.7m",
            2.0f to "0.5m",
            3.33f to "0.3m",
            5.0f to "0.2m",
            safeMaxD to "0.1m"
        ).map { (diopters, label) ->
            val p = (diopters / safeMaxD).coerceIn(0f, 1f).pow(1f / 2.4f)
            Triple(diopters, label, p)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaGlassSheetBg, RoundedCornerShape(22.dp))
            .border(0.5.dp, CinemaSpecularTop, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MANUAL FOLLOW FOCUS",
                    color = CinemaAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                headerActions()
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dial",
                    tint = CinemaWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val readoutText = when {
            currentFocusDistance <= 0.05f -> "∞ INFINITY"
            currentFocusDistance >= safeMaxD - 0.1f -> "0.10m MACRO"
            else -> String.format(Locale.US, "%.2fm", 1.0f / currentFocusDistance)
        }
        Text(
            text = readoutText,
            color = CinemaWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularDialStyle
        )

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presetMarks.forEach { (label, targetDiopters) ->
                val isSelected = abs(currentFocusDistance - targetDiopters) < 0.15f ||
                        (targetDiopters == 0.0f && currentFocusDistance <= 0.05f) ||
                        (targetDiopters == safeMaxD && currentFocusDistance >= safeMaxD - 0.2f)
                Box(
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(5.dp.toPx())
                            drawRoundRect(color = if (isSelected) CinemaAmber else CinemaElevated, cornerRadius = r)
                            drawRoundRect(
                                color = if (isSelected) CinemaAmber else CinemaBorderInactive,
                                cornerRadius = r,
                                style = Stroke(0.75.dp.toPx())
                            )
                        }
                        .clickable {
                            flingJob?.cancel()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            dragProgress = (targetDiopters / safeMaxD).coerceIn(0f, 1f).pow(1f / 2.4f)
                            onFocusDistanceChanged(targetDiopters)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) CinemaObsidian else CinemaWhite,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = TabularDialStyle
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        var lastHapticMarkIdx by remember { mutableIntStateOf(-1) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(CinemaObsidian, RoundedCornerShape(8.dp))
                .border(0.75.dp, CinemaBorderInactive, RoundedCornerShape(8.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        flingJob?.cancel()
                        val deltaP = -delta / barrelWidthPx
                        dragProgress = (dragProgress + deltaP).coerceIn(0f, 1f)
                        val newDiopters = safeMaxD * dragProgress.pow(2.4f)
                        onFocusDistanceChanged(newDiopters)

                        val nearestMark = barrelMarks.indexOfFirst { abs(it.third - dragProgress) < 0.015f }
                        if (nearestMark != -1 && nearestMark != lastHapticMarkIdx) {
                            lastHapticMarkIdx = nearestMark
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (nearestMark == -1) {
                            lastHapticMarkIdx = -1
                        }
                    },
                    onDragStopped = { velocity ->
                        if (abs(velocity) > 100f) {
                            flingJob = coroutineScope.launch {
                                var lastAnimVal = 0f
                                Animatable(0f).animateDecay(
                                    initialVelocity = -velocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 2.0f)
                                ) {
                                    val step = value - lastAnimVal
                                    lastAnimVal = value
                                    dragProgress = (dragProgress + step / barrelWidthPx).coerceIn(0f, 1f)
                                    val d = safeMaxD * dragProgress.pow(2.4f)
                                    onFocusDistanceChanged(d)
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                barrelMarks.forEach { (_, label, p) ->
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -40f..(size.width + 40f)) {
                        val isCenter = abs(markX - cx) < 6f
                        val tickColor = if (isCenter) CinemaAmber else CinemaWhite.copy(alpha = 0.8f)

                        drawLine(
                            color = tickColor,
                            start = Offset(markX, h * 0.15f),
                            end = Offset(markX, h * 0.70f),
                            strokeWidth = 1.5.dp.toPx()
                        )

                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            markX,
                            h * 0.90f,
                            android.graphics.Paint().apply {
                                color = if (isCenter) android.graphics.Color.rgb(245, 166, 35) else android.graphics.Color.WHITE
                                textSize = 9.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.MONOSPACE
                                isAntiAlias = true
                            }
                        )
                    }
                }

                val subTicks = 100
                for (i in 0..subTicks) {
                    val p = i / subTicks.toFloat()
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -10f..(size.width + 10f)) {
                        val isMajor = barrelMarks.any { abs(it.third - p) < 0.008f }
                        if (!isMajor) {
                            drawLine(
                                color = CinemaWhite.copy(alpha = 0.2f),
                                start = Offset(markX, h * 0.35f),
                                end = Offset(markX, h * 0.55f),
                                strokeWidth = 1.dp.toPx()
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

                val trianglePath = Path().apply {
                    moveTo(cx - 4.5.dp.toPx(), 0f)
                    lineTo(cx + 4.5.dp.toPx(), 0f)
                    lineTo(cx, 5.5.dp.toPx())
                    close()
                }
                drawPath(trianglePath, CinemaAmber)
            }
        }
    }
}

/**
 * Cinema Logarithmic Sub-Stop ISO Sensitivity Wheel Scrubber.
 */
@Composable
fun CinemaIsoWheelScrubber(
    currentIso: Int,
    activeIsoRange: Range<Int>,
    isManualExposure: Boolean,
    isAutoIso: Boolean = false,
    onIsoChanged: (Int) -> Unit,
    onToggleExposureMode: () -> Unit,
    onToggleAutoIso: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current

    var isHiIsoMode by remember { mutableStateOf(currentIso > 3200) }
    val maxAccessibleIso = if (isHiIsoMode) activeIsoRange.upper else minOf(3200, activeIsoRange.upper)
    val minIso = activeIsoRange.lower

    val stopWidthPx = with(density) { 160.dp.toPx() }
    val currentIsoState = rememberUpdatedState(currentIso)
    var lastHapticIso by remember { mutableIntStateOf(currentIso) }

    val standardPresets = if (!isHiIsoMode) {
        listOf(100, 200, 400, 800, 1600, 3200)
    } else {
        listOf(400, 800, 1600, 3200, 6400, 12800)
    }
    val activePresets = standardPresets.filter { it in minIso..maxAccessibleIso }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaGlassSheetBg, RoundedCornerShape(22.dp))
            .border(0.5.dp, CinemaSpecularTop, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ISO SENSITIVITY",
                    color = CinemaAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(4.dp.toPx())
                            drawRoundRect(color = if (isHiIsoMode) CinemaCrimson else CinemaElevated, cornerRadius = r)
                            drawRoundRect(
                                color = if (isHiIsoMode) CinemaCrimson else CinemaBorderInactive,
                                cornerRadius = r,
                                style = Stroke(0.75.dp.toPx())
                            )
                        }
                        .clickable {
                            isHiIsoMode = !isHiIsoMode
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isHiIsoMode) "HI-ISO ON" else "HI-ISO",
                        color = CinemaWhite,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = onToggleExposureMode, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(
                        if (isManualExposure) "SET AUTO" else "SET MANUAL",
                        color = CinemaCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (isManualExposure) {
                    TextButton(onClick = onToggleAutoIso, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(
                            if (isAutoIso) "AUTO ISO" else "SHUTTER-PRI",
                            color = if (isAutoIso) CinemaAutoGreen else CinemaGray,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dial",
                    tint = CinemaWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val readoutText = if (isAutoIso) "AUTO ISO $currentIso" else "ISO $currentIso"
        Text(
            text = readoutText,
            color = if (isAutoIso) CinemaAutoGreen else CinemaWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularDialStyle
        )

        if (activePresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                activePresets.forEach { presetIso ->
                    val isSelected = abs(currentIso - presetIso) <= 5
                    Box(
                        modifier = Modifier
                            .drawBehind {
                                val r = CornerRadius(5.dp.toPx())
                                drawRoundRect(color = if (isSelected) CinemaAmber else CinemaElevated, cornerRadius = r)
                                drawRoundRect(
                                    color = if (isSelected) CinemaAmber else CinemaBorderInactive,
                                    cornerRadius = r,
                                    style = Stroke(0.75.dp.toPx())
                                )
                            }
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onIsoChanged(presetIso)
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$presetIso",
                            color = if (isSelected) CinemaObsidian else CinemaWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = TabularDialStyle
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Color(0x66060709), RoundedCornerShape(12.dp))
                .border(0.5.dp, CinemaSpecularBottom, RoundedCornerShape(12.dp))
                .pointerInput(isHiIsoMode) {
                    var anchorIso = currentIsoState.value.toDouble()
                    var cumulativeDxDp = 0.0

                    detectHorizontalDragGestures(
                        onDragStart = {
                            anchorIso = currentIsoState.value.toDouble()
                            cumulativeDxDp = 0.0
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val deltaDp = (-dragAmount / density.density).toDouble()
                            cumulativeDxDp += deltaDp

                            val targetIso = (anchorIso * 2.0.pow(cumulativeDxDp / 160.0))
                                .roundToInt()
                                .coerceIn(minIso, maxAccessibleIso)

                            if (abs(targetIso - lastHapticIso) >= 5) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastHapticIso = targetIso
                            }
                            onIsoChanged(targetIso)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                val baseStops = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                val safeCurrentIso = maxOf(1f, currentIso.toFloat())

                baseStops.forEach { stopVal ->
                    if (stopVal in (minIso / 2)..(maxAccessibleIso * 2)) {
                        val stopOffsetStops = kotlin.math.ln(stopVal.toDouble() / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                        val markX = cx + (stopOffsetStops * stopWidthPx).toFloat()

                        if (markX in -40f..(size.width + 40f)) {
                            val isCenter = abs(markX - cx) < 6f
                            val tickColor = if (isCenter) CinemaAmber else CinemaWhite.copy(alpha = 0.9f)

                            drawLine(
                                color = tickColor,
                                start = Offset(markX, h * 0.15f),
                                end = Offset(markX, h * 0.70f),
                                strokeWidth = 1.6.dp.toPx()
                            )

                            drawContext.canvas.nativeCanvas.drawText(
                                "$stopVal",
                                markX,
                                h * 0.90f,
                                android.graphics.Paint().apply {
                                    color = if (isCenter) android.graphics.Color.rgb(245, 166, 35) else android.graphics.Color.WHITE
                                    textSize = 9.dp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    typeface = android.graphics.Typeface.MONOSPACE
                                    isAntiAlias = true
                                }
                            )
                        }

                        for (third in 1..2) {
                            val thirdVal = stopVal * 2.0.pow(third / 3.0)
                            if (thirdVal <= maxAccessibleIso * 1.5) {
                                val thirdOffsetStops = kotlin.math.ln(thirdVal / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                                val thirdX = cx + (thirdOffsetStops * stopWidthPx).toFloat()
                                if (thirdX in -10f..(size.width + 10f)) {
                                    drawLine(
                                        color = CinemaWhite.copy(alpha = 0.45f),
                                        start = Offset(thirdX, h * 0.28f),
                                        end = Offset(thirdX, h * 0.58f),
                                        strokeWidth = 1.1.dp.toPx()
                                    )
                                }
                            }
                        }

                        for (sixth in listOf(1, 3, 5)) {
                            val sixthVal = stopVal * 2.0.pow(sixth / 6.0)
                            if (sixthVal <= maxAccessibleIso * 1.5) {
                                val sixthOffsetStops = kotlin.math.ln(sixthVal / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                                val sixthX = cx + (sixthOffsetStops * stopWidthPx).toFloat()
                                if (sixthX in -10f..(size.width + 10f)) {
                                    drawLine(
                                        color = CinemaWhite.copy(alpha = 0.16f),
                                        start = Offset(sixthX, h * 0.38f),
                                        end = Offset(sixthX, h * 0.52f),
                                        strokeWidth = 0.8.dp.toPx()
                                    )
                                }
                            }
                        }
                    }
                }

                drawLine(
                    color = CinemaAmber,
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = 2.dp.toPx()
                )

                val trianglePath = Path().apply {
                    moveTo(cx - 4.5.dp.toPx(), 0f)
                    lineTo(cx + 4.5.dp.toPx(), 0f)
                    lineTo(cx, 5.5.dp.toPx())
                    close()
                }
                drawPath(trianglePath, CinemaAmber)
            }
        }
    }
}

/**
 * Cinema Perceptual Mired / Kelvin White Balance Wheel Scrubber.
 */
@Composable
fun CinemaWhiteBalanceWheelScrubber(
    currentKelvin: Int,
    isManualWb: Boolean,
    onKelvinChanged: (Int) -> Unit,
    onToggleAutoWb: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val barrelWidthPx = with(density) { 1400.dp.toPx() }

    val minMired = 1_000_000f / 8000f
    val maxMired = 1_000_000f / 2300f
    val miredSpan = maxMired - minMired

    fun kelvinToProgress(k: Int): Float {
        val m = 1_000_000f / k.coerceIn(2300, 8000)
        return ((m - minMired) / miredSpan).coerceIn(0f, 1f)
    }

    fun progressToKelvin(p: Float): Int {
        val m = minMired + p.coerceIn(0f, 1f) * miredSpan
        val k = (1_000_000f / m).roundToInt()
        return ((k + 25) / 50 * 50).coerceIn(2300, 8000)
    }

    val currentProgress = kelvinToProgress(currentKelvin)
    var dragProgress by remember(currentKelvin) { mutableFloatStateOf(currentProgress) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val presetMarks = remember {
        listOf(
            "2800K" to 2800,
            "3200K" to 3200,
            "4000K" to 4000,
            "5600K" to 5600,
            "6500K" to 6500,
            "7500K" to 7500
        )
    }

    val barrelMarks = remember {
        listOf(
            2300 to "2300K",
            2800 to "2800K",
            3200 to "3200K",
            4000 to "4000K",
            5000 to "5000K",
            5600 to "5600K",
            6500 to "6500K",
            7500 to "7500K",
            8000 to "8000K"
        ).map { (k, label) ->
            Triple(k, label, kelvinToProgress(k))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaGlassSheetBg, RoundedCornerShape(22.dp))
            .border(0.5.dp, CinemaSpecularTop, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "WHITE BALANCE",
                    color = CinemaAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onToggleAutoWb, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(
                        if (isManualWb) "ENABLE AWB" else "ENABLE MANUAL",
                        color = CinemaCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dial",
                    tint = CinemaWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${currentKelvin}K",
            color = CinemaWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularDialStyle
        )

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presetMarks.forEach { (label, targetKelvin) ->
                val isSelected = abs(currentKelvin - targetKelvin) < 100
                Box(
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(5.dp.toPx())
                            drawRoundRect(color = if (isSelected) CinemaAmber else CinemaElevated, cornerRadius = r)
                            drawRoundRect(
                                color = if (isSelected) CinemaAmber else CinemaBorderInactive,
                                cornerRadius = r,
                                style = Stroke(0.75.dp.toPx())
                            )
                        }
                        .clickable {
                            flingJob?.cancel()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            dragProgress = kelvinToProgress(targetKelvin)
                            onKelvinChanged(targetKelvin)
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) CinemaObsidian else CinemaWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = TabularDialStyle
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        var lastHapticKelvinMark by remember { mutableIntStateOf(-1) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(CinemaObsidian, RoundedCornerShape(8.dp))
                .border(0.75.dp, CinemaBorderInactive, RoundedCornerShape(8.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        flingJob?.cancel()
                        val deltaP = -delta / barrelWidthPx
                        dragProgress = (dragProgress + deltaP).coerceIn(0f, 1f)
                        val newK = progressToKelvin(dragProgress)
                        onKelvinChanged(newK)

                        val nearestMark = barrelMarks.indexOfFirst { abs(it.third - dragProgress) < 0.015f }
                        if (nearestMark != -1 && nearestMark != lastHapticKelvinMark) {
                            lastHapticKelvinMark = nearestMark
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (nearestMark == -1) {
                            lastHapticKelvinMark = -1
                        }
                    },
                    onDragStopped = { velocity ->
                        if (abs(velocity) > 100f) {
                            flingJob = coroutineScope.launch {
                                var lastAnimVal = 0f
                                Animatable(0f).animateDecay(
                                    initialVelocity = -velocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 1.8f)
                                ) {
                                    val step = value - lastAnimVal
                                    lastAnimVal = value
                                    dragProgress = (dragProgress + step / barrelWidthPx).coerceIn(0f, 1f)
                                    val k = progressToKelvin(dragProgress)
                                    onKelvinChanged(k)
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val h = size.height

                barrelMarks.forEach { (_, label, p) ->
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -40f..(size.width + 40f)) {
                        val isCenter = abs(markX - cx) < 6f
                        val tickColor = if (isCenter) CinemaAmber else CinemaWhite.copy(alpha = 0.8f)

                        drawLine(
                            color = tickColor,
                            start = Offset(markX, h * 0.15f),
                            end = Offset(markX, h * 0.70f),
                            strokeWidth = 1.5.dp.toPx()
                        )

                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            markX,
                            h * 0.90f,
                            android.graphics.Paint().apply {
                                color = if (isCenter) android.graphics.Color.rgb(245, 166, 35) else android.graphics.Color.WHITE
                                textSize = 9.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.MONOSPACE
                                isAntiAlias = true
                            }
                        )
                    }
                }

                val subTicks = 80
                for (i in 0..subTicks) {
                    val p = i / subTicks.toFloat()
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -10f..(size.width + 10f)) {
                        val isMajor = barrelMarks.any { abs(it.third - p) < 0.008f }
                        if (!isMajor) {
                            drawLine(
                                color = CinemaWhite.copy(alpha = 0.2f),
                                start = Offset(markX, h * 0.35f),
                                end = Offset(markX, h * 0.55f),
                                strokeWidth = 1.dp.toPx()
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

                val trianglePath = Path().apply {
                    moveTo(cx - 4.5.dp.toPx(), 0f)
                    lineTo(cx + 4.5.dp.toPx(), 0f)
                    lineTo(cx, 5.5.dp.toPx())
                    close()
                }
                drawPath(trianglePath, CinemaAmber)
            }
        }
    }
}
