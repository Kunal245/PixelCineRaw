package com.kunal.pixelcineraw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.*

/**
 * Modern dark-glassmorphism top status bar (inspired by Blackmagic Camera & Final Cut Camera).
 *
 * Features separate interactive popover menus for:
 * - FORMAT (Resolution, Frame Rate, Aspect Ratio)
 * - CODEC (Compression Mode, Bitrate)
 */
@Composable
fun TopStatusBar(
    state: CinemaState,
    onResolutionSelected: (CustomResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onColorProfileClick: () -> Unit,
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
            .background(Color(0xB30A0C10))
            .border(width = 0.5.dp, color = Color(0x26FFFFFF))
            .padding(horizontal = 68.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Consolidated Cinema Format & Engine Pills
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Resolution & FPS Pill with Dedicated Format Popover Menu
            val resTitle = when {
                state.videoResolution.width >= 3800 || state.videoResolution.height >= 2160 -> "4K"
                state.videoResolution.width in 2400..3200 || state.videoResolution.height in 1400..1800 -> "2.5K"
                state.videoResolution.width >= 1920 -> "1080p"
                else -> "${state.videoResolution.width}p"
            }

            Box {
                FrostedPill(
                    label = "FORMAT",
                    value = "$resTitle · ${state.targetFps}p",
                    subText = state.videoResolution.aspectLabel,
                    highlight = true,
                    onClick = {
                        showCodecPopover = false
                        showFormatPopover = true
                    }
                )

                DropdownMenu(
                    expanded = showFormatPopover,
                    onDismissRequest = { showFormatPopover = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color(0xF20A0C10),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier.width(310.dp)
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

            // 2. Color Profile Pill (HLG10, HDR10+, PIXEL-LOG, REC.709 with color indicator dot)
            val profileDotColor = when (state.colorProfile) {
                ColorProfile.HLG_10BIT -> Color(0xFFFFD700)
                ColorProfile.HDR10_PLUS -> Color(0xFFFF007F)
                ColorProfile.FLAT_LOG -> Color(0xFF00E5FF)
                ColorProfile.REC709 -> Color(0xFF00FF66)
            }
            FrostedPill(
                label = "PROFILE",
                value = state.colorProfile.label,
                dotColor = profileDotColor,
                highlight = true,
                onClick = onColorProfileClick
            )

            // 3. Codec & Bitrate Pill with Dedicated Codec Popover Menu
            val codecLabel = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) "ALL-I" else "GOP"
            Box {
                FrostedPill(
                    label = "CODEC",
                    value = "$codecLabel · ${state.targetBitrateMbps}M",
                    subText = if (state.videoCodec == VideoCodec.HEVC_10BIT) "10-BIT" else "8-BIT",
                    highlight = false,
                    onClick = {
                        showFormatPopover = false
                        showCodecPopover = true
                    }
                )

                DropdownMenu(
                    expanded = showCodecPopover,
                    onDismissRequest = { showCodecPopover = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color(0xF20A0C10),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
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

            // 4. Clean Cinema ISP Pill
            FrostedPill(
                label = "ISP",
                value = state.ispMode.name,
                highlight = state.ispMode == IspMode.CLEAN,
                onClick = onIspClick
            )

            // 5. Audio Sidecar Pill
            FrostedPill(
                label = "AUDIO",
                value = if (state.recordWavSidecar) "48k WAV" else "AAC",
                highlight = state.recordWavSidecar,
                onClick = onAudioWavClick
            )

            // 6. Stabilization Mode Pill (OFF / OIS / OIS+EIS)
            val stabDotColor = when (state.stabilizationMode) {
                StabilizationMode.OFF -> Color(0xFF888888)
                StabilizationMode.OIS -> Color(0xFF00E5FF)
                StabilizationMode.OIS_EIS -> Color(0xFF00FF66)
            }
            FrostedPill(
                label = "STAB",
                value = state.stabilizationMode.shortLabel,
                dotColor = stabDotColor,
                highlight = state.stabilizationMode != StabilizationMode.OFF,
                onClick = onStabilizationClick
            )

            // 7. Orientation Lock Pill (AUTO / LANDSCAPE / PORTRAIT)
            val isOrientLocked = state.orientationLockMode != OrientationLockMode.AUTO
            val orientIcon = when (state.orientationLockMode) {
                OrientationLockMode.AUTO -> Icons.Default.ScreenRotation
                OrientationLockMode.LOCK_LANDSCAPE -> Icons.Default.StayCurrentLandscape
                OrientationLockMode.LOCK_PORTRAIT -> Icons.Default.StayCurrentPortrait
            }
            FrostedPill(
                label = "ORIENT",
                value = state.orientationLockMode.shortLabel,
                icon = orientIcon,
                highlight = isOrientLocked,
                onClick = onOrientationLockClick
            )

            // 8. Anti-Banding / Flicker Sync Pill (50Hz / 60Hz / AUTO / OFF)
            FrostedPill(
                label = "FLICKER",
                value = state.antiBandingMode.shortLabel,
                highlight = state.antiBandingMode != AntiBandingMode.OFF,
                onClick = onAntiBandingClick
            )

            // 9. AE Metering Pill (CENTER / MATRIX) - mcpro24fps anti-dimming
            FrostedPill(
                label = "METER",
                value = state.aeMeteringMode.shortLabel,
                highlight = state.aeMeteringMode == AeMeteringMode.CENTER,
                onClick = onAeMeteringClick
            )
        }

        // Right: Real Live Telemetry Cluster (Storage, Battery %, Tensor G4 Thermal)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ND Needed Warning Badge (Bright outdoor light + minimum ISO + highlights clipping)
            if (state.isNdNeeded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x33FF9900), RoundedCornerShape(4.dp))
                        .border(0.8.dp, Color(0xFFFF9900), RoundedCornerShape(4.dp))
                        .clickable { onAutoShutterClick() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "ND Needed",
                        tint = Color(0xFFFF9900),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "ND NEEDED · AUTO SHUTTER",
                        color = Color(0xFFFFCC00),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            // USB-C External Mic Indicator
            if (state.isExternalMicConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x2A00E5FF), RoundedCornerShape(4.dp))
                        .border(0.5.dp, Color(0xFF00E5FF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "USB Mic",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "USB-C",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Internal Storage Remaining (GB and time)
            val storageText = "${state.remainingStorageGb.toInt()}GB · ${state.remainingRecordingMinutes}m"
            Text(
                text = storageText,
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )

            // Real Live Battery Percentage
            Row(verticalAlignment = Alignment.CenterVertically) {
                val batteryIcon = if (state.batteryPercent < 15) Icons.Default.BatteryAlert else Icons.Default.BatteryFull
                val batteryColor = when {
                    state.batteryPercent < 15 -> Color(0xFFFF3333)
                    state.batteryPercent < 30 -> Color(0xFFFFD700)
                    else -> Color(0xFF00FF66)
                }

                Icon(
                    imageVector = batteryIcon,
                    contentDescription = "Battery",
                    tint = batteryColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "${state.batteryPercent}%",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Tensor G4 Real Live Thermal Badge
            ThermalIndicator(status = state.thermalStatus)
        }
    }
}

/**
 * Format Popover Menu Content with dedicated segmented chips for Resolution, FPS, and Aspect Ratio.
 */
@Composable
internal fun FormatPopoverContent(
    state: CinemaState,
    onResolutionSelected: (CustomResolution) -> Unit,
    onFpsSelected: (Int) -> Unit,
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
        Math.abs(state.videoResolution.aspectRatio - (4f / 3f)) < 0.05f -> "4:3 Open Gate"
        Math.abs(state.videoResolution.aspectRatio - 2.39f) < 0.08f -> "2.39:1"
        else -> "16:9"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FORMAT & SENSOR",
                color = Color(0xFFFFCC00),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${state.videoResolution.width}×${state.videoResolution.height}",
                color = Color(0x88FFFFFF),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Resolution: 4K | 2.5K | 1080p
        val resTiers = listOf("4K", "2.5K", "1080p")
        val selectedResIdx = resTiers.indexOf(currentTier).coerceAtLeast(0)
        PopoverSegmentedRow(
            title = "RESOLUTION",
            items = resTiers,
            selectedIndex = selectedResIdx,
            onItemSelected = { idx ->
                val newTier = resTiers[idx]
                val res = resolveCinemaResolution(newTier, currentAspect, state.supportedResolutions)
                onResolutionSelected(res)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Frame Rate (FPS): 24 fps | 30 fps | 60 fps
        val fpsOptions = listOf(24, 30, 60)
        val selectedFpsIdx = fpsOptions.indexOf(state.targetFps).let { if (it >= 0) it else 0 }
        PopoverSegmentedRow(
            title = "FRAME RATE (FPS)",
            items = listOf("24 fps", "30 fps", "60 fps"),
            selectedIndex = selectedFpsIdx,
            onItemSelected = { idx ->
                onFpsSelected(fpsOptions[idx])
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Aspect Ratio: 16:9 | 4:3 Open Gate | 2.39:1
        val aspectOptions = listOf("16:9", "4:3 Open Gate", "2.39:1")
        val selectedAspectIdx = aspectOptions.indexOf(currentAspect).let { if (it >= 0) it else 0 }
        PopoverSegmentedRow(
            title = "ASPECT RATIO",
            items = aspectOptions,
            selectedIndex = selectedAspectIdx,
            onItemSelected = { idx ->
                val newAspect = aspectOptions[idx]
                val res = resolveCinemaResolution(currentTier, newAspect, state.supportedResolutions)
                onResolutionSelected(res)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Anti-Banding / Flicker Sync: 50Hz | 60Hz | AUTO | OFF
        val antiBandingModes = listOf(AntiBandingMode.HZ_50, AntiBandingMode.HZ_60, AntiBandingMode.AUTO, AntiBandingMode.OFF)
        val selectedAbIdx = antiBandingModes.indexOf(state.antiBandingMode).coerceAtLeast(0)
        PopoverSegmentedRow(
            title = "ANTI-BANDING (FLICKER SYNC)",
            items = listOf("50Hz", "60Hz", "AUTO", "OFF"),
            selectedIndex = selectedAbIdx,
            onItemSelected = { idx ->
                onAntiBandingSelected(antiBandingModes[idx])
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 5. AE Metering: CENTER | MATRIX (mcpro24fps anti-dimming)
        val meteringModes = listOf(AeMeteringMode.CENTER, AeMeteringMode.MATRIX)
        val selectedMeterIdx = meteringModes.indexOf(state.aeMeteringMode).coerceAtLeast(0)
        PopoverSegmentedRow(
            title = "AE METERING (ANTI-DIMMING)",
            items = listOf("CENTER", "MATRIX"),
            selectedIndex = selectedMeterIdx,
            onItemSelected = { idx ->
                onAeMeteringModeSelected(meteringModes[idx])
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RESET DEFAULTS",
                color = Color(0xFFFF5252),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onResetDefaults() }
                    .padding(4.dp)
            )

            Text(
                text = "ADVANCED PRESETS...",
                color = Color(0xFF00E5FF),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClick = onOpenAdvanced)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Codec Popover Menu Content with dedicated chips for Compression Mode and Bitrate.
 */
@Composable
internal fun CodecPopoverContent(
    state: CinemaState,
    onIntraModeSelected: (IntraFrameMode) -> Unit,
    onBitrateSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CODEC & BITRATE",
                color = Color(0xFFFFCC00),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = state.encoderChromaBadge,
                color = Color(0x88FFFFFF),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Compression Mode: ALL-I (Max Quality) | GOP (Smaller Size)
        val modeOptions = listOf(IntraFrameMode.ALL_INTRA, IntraFrameMode.GOP_LONG)
        val selectedModeIdx = if (state.intraFrameMode == IntraFrameMode.ALL_INTRA) 0 else 1
        PopoverSegmentedRow(
            title = "COMPRESSION MODE",
            items = listOf("ALL-I (Max Quality)", "GOP (Smaller Size)"),
            selectedIndex = selectedModeIdx,
            onItemSelected = { idx ->
                onIntraModeSelected(modeOptions[idx])
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Bitrate Selector: 80M | 100M | 120M | 160M (Tensor G4 optimized)
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
            selectedIndex = selectedBitrateIdx,
            onItemSelected = { idx ->
                onBitrateSelected(bitrateOptions[idx])
            }
        )
    }
}

/**
 * Sleek tactile segmented row for popover options with gold highlight.
 */
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
            color = Color(0x99FFFFFF),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x40000000), RoundedCornerShape(8.dp))
                .border(0.5.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) Color(0xFFFFCC00) else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onItemSelected(index) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item,
                        color = if (isSelected) Color(0xFF08090C) else Color(0xDDFFFFFF),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Resolves optimal CustomResolution based on selected tier and aspect ratio.
 */
fun resolveCinemaResolution(
    tier: String,
    aspect: String,
    supported: List<CustomResolution>
): CustomResolution {
    val targetRatio = when (aspect) {
        "4:3 Open Gate" -> 4f / 3f
        "2.39:1" -> 2.39f
        else -> 16f / 9f
    }

    // 1. Check supported resolutions for close aspect ratio match
    val matchingAspect = supported.filter { Math.abs(it.aspectRatio - targetRatio) < 0.05f }
    if (matchingAspect.isNotEmpty()) {
        val match = when (tier) {
            "4K" -> matchingAspect.filter { it.width >= 3800 || it.height >= 2160 }.maxByOrNull { it.width * it.height }
                ?: matchingAspect.maxByOrNull { it.width * it.height }
            "2.5K" -> matchingAspect.filter { it.width in 2400..3200 || it.height in 1400..1800 }.minByOrNull { Math.abs(it.width - 2560) }
                ?: matchingAspect.minByOrNull { Math.abs(it.width - 2560) }
            "1080p" -> matchingAspect.filter { it.width in 1800..2048 || it.height in 1000..1200 }.minByOrNull { Math.abs(it.width - 1920) }
                ?: matchingAspect.minByOrNull { Math.abs(it.width - 1920) }
            else -> matchingAspect.firstOrNull()
        }
        if (match != null) return match
    }

    // 2. Fallbacks for custom cinema ratios
    return when (aspect) {
        "2.39:1" -> when (tier) {
            "4K" -> CustomResolution.fromSize(3840, 1608)
            "2.5K" -> CustomResolution.fromSize(2560, 1072)
            "1080p" -> CustomResolution.fromSize(1920, 804)
            else -> CustomResolution.fromSize(3840, 1608)
        }
        "4:3 Open Gate" -> when (tier) {
            "4K" -> supported.filter { Math.abs(it.aspectRatio - (4f / 3f)) < 0.05f }.maxByOrNull { it.width * it.height }
                ?: CustomResolution.fromSize(4032, 3024)
            "2.5K" -> CustomResolution.fromSize(2560, 1920)
            "1080p" -> CustomResolution.fromSize(1920, 1440)
            else -> CustomResolution.fromSize(4032, 3024)
        }
        else -> when (tier) {
            "4K" -> CustomResolution.UHD_4K
            "2.5K" -> CustomResolution.QHD_1440P
            "1080p" -> CustomResolution.FHD_1080P
            else -> CustomResolution.UHD_4K
        }
    }
}

@Composable
fun FrostedPill(
    label: String,
    value: String,
    subText: String? = null,
    dotColor: Color? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(Color(0xB312141A), RoundedCornerShape(6.dp))
            .border(
                0.8.dp,
                if (highlight) Color(0xFFFFCC00).copy(alpha = 0.5f) else Color(0x26FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0x88FFFFFF),
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) Color(0xFFFFCC00) else Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            } else if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = value,
                color = if (highlight) Color(0xFFFFCC00) else Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subText != null) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = subText,
                    color = Color(0x99FFFFFF),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ThermalIndicator(status: ThermalStatus) {
    val (dotColor, text) = when (status) {
        ThermalStatus.NOMINAL -> Pair(Color(0xFF00FF66), "COOL")
        ThermalStatus.LIGHT, ThermalStatus.MODERATE -> Pair(Color(0xFFFFCC00), "WARM")
        ThermalStatus.SEVERE, ThermalStatus.EMERGENCY -> Pair(Color(0xFFFF2222), "HOT")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xB312141A), RoundedCornerShape(4.dp))
            .border(0.5.dp, dotColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = text,
            color = dotColor,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
