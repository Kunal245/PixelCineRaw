package com.kunal.pixelcineraw.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Full landscape cinema viewfinder and dark HUD with expandable rotary controls.
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

    // Cinema standard presets
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
        // 0.0 (Infinity) to 10.0 (Macro 10cm) in 40 fine steps
        (0..40).map { i -> (i / 40f) * 10f }
    }
    val kelvinValues = remember {
        (2300..8000 step 100).toList()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Tapping anywhere dismisses active dial to keep 85%+ screen unobstructed
                activeDial = ActiveDial.NONE
            }
    ) {
        // 1. Live Camera2 Viewfinder Surface with rotation matrix and aspect ratio fit
        Viewfinder(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Monitoring Overlays (Zebras, Peaking, Frame Guides, Live Movable Histogram)
        MonitoringOverlays(
            state = state,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Top Cinema Status Bar (Tap values to open tactile scrubbers)
        TopStatusBar(
            state = state,
            onFpsClick = {
                activeDial = if (activeDial == ActiveDial.FPS) ActiveDial.NONE else ActiveDial.FPS
            },
            onShutterClick = {
                activeDial = if (activeDial == ActiveDial.SHUTTER) ActiveDial.NONE else ActiveDial.SHUTTER
            },
            onIsoClick = {
                activeDial = if (activeDial == ActiveDial.ISO) ActiveDial.NONE else ActiveDial.ISO
            },
            onWbClick = {
                activeDial = if (activeDial == ActiveDial.WHITE_BALANCE) ActiveDial.NONE else ActiveDial.WHITE_BALANCE
            },
            onColorProfileClick = {
                showSettingsDialog = true
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 4. Bottom Tactile Control Bar
        BottomControlBar(
            state = state,
            onLensSelected = { lens -> viewModel.cameraController.selectLens(lens) },
            onFocusDialClick = {
                activeDial = if (activeDial == ActiveDial.FOCUS) ActiveDial.NONE else ActiveDial.FOCUS
            },
            onToggleLut = { viewModel.cameraController.toggleLutPreview() },
            onTogglePeaking = { viewModel.cameraController.toggleFocusPeaking() },
            onToggleZebras = { viewModel.cameraController.toggleZebras() },
            onToggleHistogram = { viewModel.cameraController.toggleHistogram() },
            onCycleGuide = {
                val nextGuide = when (state.frameGuide) {
                    FrameGuide.NONE -> FrameGuide.RATIO_2_39
                    FrameGuide.RATIO_2_39 -> FrameGuide.RATIO_16_9
                    FrameGuide.RATIO_16_9 -> FrameGuide.RATIO_4_3
                    FrameGuide.RATIO_4_3 -> FrameGuide.NONE
                    else -> FrameGuide.NONE
                }
                viewModel.cameraController.setFrameGuide(nextGuide)
            },
            onToggleStabilization = { viewModel.cameraController.toggleOis() },
            onRecordClick = onRecordToggle,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 5. Expandable Rotary Dial Bottom Sheet Overlay with Haptic Ticks
        AnimatedVisibility(
            visible = activeDial != ActiveDial.NONE,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 68.dp)
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
                        val nearestIdx = shutterAngles.indices.minByOrNull { Math.abs(shutterAngles[it] - currentAngle) } ?: 6
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
                        val nearestIdx = shutterSpeedsNs.indices.minByOrNull { Math.abs(shutterSpeedsNs[it] - currentNs) } ?: 10
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
                    val nearestIdx = isoValues.indices.minByOrNull { Math.abs(isoValues[it] - currentIso) } ?: 3
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
                    val nearestIdx = focusDistances.indices.minByOrNull { Math.abs(focusDistances[it] - currentDist) } ?: 0
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
                    val nearestIdx = kelvinValues.indices.minByOrNull { Math.abs(kelvinValues[it] - currentK) } ?: 33
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
                ActiveDial.NONE -> {}
            }
        }

        // 6. Cinema Settings Dialog (Format, Bitrate, Profile)
        if (showSettingsDialog) {
            CinemaSettingsDialog(
                state = state,
                onDismiss = { showSettingsDialog = false },
                onSelectResolution = { viewModel.cameraController.setVideoResolution(it) },
                onSelectBitrate = { viewModel.cameraController.setBitrateMbps(it) },
                onSelectProfile = { viewModel.cameraController.setColorProfile(it) },
                onSelectFormat = { viewModel.cameraController.setRecordingFormat(it) }
            )
        }
    }
}

@Composable
fun CinemaSettingsDialog(
    state: CinemaState,
    onDismiss: () -> Unit,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectBitrate: (Int) -> Unit,
    onSelectProfile: (ColorProfile) -> Unit,
    onSelectFormat: (RecordingFormat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "CINEMA RECORDING SETTINGS",
                color = Color(0xFFFFD700),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Recording Format
                Text("RECORDING ENGINE", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = state.recordingFormat == fmt,
                            onClick = { onSelectFormat(fmt) },
                            label = { Text(fmt.label, fontSize = 11.sp) }
                        )
                    }
                }

                // Resolution
                Text("RESOLUTION", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoResolution.entries.forEach { res ->
                        FilterChip(
                            selected = state.videoResolution == res,
                            onClick = { onSelectResolution(res) },
                            label = { Text(res.label, fontSize = 11.sp) }
                        )
                    }
                }

                // Bitrate
                Text("BITRATE (HEVC 10-BIT)", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 150, 200).forEach { mbps ->
                        FilterChip(
                            selected = state.targetBitrateMbps == mbps,
                            onClick = { onSelectBitrate(mbps) },
                            label = { Text("${mbps} Mbps", fontSize = 11.sp) }
                        )
                    }
                }

                // Color Profile
                Text("COLOR PROFILE & LOG", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        containerColor = Color(0xFF181818),
        shape = RoundedCornerShape(12.dp)
    )
}
