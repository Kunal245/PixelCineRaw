package com.kunal.pixelcineraw.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.*
import java.util.Locale
import kotlin.math.abs

/**
 * Modern, flagship cinema camera interface (inspired by Blackmagic Camera & Final Cut Camera).
 *
 * Features:
 * - Edge-to-edge #08090C canvas with 90%+ unobstructed viewfinder
 * - One-tap Clean Viewfinder mode (smoothly hides all HUD overlays)
 * - Compact frosted-glass top pill bar with tabular telemetry
 * - Left-side pro assist monitoring rail (Peaking, Zebras, Guides, Horizon Leveler, Histogram, LUT, OIS)
 * - Right-side ergonomic thumb rail (Torch, Shutter, Timecode, MB counter, Pause, Clip preview, AE/AF lock)
 * - Bottom tactile exposure deck (0.5x/1x/2x Lens Hub, SHUTTER, ISO, WB, FOCUS, EV)
 * - Shared high-precision rotary tick wheel with haptic detents
 */
@Composable
fun CinemaScreen(
    viewModel: CinemaViewModel,
    onRecordToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeDial by remember { mutableStateOf(ActiveDial.NONE) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Precision presets
    val fpsValues = remember { listOf(24, 25, 30, 48, 50, 60) }
    val shutterAngles = remember { listOf(11.2f, 22.5f, 45f, 90f, 144f, 172.8f, 180f, 270f, 360f) }
    val shutterSpeedsNs = remember {
        listOf(
            1_000_000_000L / 8000,
            1_000_000_000L / 4000,
            1_000_000_000L / 2000,
            1_000_000_000L / 1000,
            1_000_000_000L / 500,
            1_000_000_000L / 240,
            1_000_000_000L / 120,
            1_000_000_000L / 96,
            1_000_000_000L / 60,
            1_000_000_000L / 50,
            1_000_000_000L / 48,
            1_000_000_000L / 30,
            1_000_000_000L / 24
        )
    }
    val isoValues = remember {
        listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 6400)
    }
    val focusDistances = remember {
        (0..40).map { i -> (i / 40f) * 10f }
    }
    val kelvinValues = remember {
        (2300..8000 step 100).toList()
    }
    val evValues = remember {
        listOf(-2.0f, -1.7f, -1.3f, -1.0f, -0.7f, -0.3f, 0.0f, 0.3f, 0.7f, 1.0f, 1.3f, 1.7f, 2.0f)
    }
    val bitrateValues = remember {
        listOf(50, 80, 100, 120, 150, 180, 200, 240, 280)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (activeDial != ActiveDial.NONE) {
                    activeDial = ActiveDial.NONE
                } else {
                    viewModel.cameraController.toggleCleanViewfinder()
                }
            }
    ) {
        // 1. Live Camera2 Viewfinder Surface with AutoFitSurfaceView (Universal Zero-Stretch)
        Viewfinder(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Monitoring Overlays (Zebras, Peaking, Frame Guides, Horizon Leveler, Histogram)
        val vRes = state.videoResolution
        val resWidth = maxOf(vRes.width, vRes.height)
        val resHeight = minOf(vRes.width, vRes.height)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp, bottom = 54.dp),
            contentAlignment = Alignment.Center
        ) {
            MonitoringOverlays(
                state = state,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(resWidth.toFloat() / resHeight.toFloat(), matchHeightConstraintsFirst = true)
            )
        }

        // 3. Clean Viewfinder Mode Glowing REC Indicator (Visible even when HUD is hidden)
        if (state.isCleanViewfinder && state.isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFFFF0033), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val mins = state.recordingTimeSeconds / 60
                val secs = state.recordingTimeSeconds % 60
                Text(
                    text = "REC %02d:%02d".format(mins, secs),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 4. Modern Frosted Glass Top Bar
        AnimatedVisibility(
            visible = !state.isCleanViewfinder,
            enter = slideInVertically { -it } + fadeIn(tween(150)),
            exit = slideOutVertically { -it } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopStatusBar(
                state = state,
                onFormatClick = { showSettingsDialog = true },
                onColorProfileClick = {
                    val nextProfile = when (state.colorProfile) {
                        ColorProfile.REC709 -> ColorProfile.FLAT_LOG
                        ColorProfile.FLAT_LOG -> ColorProfile.HLG_10BIT
                        ColorProfile.HLG_10BIT -> ColorProfile.HDR10_PLUS
                        ColorProfile.HDR10_PLUS -> ColorProfile.REC709
                    }
                    viewModel.cameraController.setColorProfile(nextProfile)
                },
                onBitrateClick = {
                    activeDial = if (activeDial == ActiveDial.BITRATE) ActiveDial.NONE else ActiveDial.BITRATE
                },
                onIspClick = { viewModel.cameraController.toggleIspMode() },
                onAudioWavClick = { viewModel.cameraController.toggleRecordWavSidecar() }
            )
        }

        // 5. Left-Side Pro Assist Monitoring Rail
        AnimatedVisibility(
            visible = !state.isCleanViewfinder,
            enter = slideInHorizontally { -it } + fadeIn(tween(150)),
            exit = slideOutHorizontally { -it } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            LeftMonitoringRail(
                state = state,
                onCleanViewfinderToggle = { viewModel.cameraController.toggleCleanViewfinder() },
                onPeakingToggle = { viewModel.cameraController.toggleFocusPeaking() },
                onZebrasToggle = { viewModel.cameraController.toggleZebras() },
                onGuidesCycle = {
                    val nextGuide = when (state.frameGuide) {
                        FrameGuide.NONE -> FrameGuide.RATIO_2_39
                        FrameGuide.RATIO_2_39 -> FrameGuide.RATIO_16_9
                        FrameGuide.RATIO_16_9 -> FrameGuide.RATIO_4_3
                        FrameGuide.RATIO_4_3 -> FrameGuide.RATIO_1_1
                        FrameGuide.RATIO_1_1 -> FrameGuide.NONE
                    }
                    viewModel.cameraController.setFrameGuide(nextGuide)
                },
                onHorizonToggle = { viewModel.cameraController.toggleHorizonLeveler() },
                onHistogramToggle = { viewModel.cameraController.toggleHistogram() },
                onLutToggle = { viewModel.cameraController.toggleLutPreview() },
                onStabilizationToggle = { viewModel.cameraController.toggleOis() }
            )
        }

        // 6. Right-Side Cinema Action Rail (Thumb Controls)
        AnimatedVisibility(
            visible = !state.isCleanViewfinder,
            enter = slideInHorizontally { it } + fadeIn(tween(150)),
            exit = slideOutHorizontally { it } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            RightActionRail(
                state = state,
                onRecordToggle = onRecordToggle,
                onPauseToggle = {
                    viewModel.cameraController.setRecordingPaused(!state.isRecordingPaused)
                },
                onTorchToggle = { viewModel.cameraController.toggleTorch() },
                onAeAfLockToggle = { viewModel.cameraController.toggleAeAfLock() }
            )
        }

        // 7. Bottom Tactile Exposure Deck & Lens Hub
        AnimatedVisibility(
            visible = !state.isCleanViewfinder,
            enter = slideInVertically { it } + fadeIn(tween(150)),
            exit = slideOutVertically { it } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControlBar(
                state = state,
                onLensSelected = { lens -> viewModel.cameraController.selectLens(lens) },
                onZoomChanged = { zoom -> viewModel.cameraController.setZoomRatio(zoom) },
                onZoomSliderVisibilityChanged = { visible -> viewModel.cameraController.setZoomSliderVisibility(visible) },
                onShutterClick = {
                    activeDial = if (activeDial == ActiveDial.SHUTTER) ActiveDial.NONE else ActiveDial.SHUTTER
                },
                onIsoClick = {
                    activeDial = if (activeDial == ActiveDial.ISO) ActiveDial.NONE else ActiveDial.ISO
                },
                onWbClick = {
                    activeDial = if (activeDial == ActiveDial.WHITE_BALANCE) ActiveDial.NONE else ActiveDial.WHITE_BALANCE
                },
                onFocusClick = {
                    activeDial = if (activeDial == ActiveDial.FOCUS) ActiveDial.NONE else ActiveDial.FOCUS
                },
                onEvClick = {
                    activeDial = if (activeDial == ActiveDial.EV) ActiveDial.NONE else ActiveDial.EV
                }
            )
        }

        // 8. Shared Expandable Rotary Dial Wheel (Pops up directly above the bottom deck)
        AnimatedVisibility(
            visible = activeDial != ActiveDial.NONE && !state.isCleanViewfinder,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 58.dp)
        ) {
            when (activeDial) {
                ActiveDial.FPS -> {
                    val currentFps = state.targetFps
                    val selectedIdx = fpsValues.indexOf(currentFps).coerceAtLeast(0)
                    RotaryWheelScrubber(
                        title = "Frame Rate (FPS)",
                        items = fpsValues,
                        selectedIndex = selectedIdx,
                        onItemSelected = { _, fps -> viewModel.cameraController.setTargetFps(fps) },
                        formatItem = { "$it FPS" },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("24p" to 0, "25p" to 1, "30p" to 2, "48p" to 3, "60p" to 5)
                    )
                }
                ActiveDial.SHUTTER -> {
                    if (state.shutterUnit == ShutterUnit.ANGLE) {
                        val currentAngle = state.shutterAngle
                        val nearestIdx = shutterAngles.indices.minByOrNull { abs(shutterAngles[it] - currentAngle) } ?: 6
                        RotaryWheelScrubber(
                            title = "Shutter Angle",
                            items = shutterAngles,
                            selectedIndex = nearestIdx,
                            onItemSelected = { _, angle -> viewModel.cameraController.setShutterAngle(angle) },
                            formatItem = { "${it.toInt()}°" },
                            onClose = { activeDial = ActiveDial.NONE },
                            presets = listOf("90°" to 3, "144°" to 4, "172.8°" to 5, "180°" to 6, "360°" to 8),
                            headerActions = {
                                TextButton(onClick = { viewModel.cameraController.toggleShutterUnit() }) {
                                    Text("SWITCH TO SPEED", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    } else {
                        val currentNs = state.shutterSpeedNs
                        val nearestIdx = shutterSpeedsNs.indices.minByOrNull { abs(shutterSpeedsNs[it] - currentNs) } ?: 10
                        RotaryWheelScrubber(
                            title = "Shutter Speed",
                            items = shutterSpeedsNs,
                            selectedIndex = nearestIdx,
                            onItemSelected = { _, ns -> viewModel.cameraController.setShutterSpeed(ns) },
                            formatItem = { ColorMath.formatShutterSpeed(it) },
                            onClose = { activeDial = ActiveDial.NONE },
                            presets = listOf("1/24" to 12, "1/48" to 10, "1/50" to 9, "1/60" to 8, "1/120" to 6, "1/1000" to 3),
                            headerActions = {
                                TextButton(onClick = { viewModel.cameraController.toggleShutterUnit() }) {
                                    Text("SWITCH TO ANGLE", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }
                }
                ActiveDial.ISO -> {
                    val currentIso = state.iso
                    val nearestIdx = isoValues.indices.minByOrNull { abs(isoValues[it] - currentIso) } ?: 3
                    RotaryWheelScrubber(
                        title = "ISO Sensitivity",
                        items = isoValues,
                        selectedIndex = nearestIdx,
                        onItemSelected = { _, iso -> viewModel.cameraController.setIso(iso) },
                        formatItem = { "ISO $it" },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("100" to 3, "200" to 6, "400" to 8, "800" to 10, "1600" to 13, "3200" to 18),
                        headerActions = {
                            TextButton(onClick = { viewModel.cameraController.toggleExposureMode() }) {
                                Text(
                                    if (state.isManualExposure) "SET AUTO" else "SET MANUAL",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }
                ActiveDial.FOCUS -> {
                    val currentDist = state.focusDistance
                    val nearestIdx = focusDistances.indices.minByOrNull { abs(focusDistances[it] - currentDist) } ?: 0
                    RotaryWheelScrubber(
                        title = "Manual Focus Wheel",
                        items = focusDistances,
                        selectedIndex = nearestIdx,
                        onItemSelected = { _, dist -> viewModel.cameraController.setFocusDistance(dist) },
                        formatItem = { dist ->
                            if (dist <= 0.05f) "∞ INFINITY"
                            else {
                                val meters = 1.0f / dist
                                String.format(Locale.US, "%.2fm", meters)
                            }
                        },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("∞ INF" to 0, "2.0m" to 10, "1.0m" to 20, "0.5m" to 30, "MACRO" to 40),
                        headerActions = {
                            TextButton(onClick = {
                                viewModel.cameraController.setFocusMode(
                                    if (state.focusMode == FocusMode.MANUAL) FocusMode.AUTO_CONTINUOUS else FocusMode.MANUAL
                                )
                            }) {
                                Text(
                                    if (state.focusMode == FocusMode.MANUAL) "ENABLE AF" else "ENABLE MF",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }
                ActiveDial.WHITE_BALANCE -> {
                    val currentK = state.wbKelvin
                    val nearestIdx = kelvinValues.indices.minByOrNull { abs(kelvinValues[it] - currentK) } ?: 33
                    RotaryWheelScrubber(
                        title = "White Balance Kelvin",
                        items = kelvinValues,
                        selectedIndex = nearestIdx,
                        onItemSelected = { _, k -> viewModel.cameraController.setWhiteBalance(k, state.wbTint) },
                        formatItem = { "${it}K" },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("3200K" to 9, "4000K" to 17, "5600K" to 33, "6500K" to 42, "7500K" to 52),
                        headerActions = {
                            TextButton(onClick = { viewModel.cameraController.toggleAutoWb() }) {
                                Text(
                                    if (state.isManualWb) "ENABLE AWB" else "ENABLE MANUAL",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }
                ActiveDial.EV -> {
                    val currentEv = state.evCompensation
                    val nearestIdx = evValues.indices.minByOrNull { abs(evValues[it] - currentEv) } ?: 6
                    RotaryWheelScrubber(
                        title = "Exposure Compensation (EV)",
                        items = evValues,
                        selectedIndex = nearestIdx,
                        onItemSelected = { _, ev -> viewModel.cameraController.setEvCompensation(ev) },
                        formatItem = { ev ->
                            if (ev > 0f) "+%.1f EV".format(ev) else "%.1f EV".format(ev)
                        },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("-2.0" to 0, "-1.0" to 3, "0.0" to 6, "+1.0" to 9, "+2.0" to 12),
                        headerActions = {
                            TextButton(onClick = { viewModel.cameraController.setEvCompensation(0.0f) }) {
                                Text("RESET 0 EV", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
                ActiveDial.BITRATE -> {
                    val currentBitrate = state.targetBitrateMbps
                    val nearestIdx = bitrateValues.indices.minByOrNull { abs(bitrateValues[it] - currentBitrate) } ?: 8
                    RotaryWheelScrubber(
                        title = "Target Video Bitrate",
                        items = bitrateValues,
                        selectedIndex = nearestIdx,
                        onItemSelected = { _, mbps -> viewModel.cameraController.setBitrateMbps(mbps) },
                        formatItem = { "$it Mbps" },
                        onClose = { activeDial = ActiveDial.NONE },
                        presets = listOf("50M" to 0, "100M" to 2, "150M" to 4, "200M" to 6, "280M" to 8)
                    )
                }
                ActiveDial.NONE -> {}
            }
        }

        // 9. Cinema Settings Dialog (Format, Codec, Bitrate, Profile, Resolution)
        if (showSettingsDialog) {
            CinemaSettingsDialog(
                state = state,
                onDismiss = { showSettingsDialog = false },
                onSelectResolution = { viewModel.cameraController.setVideoResolution(it) },
                onSelectBitrate = { viewModel.cameraController.setBitrateMbps(it) },
                onSelectProfile = { viewModel.cameraController.setColorProfile(it) },
                onSelectFormat = { viewModel.cameraController.setRecordingFormat(it) },
                onSelectCodec = { viewModel.cameraController.setVideoCodec(it) }
            )
        }
    }
}

@Composable
fun CinemaSettingsDialog(
    state: CinemaState,
    onDismiss: () -> Unit,
    onSelectResolution: (CustomResolution) -> Unit,
    onSelectBitrate: (Int) -> Unit,
    onSelectProfile: (ColorProfile) -> Unit,
    onSelectFormat: (RecordingFormat) -> Unit,
    onSelectCodec: (VideoCodec) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "CINEMA RECORDING & FORMAT SETTINGS",
                color = Color(0xFFFFD700),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Recording Container / Pipeline
                Text("RECORDING CONTAINER", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = state.recordingFormat == fmt,
                            onClick = { onSelectFormat(fmt) },
                            label = { Text(fmt.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                // 2. Video Codec (HEVC 10-bit / HEVC 8-bit / H.264)
                if (state.recordingFormat == RecordingFormat.VIDEO_PRO) {
                    Text("VIDEO CODEC (COLOR DEPTH)", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoCodec.entries.forEach { codec ->
                            FilterChip(
                                selected = state.videoCodec == codec,
                                onClick = { onSelectCodec(codec) },
                                label = { Text(codec.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // 3. Sensor Video Resolution & Aspect Ratio (Grouped by Aspect Ratio)
                Text("RESOLUTION & ASPECT RATIO (${state.supportedResolutions.size} AVAILABLE)", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)

                var selectedAspectFilter by remember { mutableStateOf<String?>("ALL") }
                val availableAspects = remember(state.supportedResolutions) {
                    listOf("ALL") + state.supportedResolutions.map { it.aspectLabel }.distinct()
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableAspects.forEach { aspect ->
                        FilterChip(
                            selected = selectedAspectFilter == aspect,
                            onClick = { selectedAspectFilter = aspect },
                            label = { Text(aspect, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                val filteredResolutions = remember(selectedAspectFilter, state.supportedResolutions) {
                    if (selectedAspectFilter == "ALL" || selectedAspectFilter == null) {
                        state.supportedResolutions
                    } else {
                        state.supportedResolutions.filter { it.aspectLabel == selectedAspectFilter }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filteredResolutions.forEach { res ->
                        val isSelected = state.videoResolution.width == res.width && state.videoResolution.height == res.height
                        Surface(
                            onClick = { onSelectResolution(res) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF2A2A10) else Color(0xFF222428),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFFFFD700) else Color(0x22FFFFFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = res.label,
                                        color = if (isSelected) Color(0xFFFFD700) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${res.width} × ${res.height} • Aspect Ratio: ${res.aspectLabel}",
                                        color = Color(0x88FFFFFF),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (isSelected) {
                                    Text("ACTIVE", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }

                // 4. Bitrate (HEVC / H.264)
                if (state.recordingFormat == RecordingFormat.VIDEO_PRO) {
                    Text("TARGET BITRATE", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(50, 100, 150, 200, 240, 280).forEach { mbps ->
                            FilterChip(
                                selected = state.targetBitrateMbps == mbps,
                                onClick = { onSelectBitrate(mbps) },
                                label = { Text("${mbps} Mbps", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // 5. Color Profile
                Text("COLOR PROFILE & TONE CURVE", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorProfile.entries.forEach { prof ->
                        FilterChip(
                            selected = state.colorProfile == prof,
                            onClick = { onSelectProfile(prof) },
                            label = { Text(prof.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("DONE", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF141518),
        shape = RoundedCornerShape(12.dp)
    )
}
