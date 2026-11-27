package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.*
import com.kunal.pixelcineraw.ui.theme.*
import java.util.Locale

private val TabularTextStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v2.0.0 Unified Dynamic-Island-Style Top Telemetry Capsule.
 *
 * - Idle: Centered obsidian glass capsule (`#0D0F12` @ 74%, CircleShape, 0.5dp top specular rim)
 *   showing `[4K · 60 · HLG10 | 180°]` + battery/thermal dot. Tapping toggles the floating
 *   Format & Color Quick-Sheet.
 * - Recording: Morphs smoothly into a Cinema Tally Capsule with pulsing crimson tally dot,
 *   tabular timecode, live Canvas stereo peak bars, and remaining GB/minutes.
 */
@Composable
fun DynamicIslandTelemetryCapsule(
    state: CinemaState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    recordingSeconds: Long,
    peakL: Float,
    peakR: Float,
    onAutoShutterClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isRec = state.isRecording

    val recProgress by animateFloatAsState(
        targetValue = if (isRec) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "capsuleRecMorph"
    )
    val chevronRot by animateFloatAsState(
        targetValue = if (isExpanded && !isRec) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "capsuleChevron"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "CapsuleTallyPulse")
    val tallyAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CapsuleTallyAlpha"
    )

    val resTitle = when {
        state.videoResolution.width >= 3800 || state.videoResolution.height >= 2160 -> "4K"
        state.videoResolution.width in 2400..3200 || state.videoResolution.height in 1400..1800 -> "2.5K"
        state.videoResolution.width >= 1920 -> "1080p"
        else -> "${state.videoResolution.width}p"
    }

    val profileShort = when (state.colorProfile) {
        ColorProfile.HLG_10BIT -> "HLG10"
        ColorProfile.HDR10_PLUS -> "HDR10+"
        ColorProfile.FLAT_LOG -> "LOG"
        ColorProfile.REC709 -> "709"
    }
    val profileDotColor = when (state.colorProfile) {
        ColorProfile.HLG_10BIT -> CinemaGold
        ColorProfile.HDR10_PLUS -> CinemaHdrPink
        ColorProfile.FLAT_LOG -> CinemaAmber
        ColorProfile.REC709 -> CinemaAutoGreen
    }

    val shutterShort = if (state.shutterUnit == ShutterUnit.ANGLE) {
        "${state.shutterAngle.toInt()}°"
    } else {
        ColorMath.formatShutterSpeed(state.shutterSpeedNs)
    }

    val thermalBatteryDot = when {
        state.thermalStatus == ThermalStatus.SEVERE || state.thermalStatus == ThermalStatus.EMERGENCY -> CinemaCrimson
        state.thermalStatus == ThermalStatus.LIGHT || state.thermalStatus == ThermalStatus.MODERATE -> CinemaAmber
        state.batteryPercent < 15 -> CinemaCrimson
        state.batteryPercent < 30 -> CinemaAmber
        else -> CinemaAutoGreen
    }

    val hours = recordingSeconds / 3600L
    val mins = (recordingSeconds % 3600L) / 60L
    val secs = recordingSeconds % 60L
    val timecode = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                val bg = Color(
                    red = CinemaCapsuleBg.red + (CinemaTallyBg.red - CinemaCapsuleBg.red) * recProgress,
                    green = CinemaCapsuleBg.green + (CinemaTallyBg.green - CinemaCapsuleBg.green) * recProgress,
                    blue = CinemaCapsuleBg.blue + (CinemaTallyBg.blue - CinemaCapsuleBg.blue) * recProgress,
                    alpha = CinemaCapsuleBg.alpha + (CinemaTallyBg.alpha - CinemaCapsuleBg.alpha) * recProgress
                )
                drawRoundRect(color = bg, cornerRadius = r)

                val topEdge = if (isRec) CinemaCrimson.copy(alpha = 0.55f) else if (isExpanded) CinemaAmber.copy(alpha = 0.55f) else CinemaSpecularTop
                val botEdge = if (isRec) CinemaCrimson.copy(alpha = 0.16f) else CinemaSpecularBottom
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(topEdge, botEdge)),
                    cornerRadius = r,
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isRec) onToggleExpand()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isRec) {
            // Recording Cinema Tally Capsule
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = if (!state.isRecordingPaused) tallyAlpha else 0.45f }
                    .drawBehind { drawCircle(CinemaCrimson) }
            )

            Text(
                text = if (state.isRecordingPaused) "PAUSED" else timecode,
                color = if (state.isRecordingPaused) CinemaAmber else CinemaWhite,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )

            CapsuleDivider()

            CompactStereoVuMeter(peakL = peakL, peakR = peakR)

            CapsuleDivider()

            Text(
                text = "${state.remainingStorageGb.toInt()}G·${state.remainingRecordingMinutes}m",
                color = CinemaGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .drawBehind { drawCircle(thermalBatteryDot) }
            )
        } else {
            // Idle Dynamic-Island Telemetry Pill: [4K · 60 · HLG10 | 180°] + battery/thermal dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "$resTitle · ${state.targetFps} ·",
                    color = CinemaWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = TabularTextStyle
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .drawBehind { drawCircle(profileDotColor) }
                )
                Text(
                    text = profileShort,
                    color = if (state.colorProfile == ColorProfile.REC709) CinemaWhite else profileDotColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    style = TabularTextStyle
                )
            }

            CapsuleDivider()

            Text(
                text = shutterShort,
                color = if (state.isManualExposure) CinemaAmber else CinemaAutoGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )

            if (state.isNdNeeded) {
                Text(
                    text = "ND",
                    color = CinemaAmber,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable { onAutoShutterClick() }
                        .padding(horizontal = 2.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .drawBehind { drawCircle(thermalBatteryDot) }
                )
                if (state.thermalStatus != ThermalStatus.NOMINAL) {
                    Text(
                        text = if (state.thermalStatus.isWarning) "HOT" else "WARM",
                        color = thermalBatteryDot,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Format Sheet",
                    tint = CinemaGray,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = chevronRot }
                )
            }
        }
    }
}

@Composable
private fun CapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(12.dp)
            .drawBehind { drawRect(Color(0x28FFFFFF)) }
    )
}

/**
 * Floating Glass Format & Color Quick-Sheet expanded from the Top Dynamic Island Capsule.
 */
@Composable
fun FormatColorQuickSheet(
    state: CinemaState,
    onResolutionSelected: (CustomResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onColorProfileSelected: (ColorProfile) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onIntraModeSelected: (IntraFrameMode) -> Unit,
    onIspToggle: () -> Unit,
    onStabilizationCycle: () -> Unit,
    onAudioWavToggle: () -> Unit,
    onOrientationLockCycle: () -> Unit,
    onOpenProSuite: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTier = when {
        state.videoResolution.width >= 3800 || state.videoResolution.height >= 2160 -> "4K"
        state.videoResolution.width in 2400..3200 || state.videoResolution.height in 1400..1800 -> "2.5K"
        else -> "1080p"
    }
    val currentAspect = when {
        kotlin.math.abs(state.videoResolution.aspectRatio - (4f / 3f)) < 0.05f -> "4:3"
        kotlin.math.abs(state.videoResolution.aspectRatio - 2.00f) < 0.06f -> "2.00:1"
        kotlin.math.abs(state.videoResolution.aspectRatio - 1.00f) < 0.05f -> "1:1"
        kotlin.math.abs(state.videoResolution.aspectRatio - 1.50f) < 0.05f -> "3:2"
        else -> "16:9"
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxSheetHeight = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
        500.dp
    } else {
        250.dp
    }

    Column(
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .clip(RoundedCornerShape(24.dp))
            .drawBehind {
                val r = CornerRadius(24.dp.toPx())
                drawRoundRect(color = CinemaGlassSheetBg, cornerRadius = r)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(CinemaSpecularTop, CinemaSpecularBottom)),
                    cornerRadius = r,
                    style = Stroke(0.5.dp.toPx())
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header telemetry line
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FORMAT & COLOR PIPELINE",
                color = CinemaAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.08.sp
            )
            Text(
                text = "${state.videoResolution.width}×${state.videoResolution.height} · ${state.batteryPercent}%",
                color = CinemaGray,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )
        }

        // 1. Resolution
        val resTiers = listOf("4K", "2.5K", "1080p")
        QuickSheetSegmentedRow(
            title = "RESOLUTION",
            items = resTiers,
            selectedIndex = resTiers.indexOf(currentTier).coerceAtLeast(0)
        ) { idx ->
            onResolutionSelected(resolveCinemaResolution(resTiers[idx], currentAspect, state.supportedResolutions))
        }

        // 2. Aspect Ratio
        val aspectOptions = listOf("16:9", "4:3", "2.00:1", "1:1", "3:2")
        QuickSheetSegmentedRow(
            title = "ASPECT RATIO",
            items = aspectOptions,
            selectedIndex = aspectOptions.indexOf(currentAspect).coerceAtLeast(0)
        ) { idx ->
            onResolutionSelected(resolveCinemaResolution(currentTier, aspectOptions[idx], state.supportedResolutions))
        }

        // 2. Frame Rate
        val fpsOptions = listOf(24, 30, 60)
        QuickSheetSegmentedRow(
            title = "FRAME RATE",
            items = listOf("24 fps", "30 fps", "60 fps"),
            selectedIndex = fpsOptions.indexOf(state.targetFps).coerceAtLeast(0)
        ) { idx ->
            onFpsSelected(fpsOptions[idx])
        }

        // 3. Color Profile
        val profiles = listOf(
            ColorProfile.HLG_10BIT,
            ColorProfile.HDR10_PLUS,
            ColorProfile.FLAT_LOG,
            ColorProfile.REC709
        )
        QuickSheetSegmentedRow(
            title = "COLOR PROFILE",
            items = listOf("HLG10", "HDR10+", "LOG", "REC.709"),
            selectedIndex = profiles.indexOf(state.colorProfile).coerceAtLeast(0)
        ) { idx ->
            onColorProfileSelected(profiles[idx])
        }

        // 4. Bitrate + Compression
        val bitrateOptions = listOf(80, 120, 180, 240)
        val selectedBitrateIdx = when {
            state.targetBitrateMbps <= 100 -> 0
            state.targetBitrateMbps in 101..150 -> 1
            state.targetBitrateMbps in 151..210 -> 2
            else -> 3
        }
        QuickSheetSegmentedRow(
            title = "BITRATE (${state.encoderChromaBadge})",
            items = listOf("80M", "120M", "180M", "240M"),
            selectedIndex = selectedBitrateIdx
        ) { idx ->
            onBitrateSelected(bitrateOptions[idx])
        }

        // 5. Compact Hardware & Audio Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickChip(
                label = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) "ALL-I" else "GOP",
                isActive = state.intraFrameMode == IntraFrameMode.ALL_INTRA,
                onClick = {
                    val next = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) {
                        IntraFrameMode.GOP_LONG
                    } else {
                        IntraFrameMode.ALL_INTRA
                    }
                    onIntraModeSelected(next)
                },
                modifier = Modifier.weight(1f)
            )
            QuickChip(
                label = "ISP:${state.ispMode.name}",
                isActive = state.ispMode == IspMode.CLEAN,
                onClick = onIspToggle,
                modifier = Modifier.weight(1.1f)
            )
            QuickChip(
                label = state.stabilizationMode.shortLabel,
                isActive = state.stabilizationMode != StabilizationMode.OFF,
                onClick = onStabilizationCycle,
                modifier = Modifier.weight(1f)
            )
            QuickChip(
                label = if (state.recordWavSidecar) "WAV" else "AAC",
                isActive = state.recordWavSidecar,
                onClick = onAudioWavToggle,
                modifier = Modifier.weight(0.9f)
            )
        }

        // 6. Footer Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ORIENT: ${state.orientationLockMode.shortLabel}",
                color = if (state.orientationLockMode != OrientationLockMode.AUTO) CinemaAmber else CinemaGray,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClick = onOrientationLockCycle)
                    .padding(vertical = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "RESET",
                    color = CinemaCrimson,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable(onClick = onResetDefaults)
                        .padding(vertical = 4.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onOpenProSuite)
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Pro Suite",
                        tint = CinemaCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "PRO SUITE",
                        color = CinemaCyan,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "ALL SETTINGS",
                    color = CinemaAmber,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable(onClick = onOpenAllSettings)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickSheetSegmentedRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = CinemaGray,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val r = CornerRadius(10.dp.toPx())
                    drawRoundRect(color = Color(0x4D000000), cornerRadius = r)
                    drawRoundRect(color = CinemaSpecularBottom, cornerRadius = r, style = Stroke(0.5.dp.toPx()))
                }
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .drawBehind {
                            if (isSelected) {
                                drawRoundRect(color = CinemaAmber, cornerRadius = CornerRadius(7.dp.toPx()))
                            }
                        }
                        .clickable { onItemSelected(index) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item,
                        color = if (isSelected) CinemaObsidian else CinemaWhite,
                        fontSize = 9.5.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        style = TabularTextStyle,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                val r = CornerRadius(8.dp.toPx())
                drawRoundRect(
                    color = if (isActive) CinemaAmber.copy(alpha = 0.20f) else Color(0x4D000000),
                    cornerRadius = r
                )
                drawRoundRect(
                    color = if (isActive) CinemaAmber.copy(alpha = 0.7f) else CinemaSpecularBottom,
                    cornerRadius = r,
                    style = Stroke(0.5.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) CinemaAmber else CinemaWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

fun resolveCinemaResolution(tier: String, aspect: String, supported: List<CustomResolution>): CustomResolution {
    val targetRatio = when (aspect) {
        "4:3", "4:3 Open Gate" -> 4f / 3f
        "2.00:1" -> 2.00f
        "1:1" -> 1.00f
        "3:2" -> 1.50f
        "2.39:1" -> 2.39f
        else -> 16f / 9f
    }
    val matchingAspect = supported.filter { kotlin.math.abs(it.aspectRatio - targetRatio) < 0.05f }
    if (matchingAspect.isNotEmpty()) {
        val match = when (tier) {
            "4K" -> matchingAspect.filter { it.width >= 3800 || it.height >= 2160 }.maxByOrNull { it.width * it.height }
            "2.5K" -> matchingAspect.filter { it.width in 2400..3200 || it.height in 1400..1800 }.minByOrNull { kotlin.math.abs(it.width - 2560) }
            "1080p" -> matchingAspect.filter { it.width in 1200..2160 && it.height in 960..1440 }.minByOrNull { kotlin.math.abs(it.height - 1080) }
            else -> null
        }
        if (match != null) return match
    }
    return when (aspect) {
        "2.39:1" -> when (tier) {
            "4K" -> CustomResolution.fromSize(3840, 1608)
            "2.5K" -> CustomResolution.fromSize(2560, 1072)
            else -> CustomResolution.fromSize(1920, 804)
        }
        "2.00:1" -> when (tier) {
            "4K" -> CustomResolution.fromSize(3840, 1920)
            "2.5K" -> CustomResolution.fromSize(2880, 1440)
            else -> CustomResolution.fromSize(2160, 1080)
        }
        "1:1" -> when (tier) {
            "4K" -> CustomResolution.fromSize(2160, 2160)
            "2.5K" -> CustomResolution.fromSize(1440, 1440)
            else -> CustomResolution.fromSize(1080, 1080)
        }
        "3:2" -> when (tier) {
            "4K" -> CustomResolution.fromSize(3240, 2160)
            "2.5K" -> CustomResolution.fromSize(2400, 1600)
            else -> CustomResolution.fromSize(1920, 1280)
        }
        "4:3", "4:3 Open Gate" -> when (tier) {
            "4K" -> supported.filter { kotlin.math.abs(it.aspectRatio - (4f / 3f)) < 0.05f && (it.width >= 3000 || it.height >= 2160) }
                .maxByOrNull { it.width * it.height } ?: CustomResolution.fromSize(4032, 3024)
            "2.5K" -> CustomResolution.fromSize(2560, 1920)
            else -> CustomResolution.fromSize(1440, 1080)
        }
        else -> when (tier) {
            "4K" -> CustomResolution.UHD_4K
            "2.5K" -> CustomResolution.QHD_1440P
            else -> CustomResolution.FHD_1080P
        }
    }
}

@Composable
fun CompactStereoVuMeter(peakL: Float, peakR: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(34.dp).height(10.dp)) {
        val w = size.width
        val barH = size.height / 2f - 1f
        val r = CornerRadius(1.5.dp.toPx())

        drawRoundRect(color = Color(0x28FFFFFF), size = Size(w, barH), cornerRadius = r)
        drawRoundRect(
            color = Color(0x28FFFFFF),
            topLeft = Offset(0f, barH + 2f),
            size = Size(w, barH),
            cornerRadius = r
        )

        fun dbColor(db: Float) = when {
            db > -6f -> CinemaCrimson
            db > -18f -> CinemaAmber
            else -> CinemaAutoGreen
        }
        val normL = ((peakL + 60f) / 60f).coerceIn(0.04f, 1f)
        val normR = ((peakR + 60f) / 60f).coerceIn(0.04f, 1f)
        drawRoundRect(color = dbColor(peakL), size = Size(w * normL, barH), cornerRadius = r)
        drawRoundRect(
            color = dbColor(peakR),
            topLeft = Offset(0f, barH + 2f),
            size = Size(w * normR, barH),
            cornerRadius = r
        )
    }
}
