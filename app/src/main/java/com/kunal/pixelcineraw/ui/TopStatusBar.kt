package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.*
import com.kunal.pixelcineraw.ui.theme.*
import java.util.Locale

private val PillCorner = 6.dp
private val TabularTextStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v1.5.0 Cinema Status Pill Bar (Blackmagic Camera / Arri Alexa / Apple Pro precision).
 *
 * Groups telemetry into crisp matte-obsidian pills with 0.75dp hairline borders,
 * Warm Tungsten Amber (#F5A623) active accents, Arri Crimson (#FF2D3B) tally,
 * and tabular monospace figures (tnum) across all live counters.
 */
@Composable
fun TopStatusBar(
    state: CinemaState,
    onResolutionSelected: (CustomResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onColorProfileClick: () -> Unit,
    onColorProfileSelected: (ColorProfile) -> Unit = {},
    onIntraModeSelected: (IntraFrameMode) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onIspClick: () -> Unit,
    onAudioWavClick: () -> Unit,
    onAdvancedSettingsClick: () -> Unit,
    onStabilizationClick: () -> Unit,
    onOrientationLockClick: () -> Unit,
    onAntiBandingClick: () -> Unit = {},
    onAntiBandingModeSelected: (AntiBandingMode) -> Unit = {},
    onAeMeteringClick: () -> Unit = {},
    onAeMeteringModeSelected: (AeMeteringMode) -> Unit = {},
    onAutoShutterClick: () -> Unit = {},
    onResetDefaults: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showFormatPopover by remember { mutableStateOf(false) }
    var showCodecPopover by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaPillBgDark)
            .drawBehind {
                drawLine(
                    color = CinemaBorderInactive,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.75.dp.toPx()
                )
            }
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Format, Profile, Codec, ISP, Stabilizer, Orientation, Flicker
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val resTitle = when {
                state.videoResolution.width >= 3800 || state.videoResolution.height >= 2160 -> "4K"
                state.videoResolution.width in 2400..3200 || state.videoResolution.height in 1400..1800 -> "2.5K"
                state.videoResolution.width >= 1920 -> "1080p"
                else -> "${state.videoResolution.width}p"
            }

            // 1. Format & Res/FPS Pill
            Box {
                CinemaPill(
                    label = "FORMAT",
                    value = "$resTitle · ${state.targetFps}p",
                    subText = state.videoResolution.aspectLabel,
                    isActive = true,
                    onClick = {
                        showCodecPopover = false
                        showFormatPopover = true
                    }
                )
                DropdownMenu(
                    expanded = showFormatPopover,
                    onDismissRequest = { showFormatPopover = false },
                    shape = RoundedCornerShape(10.dp),
                    containerColor = Color(0xF20B0C0E),
                    border = BorderStroke(0.75.dp, CinemaBorderActive),
                    modifier = Modifier.width(320.dp)
                ) {
                    FormatPopoverContent(
                        state = state,
                        onResolutionSelected = { res ->
                            onResolutionSelected(res)
                            showFormatPopover = false
                        },
                        onFpsSelected = { fps ->
                            onFpsSelected(fps)
                            showFormatPopover = false
                        },
                        onColorProfileSelected = { profile ->
                            onColorProfileSelected(profile)
                            showFormatPopover = false
                        },
                        onAntiBandingSelected = { mode ->
                            onAntiBandingModeSelected(mode)
                            showFormatPopover = false
                        },
                        onAeMeteringModeSelected = { mode ->
                            onAeMeteringModeSelected(mode)
                            showFormatPopover = false
                        },
                        onOpenAdvanced = {
                            showFormatPopover = false
                            onAdvancedSettingsClick()
                        },
                        onResetDefaults = {
                            showFormatPopover = false
                            onResetDefaults()
                        }
                    )
                }
            }

            // 2. Color Profile Pill
            val profileDotColor = when (state.colorProfile) {
                ColorProfile.HLG_10BIT -> CinemaGold
                ColorProfile.HDR10_PLUS -> CinemaHdrPink
                ColorProfile.FLAT_LOG -> CinemaAmber
                ColorProfile.REC709 -> CinemaAutoGreen
            }
            CinemaPill(
                label = "PROFILE",
                value = state.colorProfile.label,
                dotColor = profileDotColor,
                isActive = state.colorProfile != ColorProfile.REC709,
                onClick = onColorProfileClick
            )

            // 3. Codec & Bitrate Pill
            val codecLabel = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) "ALL-I" else "GOP"
            Box {
                CinemaPill(
                    label = "CODEC",
                    value = "$codecLabel · ${state.targetBitrateMbps}M",
                    subText = if (state.videoCodec == VideoCodec.HEVC_10BIT) "10B" else "8B",
                    isActive = false,
                    onClick = {
                        showFormatPopover = false
                        showCodecPopover = true
                    }
                )
                DropdownMenu(
                    expanded = showCodecPopover,
                    onDismissRequest = { showCodecPopover = false },
                    shape = RoundedCornerShape(10.dp),
                    containerColor = Color(0xF20B0C0E),
                    border = BorderStroke(0.75.dp, CinemaBorderActive),
                    modifier = Modifier.width(310.dp)
                ) {
                    CodecPopoverContent(
                        state = state,
                        onIntraModeSelected = { mode ->
                            onIntraModeSelected(mode)
                            showCodecPopover = false
                        },
                        onBitrateSelected = { mbps ->
                            onBitrateSelected(mbps)
                            showCodecPopover = false
                        }
                    )
                }
            }

            // 4. ISP / Stab / Orient / Flicker / Meter
            CinemaPill(
                label = "ISP",
                value = state.ispMode.name,
                isActive = state.ispMode == IspMode.CLEAN,
                onClick = onIspClick
            )

            val stabDot = when (state.stabilizationMode) {
                StabilizationMode.OFF -> CinemaGray
                StabilizationMode.OIS -> CinemaCyan
                StabilizationMode.OIS_EIS -> CinemaAutoGreen
            }
            CinemaPill(
                label = "STAB",
                value = state.stabilizationMode.shortLabel,
                dotColor = stabDot,
                isActive = state.stabilizationMode != StabilizationMode.OFF,
                onClick = onStabilizationClick
            )

            val isOrientLocked = state.orientationLockMode != OrientationLockMode.AUTO
            val orientIcon = when (state.orientationLockMode) {
                OrientationLockMode.AUTO -> Icons.Default.ScreenRotation
                OrientationLockMode.LOCK_LANDSCAPE -> Icons.Default.StayCurrentLandscape
                OrientationLockMode.LOCK_PORTRAIT -> Icons.Default.StayCurrentPortrait
            }
            CinemaPill(
                label = "ORIENT",
                value = state.orientationLockMode.shortLabel,
                icon = orientIcon,
                isActive = isOrientLocked,
                onClick = onOrientationLockClick
            )

            CinemaPill(
                label = "FLICKER",
                value = state.antiBandingMode.shortLabel,
                isActive = state.antiBandingMode != AntiBandingMode.OFF,
                onClick = onAntiBandingClick
            )

            CinemaPill(
                label = "METER",
                value = state.aeMeteringMode.shortLabel,
                isActive = state.aeMeteringMode == AeMeteringMode.CENTER,
                onClick = onAeMeteringClick
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right: [Timecode 00:00:00] + [Audio Stereo Mini-Meter] + [System Health Pill]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.isNdNeeded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(4.dp.toPx())
                            drawRoundRect(color = Color(0x28F5A623), cornerRadius = r)
                            drawRoundRect(color = CinemaAmber, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                        }
                        .clickable { onAutoShutterClick() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "ND Needed", tint = CinemaAmber, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "ND",
                        color = CinemaAmber,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // [Timecode 00:00:00] with Arri Tally Crimson dot when recording
            CinemaTimecodePill(
                isRecording = state.isRecording,
                isPaused = state.isRecordingPaused,
                recordingSeconds = state.recordingTimeSeconds
            )

            // [Audio Stereo Mini-Meter]
            AudioStereoMiniPill(
                recordWav = state.recordWavSidecar,
                isExternalMic = state.isExternalMicConnected,
                peakL = state.audioPeakDbL,
                peakR = state.audioPeakDbR,
                onClick = onAudioWavClick
            )

            // [System Health Pill]: Storage + Battery % + conditional WARM/HOT badge
            SystemHealthPill(
                storageGb = state.remainingStorageGb,
                remainingMinutes = state.remainingRecordingMinutes,
                batteryPercent = state.batteryPercent,
                thermalStatus = state.thermalStatus
            )
        }
    }
}

@Composable
fun CinemaTimecodePill(
    isRecording: Boolean,
    isPaused: Boolean,
    recordingSeconds: Long,
    modifier: Modifier = Modifier
) {
    val hours = recordingSeconds / 3600L
    val mins = (recordingSeconds % 3600L) / 60L
    val secs = recordingSeconds % 60L
    val timecode = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

    val infiniteTransition = rememberInfiniteTransition(label = "TallyPulse")
    val tallyAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TallyAlpha"
    )

    val borderProgress by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "TimecodeBorder"
    )

    Row(
        modifier = modifier
            .drawBehind {
                val r = CornerRadius(PillCorner.toPx())
                drawRoundRect(color = CinemaPillBg, cornerRadius = r)
                val borderColor = if (isRecording) {
                    CinemaCrimson.copy(alpha = 0.35f + 0.45f * borderProgress)
                } else {
                    CinemaBorderInactive
                }
                drawRoundRect(color = borderColor, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    alpha = if (isRecording && !isPaused) tallyAlpha else if (isRecording) 0.5f else 0.35f
                }
                .background(
                    if (isRecording) CinemaCrimson else CinemaGrayDeep,
                    CircleShape
                )
        )
        Text(
            text = if (isPaused) "PAUSED" else timecode,
            color = if (isRecording) CinemaCrimson else CinemaWhite,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularTextStyle
        )
    }
}

@Composable
fun AudioStereoMiniPill(
    recordWav: Boolean,
    isExternalMic: Boolean,
    peakL: Float,
    peakR: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .drawBehind {
                val r = CornerRadius(PillCorner.toPx())
                drawRoundRect(color = CinemaPillBg, cornerRadius = r)
                val strokeColor = if (recordWav) CinemaAmber.copy(alpha = 0.45f) else CinemaBorderInactive
                drawRoundRect(color = strokeColor, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isExternalMic) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "USB-C Mic",
                tint = CinemaCyan,
                modifier = Modifier.size(11.dp)
            )
        }
        Text(
            text = if (recordWav) "WAV" else "AAC",
            color = if (recordWav) CinemaAmber else CinemaWhite,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        CompactStereoVuMeter(peakL = peakL, peakR = peakR)
    }
}

@Composable
fun SystemHealthPill(
    storageGb: Float,
    remainingMinutes: Int,
    batteryPercent: Int,
    thermalStatus: ThermalStatus,
    modifier: Modifier = Modifier
) {
    val battColor = when {
        batteryPercent < 15 -> CinemaCrimson
        batteryPercent < 30 -> CinemaAmber
        else -> CinemaAutoGreen
    }

    Row(
        modifier = modifier
            .drawBehind {
                val r = CornerRadius(PillCorner.toPx())
                drawRoundRect(color = CinemaPillBg, cornerRadius = r)
                drawRoundRect(color = CinemaBorderInactive, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "${storageGb.toInt()}G·${remainingMinutes}m",
            color = CinemaGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            style = TabularTextStyle
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (batteryPercent < 15) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                contentDescription = "Battery",
                tint = battColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "$batteryPercent%",
                color = CinemaWhite,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )
        }

        ThermalIndicator(status = thermalStatus)
    }
}

@Composable
fun CinemaPill(
    label: String,
    value: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subText: String? = null,
    dotColor: Color? = null,
    icon: ImageVector? = null
) {
    val activeAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "pillActive"
    )

    Column(
        modifier = modifier
            .drawBehind {
                val r = CornerRadius(PillCorner.toPx())
                drawRoundRect(color = CinemaPillBg, cornerRadius = r)
                val borderCol = Color(
                    red = CinemaBorderInactive.red + (CinemaBorderActive.red - CinemaBorderInactive.red) * activeAlpha,
                    green = CinemaBorderInactive.green + (CinemaBorderActive.green - CinemaBorderInactive.green) * activeAlpha,
                    blue = CinemaBorderInactive.blue + (CinemaBorderActive.blue - CinemaBorderInactive.blue) * activeAlpha,
                    alpha = CinemaBorderInactive.alpha + (CinemaBorderActive.alpha - CinemaBorderInactive.alpha) * activeAlpha
                )
                drawRoundRect(color = borderCol, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = CinemaGrayDeep,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.08.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) CinemaAmber else CinemaWhite,
                    modifier = Modifier.size(9.dp)
                )
                dotColor != null -> Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(dotColor, CircleShape)
                )
            }
            Text(
                text = value,
                color = if (isActive) CinemaAmber else CinemaWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )
            if (subText != null) {
                Text(
                    text = subText,
                    color = CinemaGray,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    style = TabularTextStyle
                )
            }
        }
    }
}

@Composable
internal fun FormatPopoverContent(
    state: CinemaState,
    onResolutionSelected: (CustomResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onColorProfileSelected: (ColorProfile) -> Unit = {},
    onAntiBandingSelected: (AntiBandingMode) -> Unit,
    onAeMeteringModeSelected: (AeMeteringMode) -> Unit = {},
    onOpenAdvanced: () -> Unit,
    onResetDefaults: () -> Unit
) {
    val currentTier = when {
        state.videoResolution.width >= 3800 || state.videoResolution.height >= 2160 -> "4K"
        state.videoResolution.width in 2400..3200 || state.videoResolution.height in 1400..1800 -> "2.5K"
        else -> "1080p"
    }
    val currentAspect = when {
        kotlin.math.abs(state.videoResolution.aspectRatio - (4f / 3f)) < 0.05f -> "4:3 Open Gate"
        kotlin.math.abs(state.videoResolution.aspectRatio - 2.39f) < 0.08f -> "2.39:1"
        else -> "16:9"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FORMAT & SENSOR",
                color = CinemaAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${state.videoResolution.width}×${state.videoResolution.height}",
                color = CinemaGray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )
        }
        Spacer(Modifier.height(10.dp))

        val resTiers = listOf("4K", "2.5K", "1080p")
        PopoverSegmentedRow(
            title = "RESOLUTION",
            items = resTiers,
            selectedIndex = resTiers.indexOf(currentTier).coerceAtLeast(0)
        ) { idx ->
            onResolutionSelected(resolveCinemaResolution(resTiers[idx], currentAspect, state.supportedResolutions))
        }
        Spacer(Modifier.height(7.dp))

        val fpsOptions = listOf(24, 30, 60)
        PopoverSegmentedRow(
            title = "FRAME RATE",
            items = listOf("24 fps", "30 fps", "60 fps"),
            selectedIndex = fpsOptions.indexOf(state.targetFps).coerceAtLeast(0)
        ) { idx ->
            onFpsSelected(fpsOptions[idx])
        }
        Spacer(Modifier.height(7.dp))

        val profiles = listOf(
            ColorProfile.HLG_10BIT,
            ColorProfile.HDR10_PLUS,
            ColorProfile.FLAT_LOG,
            ColorProfile.REC709
        )
        PopoverSegmentedRow(
            title = "COLOR PROFILE",
            items = listOf("HLG10", "HDR10+", "LOG", "REC.709"),
            selectedIndex = profiles.indexOf(state.colorProfile).coerceAtLeast(0)
        ) { idx ->
            onColorProfileSelected(profiles[idx])
        }
        Spacer(Modifier.height(7.dp))

        val aspectOptions = listOf("16:9", "4:3 Open Gate", "2.39:1")
        PopoverSegmentedRow(
            title = "ASPECT RATIO",
            items = aspectOptions,
            selectedIndex = aspectOptions.indexOf(currentAspect).coerceAtLeast(0)
        ) { idx ->
            onResolutionSelected(resolveCinemaResolution(currentTier, aspectOptions[idx], state.supportedResolutions))
        }
        Spacer(Modifier.height(7.dp))

        val antiBandingModes = listOf(AntiBandingMode.HZ_50, AntiBandingMode.HZ_60, AntiBandingMode.AUTO, AntiBandingMode.OFF)
        PopoverSegmentedRow(
            title = "ANTI-BANDING",
            items = listOf("50Hz", "60Hz", "AUTO", "OFF"),
            selectedIndex = antiBandingModes.indexOf(state.antiBandingMode).coerceAtLeast(0)
        ) { idx ->
            onAntiBandingSelected(antiBandingModes[idx])
        }
        Spacer(Modifier.height(7.dp))

        val meteringModes = listOf(AeMeteringMode.CENTER, AeMeteringMode.MATRIX)
        PopoverSegmentedRow(
            title = "AE METERING",
            items = listOf("CENTER", "MATRIX"),
            selectedIndex = meteringModes.indexOf(state.aeMeteringMode).coerceAtLeast(0)
        ) { idx ->
            onAeMeteringModeSelected(meteringModes[idx])
        }
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "RESET DEFAULTS",
                color = CinemaCrimson,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onResetDefaults() }.padding(4.dp)
            )
            Text(
                text = "ADVANCED...",
                color = CinemaCyan,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onOpenAdvanced() }.padding(4.dp)
            )
        }
    }
}

@Composable
internal fun CodecPopoverContent(
    state: CinemaState,
    onIntraModeSelected: (IntraFrameMode) -> Unit,
    onBitrateSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CODEC & BITRATE",
                color = CinemaAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = state.encoderChromaBadge,
                color = CinemaGray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                style = TabularTextStyle
            )
        }
        Spacer(Modifier.height(10.dp))

        val modeOptions = listOf(IntraFrameMode.ALL_INTRA, IntraFrameMode.GOP_LONG)
        PopoverSegmentedRow(
            title = "COMPRESSION",
            items = listOf("ALL-I (Max Quality)", "GOP (Smaller)"),
            selectedIndex = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) 0 else 1
        ) { idx ->
            onIntraModeSelected(modeOptions[idx])
        }
        Spacer(Modifier.height(7.dp))

        val bitrateOptions = listOf(80, 100, 120, 160)
        val selectedBitrateIdx = when {
            state.targetBitrateMbps <= 90 -> 0
            state.targetBitrateMbps in 91..110 -> 1
            state.targetBitrateMbps in 111..140 -> 2
            else -> 3
        }
        PopoverSegmentedRow(
            title = "BITRATE (MBPS)",
            items = listOf("80M", "100M", "120M", "160M"),
            selectedIndex = selectedBitrateIdx
        ) { idx ->
            onBitrateSelected(bitrateOptions[idx])
        }
    }
}

@Composable
private fun PopoverSegmentedRow(
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
                .background(Color(0x33000000), RoundedCornerShape(7.dp))
                .border(0.75.dp, CinemaBorderInactive, RoundedCornerShape(7.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isSelected) CinemaAmber else Color.Transparent, RoundedCornerShape(5.dp))
                        .clickable { onItemSelected(index) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item,
                        color = if (isSelected) CinemaObsidian else CinemaWhite,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        style = TabularTextStyle,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

fun resolveCinemaResolution(tier: String, aspect: String, supported: List<CustomResolution>): CustomResolution {
    val targetRatio = when (aspect) {
        "4:3 Open Gate" -> 4f / 3f
        "2.39:1" -> 2.39f
        else -> 16f / 9f
    }
    val matchingAspect = supported.filter { kotlin.math.abs(it.aspectRatio - targetRatio) < 0.05f }
    if (matchingAspect.isNotEmpty()) {
        val match = when (tier) {
            "4K" -> matchingAspect.filter { it.width >= 3800 || it.height >= 2160 }.maxByOrNull { it.width * it.height } ?: matchingAspect.maxByOrNull { it.width * it.height }
            "2.5K" -> matchingAspect.filter { it.width in 2400..3200 || it.height in 1400..1800 }.minByOrNull { kotlin.math.abs(it.width - 2560) } ?: matchingAspect.minByOrNull { kotlin.math.abs(it.width - 2560) }
            "1080p" -> matchingAspect.filter { it.width in 1800..2048 || it.height in 1000..1200 }.minByOrNull { kotlin.math.abs(it.width - 1920) } ?: matchingAspect.minByOrNull { kotlin.math.abs(it.width - 1920) }
            else -> matchingAspect.firstOrNull()
        }
        if (match != null) return match
    }
    return when (aspect) {
        "2.39:1" -> when (tier) {
            "4K" -> CustomResolution.fromSize(3840, 1608)
            "2.5K" -> CustomResolution.fromSize(2560, 1072)
            else -> CustomResolution.fromSize(1920, 804)
        }
        "4:3 Open Gate" -> when (tier) {
            "4K" -> supported.filter { kotlin.math.abs(it.aspectRatio - (4f / 3f)) < 0.05f }.maxByOrNull { it.width * it.height } ?: CustomResolution.fromSize(4032, 3024)
            "2.5K" -> CustomResolution.fromSize(2560, 1920)
            else -> CustomResolution.fromSize(1920, 1440)
        }
        else -> when (tier) {
            "4K" -> CustomResolution.UHD_4K
            "2.5K" -> CustomResolution.QHD_1440P
            else -> CustomResolution.FHD_1080P
        }
    }
}

@Composable
fun ThermalIndicator(status: ThermalStatus) {
    if (status == ThermalStatus.NOMINAL) return

    val (color, label) = when (status) {
        ThermalStatus.LIGHT, ThermalStatus.MODERATE -> Pair(CinemaAmber, "WARM")
        ThermalStatus.SEVERE, ThermalStatus.EMERGENCY -> Pair(CinemaCrimson, "HOT")
        ThermalStatus.NOMINAL -> return
    }

    val pulse by animateFloatAsState(
        targetValue = if (status == ThermalStatus.SEVERE || status == ThermalStatus.EMERGENCY) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.65f),
        label = "thermalPulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .drawBehind {
                val r = CornerRadius(4.dp.toPx())
                drawRoundRect(color = CinemaPillBg, cornerRadius = r)
                drawRoundRect(color = color.copy(alpha = 0.55f), cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            color = color,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun CompactStereoVuMeter(peakL: Float, peakR: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(36.dp).height(10.dp)) {
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
