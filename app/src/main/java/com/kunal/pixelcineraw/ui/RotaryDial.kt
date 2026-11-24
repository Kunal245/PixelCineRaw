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
 * Cinema Logarithmic ISO Sensitivity Wheel Scrubber.
 *
 * Maps logarithmic exposure stops with ~60dp drag throw per exposure stop (20dp per 1/3 EV step),
 * standard presets (100..3200), HI-ISO toggle for 6400+, 120Hz inertial fling decay, and haptics.
 */
@Composable
fun CinemaIsoWheelScrubber(
    currentIso: Int,
    activeIsoRange: Range<Int>,
    isManualExposure: Boolean,
    onIsoChanged: (Int) -> Unit,
    onToggleExposureMode: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isHiIsoMode by remember { mutableStateOf(currentIso > 3200) }

    val allSteps = remember {
        listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
        )
    }

    val maxAccessibleIso = if (isHiIsoMode) activeIsoRange.upper else minOf(3200, activeIsoRange.upper)
    val accessibleIsoValues = remember(activeIsoRange, maxAccessibleIso) {
        val filtered = allSteps.filter { it in activeIsoRange.lower..maxAccessibleIso }.toMutableList()
        if (!filtered.contains(activeIsoRange.lower)) filtered.add(0, activeIsoRange.lower)
        if (!filtered.contains(maxAccessibleIso)) filtered.add(maxAccessibleIso)
        filtered.sorted().distinct()
    }

    val selectedIndex = accessibleIsoValues.indices.minByOrNull { abs(accessibleIsoValues[it] - currentIso) } ?: 0

    // ~60dp drag per exposure stop (each 1/3-stop step is 20dp)
    val stepWidthPx = with(density) { 20.dp.toPx() }
    var dragAccumulator by remember(selectedIndex) { mutableFloatStateOf(0f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    // Quick presets
    val standardPresets = if (!isHiIsoMode) {
        listOf(100, 200, 400, 800, 1600, 3200)
    } else {
        listOf(400, 800, 1600, 3200, 6400, 12800)
    }
    val activePresets = standardPresets
        .filter { it in activeIsoRange.lower..maxAccessibleIso }
        .map { iso ->
            val targetIdx = accessibleIsoValues.indexOf(iso).let {
                if (it >= 0) it else accessibleIsoValues.indices.minByOrNull { idx -> abs(accessibleIsoValues[idx] - iso) } ?: 0
            }
            "$iso" to targetIdx
        }

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
            text = "ISO $currentIso",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        // 3. Quick-Jump Presets
        if (activePresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                activePresets.forEach { (label, targetIdx) ->
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
                                onIsoChanged(accessibleIsoValues[targetIdx])
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

        // 4. Horizontal ISO Scrubber Wheel with Inertial Fling
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
                        val stepsMoved = (-dragAccumulator / stepWidthPx).toInt()
                        if (stepsMoved != 0) {
                            val newIdx = (selectedIndex + stepsMoved).coerceIn(0, accessibleIsoValues.size - 1)
                            if (newIdx != selectedIndex) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onIsoChanged(accessibleIsoValues[newIdx])
                                dragAccumulator = 0f
                            }
                        }
                    },
                    onDragStopped = { velocity ->
                        if (abs(velocity) > 120f && accessibleIsoValues.size > 1) {
                            flingJob = coroutineScope.launch {
                                var lastAnim = 0f
                                var curIdx = selectedIndex
                                Animatable(0f).animateDecay(
                                    initialVelocity = -velocity,
                                    animationSpec = exponentialDecay(frictionMultiplier = 1.8f)
                                ) {
                                    val step = value - lastAnim
                                    lastAnim = value
                                    dragAccumulator += step
                                    val steps = (dragAccumulator / stepWidthPx).toInt()
                                    if (steps != 0) {
                                        val newIdx = (curIdx + steps).coerceIn(0, accessibleIsoValues.size - 1)
                                        if (newIdx != curIdx) {
                                            curIdx = newIdx
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            onIsoChanged(accessibleIsoValues[newIdx])
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
                    if (idx in accessibleIsoValues.indices) {
                        val tickX = cx + (offset * stepWidthPx)
                        val isoVal = accessibleIsoValues[idx]
                        val isCenter = offset == 0
                        val isFullStop = isoVal in listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

                        val tickH = when {
                            isCenter -> h * 0.75f
                            isFullStop -> h * 0.50f
                            else -> h * 0.25f
                        }

                        val tickColor = when {
                            isCenter -> Color(0xFFFFCC00)
                            isFullStop -> Color(0xEEFFFFFF)
                            else -> Color(0x44FFFFFF)
                        }

                        drawLine(
                            color = tickColor,
                            start = Offset(tickX, (h - tickH) / 2f),
                            end = Offset(tickX, (h + tickH) / 2f),
                            strokeWidth = if (isFullStop) 1.5.dp.toPx() else 1.dp.toPx()
                        )
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

