package com.kunal.pixelcineraw.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Range
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ActiveDial {
    NONE,
    FPS,
    SHUTTER,
    ISO,
    FOCUS,
    WHITE_BALANCE,
    EV,
    BITRATE
}

/**
 * Cinema Rotary Dial / Horizontal Scrubber with tactile tick marks, haptic clicks,
 * monospaced tabular figures, quick-selection presets, and smooth inertial fling.
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
                                flingJob?.cancel()
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

        // 4. Horizontal Tactile Tick Wheel with Inertial Fling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(Color(0xFF08090B), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
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

                // Render neighboring tick marks
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

/**
 * Cinema Non-Linear Follow-Focus Wheel Scrubber.
 *
 * Maps rotary focus throw using a power follow-focus curve (diopters = maxDiopters * progress^2.4),
 * dedicating ~65% of wheel travel to precision infinity-to-1m focus pulls, with smooth macro acceleration,
 * inertial fling decay on release, calibrated real-world distance marks, and haptic detents.
 */
@Composable
fun CinemaFocusWheelScrubber(
    currentFocusDistance: Float, // In diopters (0.0 = infinity, max = macro)
    maxDiopters: Float = 10.0f,
    onFocusDistanceChanged: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {}
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Virtual travel span of the focus barrel in pixels
    val barrelWidthPx = with(density) { 1400.dp.toPx() }

    val safeMaxD = max(0.1f, maxDiopters)
    val currentProgress = (currentFocusDistance / safeMaxD).coerceIn(0f, 1f).pow(1f / 2.4f)
    var dragProgress by remember(currentFocusDistance) { mutableFloatStateOf(currentProgress) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    // Preset distances (distance in meters, diopters = 1/m)
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

    // Distance graduation marks along the barrel: (diopters, label, progress)
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
            .background(Color(0xF2101317), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Bar: Title, AF/MF action, Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MANUAL FOLLOW FOCUS",
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
        val readoutText = when {
            currentFocusDistance <= 0.05f -> "∞ INFINITY"
            currentFocusDistance >= safeMaxD - 0.1f -> "0.10m MACRO"
            else -> {
                val meters = 1.0f / currentFocusDistance
                String.format(Locale.US, "%.2fm", meters)
            }
        }
        Text(
            text = readoutText,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // 3. Quick-Jump Preset Buttons
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presetMarks.forEach { (label, targetDiopters) ->
                val isSelected = abs(currentFocusDistance - targetDiopters) < 0.15f ||
                        (targetDiopters == 0.0f && currentFocusDistance <= 0.05f) ||
                        (targetDiopters == safeMaxD && currentFocusDistance >= safeMaxD - 0.2f)
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) Color(0xFFFFCC00) else Color(0xFF1E2126),
                            RoundedCornerShape(4.dp)
                        )
                        .border(0.5.dp, if (isSelected) Color(0xFFFFCC00) else Color(0x2AFFFFFF), RoundedCornerShape(4.dp))
                        .clickable {
                            flingJob?.cancel()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val targetProgress = (targetDiopters / safeMaxD).coerceIn(0f, 1f).pow(1f / 2.4f)
                            dragProgress = targetProgress
                            onFocusDistanceChanged(targetDiopters)
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
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

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Horizontal Follow-Focus Barrel Wheel
        var lastHapticMarkIdx by remember { mutableIntStateOf(-1) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFF08090B), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        flingJob?.cancel()
                        val deltaP = -delta / barrelWidthPx
                        dragProgress = (dragProgress + deltaP).coerceIn(0f, 1f)
                        val newDiopters = safeMaxD * dragProgress.pow(2.4f)
                        onFocusDistanceChanged(newDiopters)

                        // Check haptic detents
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

                // Draw barrel graduation marks and distance labels
                barrelMarks.forEach { (_, label, p) ->
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -40f..(size.width + 40f)) {
                        val isCenter = abs(markX - cx) < 6f
                        val tickColor = if (isCenter) Color(0xFFFFCC00) else Color(0xCCFFFFFF)

                        drawLine(
                            color = tickColor,
                            start = Offset(markX, h * 0.15f),
                            end = Offset(markX, h * 0.70f),
                            strokeWidth = 1.5.dp.toPx()
                        )

                        // Text distance label
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            markX,
                            h * 0.90f,
                            android.graphics.Paint().apply {
                                color = if (isCenter) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                                textSize = 9.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.MONOSPACE
                                isAntiAlias = true
                            }
                        )
                    }
                }

                // Intermediate ticks
                val subTicks = 100
                for (i in 0..subTicks) {
                    val p = i / subTicks.toFloat()
                    val markX = cx + (p - dragProgress) * barrelWidthPx
                    if (markX in -10f..(size.width + 10f)) {
                        val isMajor = barrelMarks.any { abs(it.third - p) < 0.008f }
                        if (!isMajor) {
                            drawLine(
                                color = Color(0x33FFFFFF),
                                start = Offset(markX, h * 0.35f),
                                end = Offset(markX, h * 0.55f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
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

/**
 * Cinema Logarithmic Sub-Stop ISO Sensitivity Wheel Scrubber.
 *
 * Implements iPhone-smooth continuous sub-stop ISO control with locked 130dp/stop relative dragging
 * (130dp thumb travel = 1 full 2x exposure stop), zero-flash auto-to-manual handoff, standard full-stop
 * presets (100..3200), HI-ISO toggle for 6400+, 120Hz inertial fling decay, and tactile haptic detents.
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
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isHiIsoMode by remember { mutableStateOf(currentIso > 3200) }
    val maxAccessibleIso = if (isHiIsoMode) activeIsoRange.upper else minOf(3200, activeIsoRange.upper)
    val minIso = activeIsoRange.lower

    val stopWidthDp = 130.0 // Exactly 130dp thumb drag per 1 exposure stop (2x)
    val stopWidthPx = with(density) { 130.dp.toPx() }

    var dragStartIso by remember { mutableFloatStateOf(currentIso.toFloat()) }
    var totalDragDeltaDp by remember { mutableDoubleStateOf(0.0) }
    var lastHapticIso by remember { mutableIntStateOf(currentIso) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    // Synchronize dragStartIso when external ISO changes while not dragging
    LaunchedEffect(currentIso) {
        if (flingJob == null) {
            dragStartIso = currentIso.toFloat()
            totalDragDeltaDp = 0.0
        }
    }

    // Standard Cinema Full-Stop Presets
    val standardPresets = if (!isHiIsoMode) {
        listOf(100, 200, 400, 800, 1600, 3200)
    } else {
        listOf(400, 800, 1600, 3200, 6400, 12800)
    }
    val activePresets = standardPresets.filter { it in minIso..maxAccessibleIso }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF2101317), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Bar: Title, HI-ISO, Auto/Manual, Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ISO SENSITIVITY",
                    color = Color(0xFFFFCC00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(10.dp))
                // HI-ISO Toggle Pill
                Box(
                    modifier = Modifier
                        .background(
                            if (isHiIsoMode) Color(0xFFFF2222) else Color(0xFF1E2126),
                            RoundedCornerShape(4.dp)
                        )
                        .border(0.5.dp, if (isHiIsoMode) Color(0xFFFF5555) else Color(0x2AFFFFFF), RoundedCornerShape(4.dp))
                        .clickable {
                            isHiIsoMode = !isHiIsoMode
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isHiIsoMode) "HI-ISO ON" else "HI-ISO OFF",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onToggleExposureMode) {
                    Text(
                        if (isManualExposure) "SET AUTO" else "SET MANUAL",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                if (isManualExposure) {
                    TextButton(onClick = onToggleAutoIso) {
                        Text(
                            if (isAutoIso) "AUTO ISO ON" else "SHUTTER-PRI AUTO",
                            color = if (isAutoIso) Color(0xFF00FF66) else Color(0xAAFFFFFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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

        // 2. Large Central Readout with Sub-Stop ISO Indication
        val readoutText = if (isAutoIso) "AUTO ISO $currentIso" else "ISO $currentIso"
        Text(
            text = readoutText,
            color = if (isAutoIso) Color(0xFF00FF66) else Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // 3. Quick-Jump Full-Stop Presets
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
                            .background(
                                if (isSelected) Color(0xFFFFCC00) else Color(0xFF1E2126),
                                RoundedCornerShape(4.dp)
                            )
                            .border(0.5.dp, if (isSelected) Color(0xFFFFCC00) else Color(0x2AFFFFFF), RoundedCornerShape(4.dp))
                            .clickable {
                                flingJob?.cancel()
                                dragStartIso = presetIso.toFloat()
                                totalDragDeltaDp = 0.0
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onIsoChanged(presetIso)
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$presetIso",
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

        // 4. Horizontal ISO Scrubber Wheel with 130dp/Stop Relative Drag & Inertial Fling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(Color(0xFF08090B), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        flingJob?.cancel()
                        // Negative delta = drag left = increase ISO
                        val deltaDp = (-delta / density.density).toDouble()
                        totalDragDeltaDp += deltaDp

                        // Relative drag formula: targetIso = dragStartIso * 2^(totalDragDeltaDp / 130dp)
                        val calculatedIso = (dragStartIso * 2.0.pow(totalDragDeltaDp / stopWidthDp))
                            .toFloat()
                            .coerceIn(minIso.toFloat(), maxAccessibleIso.toFloat())

                        val roundedIso = calculatedIso.roundToInt()
                        if (abs(roundedIso - lastHapticIso) >= 5) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            lastHapticIso = roundedIso
                        }
                        onIsoChanged(roundedIso)
                    },
                    onDragStopped = { velocity ->
                        if (abs(velocity) > 100f) {
                            flingJob = coroutineScope.launch {
                                var lastAnim = 0f
                                Animatable(0f).animateDecay(
                                    initialVelocity = -velocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 1.6f)
                                ) {
                                    val step = value - lastAnim
                                    lastAnim = value
                                    val stepDp = (step / density.density).toDouble()
                                    totalDragDeltaDp += stepDp

                                    val calculatedIso = (dragStartIso * 2.0.pow(totalDragDeltaDp / stopWidthDp))
                                        .toFloat()
                                        .coerceIn(minIso.toFloat(), maxAccessibleIso.toFloat())

                                    val roundedIso = calculatedIso.roundToInt()
                                    if (abs(roundedIso - lastHapticIso) >= 5) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        lastHapticIso = roundedIso
                                    }
                                    onIsoChanged(roundedIso)
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

                // Draw full stops and 1/3-stop graduation marks along the continuous log2 scale
                val baseStops = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                val safeCurrentIso = maxOf(1f, currentIso.toFloat())

                // Draw full stops
                baseStops.forEach { stopVal ->
                    if (stopVal in (minIso / 2)..(maxAccessibleIso * 2)) {
                        val stopOffsetStops = kotlin.math.ln(stopVal.toDouble() / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                        val markX = cx + (stopOffsetStops * stopWidthPx).toFloat()

                        if (markX in -40f..(size.width + 40f)) {
                            val isCenter = abs(markX - cx) < 6f
                            val tickColor = if (isCenter) Color(0xFFFFCC00) else Color(0xEEFFFFFF)

                            drawLine(
                                color = tickColor,
                                start = Offset(markX, h * 0.15f),
                                end = Offset(markX, h * 0.70f),
                                strokeWidth = 1.8.dp.toPx()
                            )

                            // Full-stop text label (e.g. "100", "200")
                            drawContext.canvas.nativeCanvas.drawText(
                                "$stopVal",
                                markX,
                                h * 0.90f,
                                android.graphics.Paint().apply {
                                    color = if (isCenter) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                                    textSize = 9.dp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    typeface = android.graphics.Typeface.MONOSPACE
                                    isAntiAlias = true
                                }
                            )
                        }

                        // Draw 1/3-stop intermediate ticks (+1/3 stop and +2/3 stop)
                        for (third in 1..2) {
                            val thirdVal = stopVal * 2.0.pow(third / 3.0)
                            if (thirdVal <= maxAccessibleIso * 1.5) {
                                val thirdOffsetStops = kotlin.math.ln(thirdVal / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                                val thirdX = cx + (thirdOffsetStops * stopWidthPx).toFloat()
                                if (thirdX in -10f..(size.width + 10f)) {
                                    drawLine(
                                        color = Color(0x77FFFFFF),
                                        start = Offset(thirdX, h * 0.28f),
                                        end = Offset(thirdX, h * 0.58f),
                                        strokeWidth = 1.2.dp.toPx()
                                    )
                                }
                            }
                        }

                        // Draw fine 1/6-stop micro-ticks
                        for (sixth in listOf(1, 3, 5)) {
                            val sixthVal = stopVal * 2.0.pow(sixth / 6.0)
                            if (sixthVal <= maxAccessibleIso * 1.5) {
                                val sixthOffsetStops = kotlin.math.ln(sixthVal / safeCurrentIso.toDouble()) / kotlin.math.ln(2.0)
                                val sixthX = cx + (sixthOffsetStops * stopWidthPx).toFloat()
                                if (sixthX in -10f..(size.width + 10f)) {
                                    drawLine(
                                        color = Color(0x2AFFFFFF),
                                        start = Offset(sixthX, h * 0.38f),
                                        end = Offset(sixthX, h * 0.52f),
                                        strokeWidth = 0.8.dp.toPx()
                                    )
                                }
                            }
                        }
                    }
                }

                // Center indicator needle (Cinema Gold with top notch)
                drawLine(
                    color = Color(0xFFFFCC00),
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = 2.5.dp.toPx()
                )

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

/**
 * Cinema Perceptual Mired / Kelvin White Balance Wheel Scrubber.
 *
 * Maps color temperature along a perceptually uniform Mired scale (10^6 / K) from 2300K to 8000K,
 * with heavy damped 3.5x precision gear ratio (~1400dp travel span), standard color temperature presets,
 * inertial fling decay on release, and tactile haptic detents.
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

    // Virtual travel span of the white balance barrel in pixels (3.5x precision throw)
    val barrelWidthPx = with(density) { 1400.dp.toPx() }

    // Human perceptual color temperature (Mireds = 10^6 / K):
    // 2300K (434.78 Mireds) to 8000K (125.0 Mireds)
    val minMired = 1_000_000f / 8000f // 125.0f
    val maxMired = 1_000_000f / 2300f // 434.78f
    val miredSpan = maxMired - minMired

    fun kelvinToProgress(k: Int): Float {
        val m = 1_000_000f / k.coerceIn(2300, 8000)
        return ((m - minMired) / miredSpan).coerceIn(0f, 1f)
    }

    fun progressToKelvin(p: Float): Int {
        val m = minMired + p.coerceIn(0f, 1f) * miredSpan
        val k = (1_000_000f / m).roundToInt()
        // Round to nearest 50K for clean tabular figures
        return ((k + 25) / 50 * 50).coerceIn(2300, 8000)
    }

    val currentProgress = kelvinToProgress(currentKelvin)
    var dragProgress by remember(currentKelvin) { mutableFloatStateOf(currentProgress) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val presetMarks = remember {
        listOf(
            "2800K INC" to 2800,
            "3200K TUNG" to 3200,
            "4000K FLOR" to 4000,
            "5600K DAY" to 5600,
            "6500K CLD" to 6500,
            "7500K SHD" to 7500
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
            .background(Color(0xF2101317), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Bar: Title, AWB/Manual, Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "WHITE BALANCE KELVIN",
                    color = Color(0xFFFFCC00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(10.dp))
                TextButton(onClick = onToggleAutoWb) {
                    Text(
                        if (isManualWb) "ENABLE AWB" else "ENABLE MANUAL",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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

        // 2. Large Central Readout
        Text(
            text = "${currentKelvin}K",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // 3. Quick-Jump Presets
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presetMarks.forEach { (label, targetKelvin) ->
                val isSelected = abs(currentKelvin - targetKelvin) < 100
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) Color(0xFFFFCC00) else Color(0xFF1E2126),
                            RoundedCornerShape(4.dp)
                        )
                        .border(0.5.dp, if (isSelected) Color(0xFFFFCC00) else Color(0x2AFFFFFF), RoundedCornerShape(4.dp))
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
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Horizontal White Balance Scrubber Wheel
        var lastHapticKelvinMark by remember { mutableIntStateOf(-1) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFF08090B), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
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
                        val tickColor = if (isCenter) Color(0xFFFFCC00) else Color(0xCCFFFFFF)

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
                                color = if (isCenter) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
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
                                color = Color(0x33FFFFFF),
                                start = Offset(markX, h * 0.35f),
                                end = Offset(markX, h * 0.55f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                }

                // Center indicator needle (Cinema Gold with top notch)
                drawLine(
                    color = Color(0xFFFFCC00),
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = 2.5.dp.toPx()
                )

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

