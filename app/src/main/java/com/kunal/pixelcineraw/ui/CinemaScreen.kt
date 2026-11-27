package com.kunal.pixelcineraw.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.*
import com.kunal.pixelcineraw.ui.theme.*
import kotlin.math.abs

private val TabularScreenStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v2.0.0 Flagship Cinema Camera Interface (Leica Lux / Halide Mark II / Kino).
 *
 * - 100% Edge-to-Edge Full-Bleed Viewfinder behind transparent system bars (zero letterbox bars).
 * - Unified Dynamic-Island-Style Top Telemetry Capsule + Format & Color Quick-Sheet.
 * - Ergonomic 3-Tier Bottom Cinema Deck (Portrait) / Balanced Side + Bottom Deck (Landscape).
 * - All motion runs strictly in `Modifier.graphicsLayer` / `Modifier.drawBehind` with spring
 *   physics (`dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow`) and zero blur shaders.
 */
@Composable
fun CinemaScreen(
    viewModel: CinemaViewModel,
    onRecordToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeDial by remember { mutableStateOf(ActiveDial.NONE) }
    var isTopCapsuleExpanded by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            isTopCapsuleExpanded = false
        }
    }

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
            1_000_000_000L / 100,
            1_000_000_000L / 96,
            1_000_000_000L / 60,
            1_000_000_000L / 50,
            1_000_000_000L / 48,
            1_000_000_000L / 30,
            1_000_000_000L / 25,
            1_000_000_000L / 24
        )
    }
    val evValues = remember {
        listOf(-2.0f, -1.7f, -1.3f, -1.0f, -0.7f, -0.3f, 0.0f, 0.3f, 0.7f, 1.0f, 1.3f, 1.7f, 2.0f)
    }
    val bitrateValues = remember {
        listOf(50, 80, 100, 120, 150, 180, 200, 240, 280)
    }

    // 1Hz recording timer & 12Hz stereo VU UI throttle during recording
    var throttledRecordingSeconds by remember { mutableLongStateOf(state.recordingTimeSeconds) }
    var lastTimerUiUpdateMs by remember { mutableLongStateOf(0L) }
    if (!state.isRecording) {
        throttledRecordingSeconds = 0L
        lastTimerUiUpdateMs = 0L
    } else {
        val nowMs = SystemClock.uptimeMillis()
        if (state.recordingTimeSeconds != throttledRecordingSeconds || nowMs - lastTimerUiUpdateMs >= 1000L) {
            throttledRecordingSeconds = state.recordingTimeSeconds
            lastTimerUiUpdateMs = nowMs
        }
    }

    var throttledAudioPeakL by remember { mutableFloatStateOf(state.audioPeakDbL) }
    var throttledAudioPeakR by remember { mutableFloatStateOf(state.audioPeakDbR) }
    var lastVuUiUpdateMs by remember { mutableLongStateOf(0L) }
    if (!state.isRecording) {
        throttledAudioPeakL = state.audioPeakDbL
        throttledAudioPeakR = state.audioPeakDbR
    } else {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastVuUiUpdateMs >= 83L) {
            throttledAudioPeakL = state.audioPeakDbL
            throttledAudioPeakR = state.audioPeakDbR
            lastVuUiUpdateMs = nowMs
        }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val hudProgress by animateFloatAsState(
        targetValue = if (!state.isCleanViewfinder) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "hudVisibility"
    )
    val quickSheetProgress by animateFloatAsState(
        targetValue = if (isTopCapsuleExpanded && !state.isRecording && !state.isCleanViewfinder) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "quickSheetSpring"
    )
    val dialProgress by animateFloatAsState(
        targetValue = if (activeDial != ActiveDial.NONE && !state.isCleanViewfinder) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "activeDialSpring"
    )
    var lastActiveDial by remember { mutableStateOf(ActiveDial.NONE) }
    if (activeDial != ActiveDial.NONE) {
        lastActiveDial = activeDial
    }

    val drawerProgress by animateFloatAsState(
        targetValue = if (state.isProToolsDrawerOpen) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "proDrawerSlide"
    )

    fun toggleDial(target: ActiveDial) {
        isTopCapsuleExpanded = false
        activeDial = if (activeDial == target) ActiveDial.NONE else target
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. 100% Edge-to-Edge Full-Bleed Viewfinder
        Viewfinder(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            onDismissOverlays = {
                when {
                    state.isProToolsDrawerOpen -> {
                        viewModel.cameraController.setProToolsDrawerOpen(false)
                        true
                    }
                    isTopCapsuleExpanded -> {
                        isTopCapsuleExpanded = false
                        true
                    }
                    activeDial != ActiveDial.NONE -> {
                        activeDial = ActiveDial.NONE
                        true
                    }
                    else -> false
                }
            }
        )

        // 2. Full-Bleed Monitoring Overlays (hidden cleanly until toggled)
        MonitoringOverlays(
            state = state,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Clean Viewfinder Mode Minimal Tally Indicator
        if (state.isCleanViewfinder) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .drawBehind {
                        val r = CornerRadius(14.dp.toPx())
                        drawRoundRect(color = CinemaCapsuleBg, cornerRadius = r)
                        drawRoundRect(
                            brush = Brush.verticalGradient(listOf(CinemaSpecularTop, CinemaSpecularBottom)),
                            cornerRadius = r,
                            style = Stroke(0.5.dp.toPx())
                        )
                    }
                    .clickable { viewModel.cameraController.toggleCleanViewfinder() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.isRecording) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(CinemaCrimson, CircleShape)
                    )
                    val mins = throttledRecordingSeconds / 60
                    val secs = throttledRecordingSeconds % 60
                    Text(
                        text = "%02d:%02d".format(mins, secs),
                        color = CinemaWhite,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        style = TabularScreenStyle
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Show HUD",
                        tint = CinemaAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "HUD",
                        color = CinemaWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 4. Leica Lux / Kino Adaptive Floating Cinema Deck (Portrait 3-Tier vs Landscape Balanced Deck)
        if (hudProgress > 0.01f) {
            if (isPortrait) {
                // PORTRAIT 3-TIER FLOATING BOTTOM CINEMA DECK
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .displayCutoutPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .graphicsLayer {
                            alpha = hudProgress
                            translationY = (1f - hudProgress) * 48.dp.toPx()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1 — Tactile Lens & Vernier Zoom Dial
                    CinemaZoomHub(
                        state = state,
                        onZoomChanged = { zoom -> viewModel.cameraController.setZoomRatio(zoom) },
                        onSliderVisibilityChanged = { visible -> viewModel.cameraController.setZoomSliderVisibility(visible) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2 — 5 Crisp Parameter Pills (ISO, SHUTTER, WB, LUT, MONITOR)
                    CinemaParameterPillStrip(
                        state = state,
                        activeDial = activeDial,
                        onIsoClick = { toggleDial(ActiveDial.ISO) },
                        onShutterClick = { toggleDial(ActiveDial.SHUTTER) },
                        onWbClick = { toggleDial(ActiveDial.WHITE_BALANCE) },
                        onLutClick = { toggleDial(ActiveDial.LUT) },
                        onMonitorClick = { toggleDial(ActiveDial.TOOLS) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Row 3 — Hero Cinema Shutter Row
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        LastClipGalleryButton(
                            state = state,
                            size = 48.dp,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )

                        CinemaShutterButton(
                            isRecording = state.isRecording,
                            onClick = onRecordToggle,
                            modifier = Modifier.align(Alignment.Center),
                            outerSize = 74.dp,
                            innerCircleSize = 54.dp,
                            innerSquareSize = 26.dp
                        )

                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCircleActionButton(
                                isActive = state.isAeAfLocked,
                                activeColor = CinemaCrimson,
                                onClick = { viewModel.cameraController.toggleAeAfLock() }
                            ) {
                                Icon(
                                    imageVector = if (state.isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "AE/AF Lock",
                                    tint = if (state.isAeAfLocked) CinemaCrimson else CinemaWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            if (state.isRecording) {
                                GlassCircleActionButton(
                                    isActive = state.isRecordingPaused,
                                    activeColor = CinemaAmber,
                                    onClick = { viewModel.cameraController.setRecordingPaused(!state.isRecordingPaused) }
                                ) {
                                    Icon(
                                        imageVector = if (state.isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = "Pause/Resume",
                                        tint = if (state.isRecordingPaused) CinemaAmber else CinemaWhite,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                GlassCircleActionButton(
                                    isActive = state.isFrontCamera,
                                    activeColor = CinemaAmber,
                                    onClick = { viewModel.cameraController.flipCamera() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FlipCameraAndroid,
                                        contentDescription = "Flip Camera",
                                        tint = if (state.isFrontCamera) CinemaAmber else CinemaWhite,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Active Vernier Scrubber / LUT / Monitor Popover (Portrait, directly above Row 2)
                if (dialProgress > 0.01f && lastActiveDial != ActiveDial.NONE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .displayCutoutPadding()
                            .padding(start = 14.dp, end = 14.dp, bottom = 198.dp)
                            .graphicsLayer {
                                alpha = dialProgress
                                translationY = (1f - dialProgress) * 18.dp.toPx()
                                scaleY = 0.94f + 0.06f * dialProgress
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                    ) {
                        CinemaActiveDialScrubber(
                            activeDial = lastActiveDial,
                            state = state,
                            viewModel = viewModel,
                            fpsValues = fpsValues,
                            shutterAngles = shutterAngles,
                            shutterSpeedsNs = shutterSpeedsNs,
                            evValues = evValues,
                            bitrateValues = bitrateValues,
                            onSwitchToDial = { activeDial = it },
                            onOpenProSuite = {
                                activeDial = ActiveDial.NONE
                                viewModel.cameraController.setProToolsDrawerOpen(true)
                            },
                            onClose = { activeDial = ActiveDial.NONE }
                        )
                    }
                }
            } else {
                // LANDSCAPE BALANCED SIDE SHUTTER DECK + FLOATING BOTTOM PARAMETER DECK
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 96.dp, bottom = 10.dp)
                        .graphicsLayer {
                            alpha = hudProgress
                            translationY = (1f - hudProgress) * 44.dp.toPx()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CinemaZoomHub(
                        state = state,
                        onZoomChanged = { zoom -> viewModel.cameraController.setZoomRatio(zoom) },
                        onSliderVisibilityChanged = { visible -> viewModel.cameraController.setZoomSliderVisibility(visible) }
                    )

                    CinemaParameterPillStrip(
                        state = state,
                        activeDial = activeDial,
                        onIsoClick = { toggleDial(ActiveDial.ISO) },
                        onShutterClick = { toggleDial(ActiveDial.SHUTTER) },
                        onWbClick = { toggleDial(ActiveDial.WHITE_BALANCE) },
                        onLutClick = { toggleDial(ActiveDial.LUT) },
                        onMonitorClick = { toggleDial(ActiveDial.TOOLS) }
                    )
                }

                // Balanced Right Side Deck (AE/AF Lock + Flip/Pause, 74dp Hero Shutter, Gallery)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .navigationBarsPadding()
                        .displayCutoutPadding()
                        .padding(end = 16.dp, top = 28.dp, bottom = 16.dp)
                        .graphicsLayer {
                            alpha = hudProgress
                            translationX = (1f - hudProgress) * 56.dp.toPx()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassCircleActionButton(
                            isActive = state.isAeAfLocked,
                            activeColor = CinemaCrimson,
                            size = 44.dp,
                            onClick = { viewModel.cameraController.toggleAeAfLock() }
                        ) {
                            Icon(
                                imageVector = if (state.isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "AE/AF Lock",
                                tint = if (state.isAeAfLocked) CinemaCrimson else CinemaWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (state.isRecording) {
                            GlassCircleActionButton(
                                isActive = state.isRecordingPaused,
                                activeColor = CinemaAmber,
                                size = 44.dp,
                                onClick = { viewModel.cameraController.setRecordingPaused(!state.isRecordingPaused) }
                            ) {
                                Icon(
                                    imageVector = if (state.isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Pause/Resume",
                                    tint = if (state.isRecordingPaused) CinemaAmber else CinemaWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        } else {
                            GlassCircleActionButton(
                                isActive = state.isFrontCamera,
                                activeColor = CinemaAmber,
                                size = 44.dp,
                                onClick = { viewModel.cameraController.flipCamera() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlipCameraAndroid,
                                    contentDescription = "Flip Camera",
                                    tint = if (state.isFrontCamera) CinemaAmber else CinemaWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    CinemaShutterButton(
                        isRecording = state.isRecording,
                        onClick = onRecordToggle,
                        outerSize = 74.dp,
                        innerCircleSize = 54.dp,
                        innerSquareSize = 26.dp
                    )

                    LastClipGalleryButton(
                        state = state,
                        size = 48.dp
                    )
                }

                // Active Vernier Scrubber / LUT / Monitor Popover (Landscape, directly above Row 2)
                if (dialProgress > 0.01f && lastActiveDial != ActiveDial.NONE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 24.dp, end = 96.dp, bottom = 110.dp)
                            .widthIn(max = 460.dp)
                            .graphicsLayer {
                                alpha = dialProgress
                                translationY = (1f - dialProgress) * 18.dp.toPx()
                                scaleY = 0.94f + 0.06f * dialProgress
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                    ) {
                        CinemaActiveDialScrubber(
                            activeDial = lastActiveDial,
                            state = state,
                            viewModel = viewModel,
                            fpsValues = fpsValues,
                            shutterAngles = shutterAngles,
                            shutterSpeedsNs = shutterSpeedsNs,
                            evValues = evValues,
                            bitrateValues = bitrateValues,
                            onSwitchToDial = { activeDial = it },
                            onOpenProSuite = {
                                activeDial = ActiveDial.NONE
                                viewModel.cameraController.setProToolsDrawerOpen(true)
                            },
                            onClose = { activeDial = ActiveDial.NONE }
                        )
                    }
                }
            }
        }

        // 5. Unified Dynamic-Island Top Telemetry Capsule + Floating Glass Quick-Sheet (rendered above decks)
        if (hudProgress > 0.01f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .then(if (isPortrait) Modifier.displayCutoutPadding() else Modifier)
                    .padding(top = 8.dp, start = 14.dp, end = 14.dp)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationY = -(1f - hudProgress) * 28.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DynamicIslandTelemetryCapsule(
                    state = state,
                    isExpanded = isTopCapsuleExpanded,
                    onToggleExpand = {
                        activeDial = ActiveDial.NONE
                        isTopCapsuleExpanded = !isTopCapsuleExpanded
                    },
                    recordingSeconds = throttledRecordingSeconds,
                    peakL = throttledAudioPeakL,
                    peakR = throttledAudioPeakR,
                    onAutoShutterClick = { viewModel.cameraController.setAutoShutter() }
                )

                if (quickSheetProgress > 0.01f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FormatColorQuickSheet(
                        state = state,
                        onResolutionSelected = { res -> viewModel.cameraController.setVideoResolution(res) },
                        onFpsSelected = { fps -> viewModel.cameraController.setTargetFps(fps) },
                        onColorProfileSelected = { profile -> viewModel.cameraController.setColorProfile(profile) },
                        onBitrateSelected = { mbps -> viewModel.cameraController.setBitrateMbps(mbps) },
                        onIntraModeSelected = { mode -> viewModel.cameraController.setIntraFrameMode(mode) },
                        onIspToggle = { viewModel.cameraController.toggleIspMode() },
                        onStabilizationCycle = { viewModel.cameraController.cycleStabilizationMode() },
                        onAudioWavToggle = { viewModel.cameraController.toggleRecordWavSidecar() },
                        onOrientationLockCycle = { viewModel.cameraController.cycleOrientationLockMode() },
                        onOpenProSuite = {
                            isTopCapsuleExpanded = false
                            viewModel.cameraController.setProToolsDrawerOpen(true)
                        },
                        onOpenAllSettings = {
                            isTopCapsuleExpanded = false
                            showSettingsDialog = true
                        },
                        onResetDefaults = {
                            isTopCapsuleExpanded = false
                            viewModel.cameraController.resetToDefaults()
                        },
                        modifier = Modifier.graphicsLayer {
                            alpha = quickSheetProgress
                            scaleX = 0.94f + 0.06f * quickSheetProgress
                            scaleY = 0.90f + 0.10f * quickSheetProgress
                            translationY = -(1f - quickSheetProgress) * 14.dp.toPx()
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                    )
                }
            }
        }

        // 6. Pro Cinema Suite Drawer (graphicsLayer slide-in from right)
        if (drawerProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = drawerProgress
                        translationX = (1f - drawerProgress) * 320.dp.toPx()
                    }
            ) {
                ProToolsDrawer(
                    state = state,
                    cameraController = viewModel.cameraController,
                    onClose = { viewModel.cameraController.setProToolsDrawerOpen(false) }
                )
            }
        }
    }

    if (showSettingsDialog) {
        CinemaSettingsDialog(
            state = state,
            onDismiss = { showSettingsDialog = false },
            onSelectResolution = { viewModel.cameraController.setVideoResolution(it) },
            onSelectBitrate = { viewModel.cameraController.setBitrateMbps(it) },
            onSelectProfile = { viewModel.cameraController.setColorProfile(it) },
            onSelectFormat = { viewModel.cameraController.setRecordingFormat(it) },
            onSelectCodec = { viewModel.cameraController.setVideoCodec(it) },
            onToggleDeband = { viewModel.cameraController.setDeband(it) }
        )
    }
}

/**
 * Shared High-Precision Vernier Dial, LUT, & Monitor Popover Scrubber.
 */
@Composable
private fun CinemaActiveDialScrubber(
    activeDial: ActiveDial,
    state: CinemaState,
    viewModel: CinemaViewModel,
    fpsValues: List<Int>,
    shutterAngles: List<Float>,
    shutterSpeedsNs: List<Long>,
    evValues: List<Float>,
    bitrateValues: List<Int>,
    onSwitchToDial: (ActiveDial) -> Unit,
    onOpenProSuite: () -> Unit,
    onClose: () -> Unit
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
                onClose = onClose,
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
                    onClose = onClose,
                    presets = listOf("90°" to 3, "144°" to 4, "172.8°" to 5, "180°" to 6, "360°" to 8),
                    headerActions = {
                        TextButton(onClick = { viewModel.cameraController.toggleShutterUnit() }) {
                            Text("SPEED", color = CinemaCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    onClose = onClose,
                    presets = listOf(
                        "1/24" to 14,
                        "1/48" to 11,
                        "1/50" to 10,
                        "1/60" to 9,
                        "1/100" to 7,
                        "1/120" to 6,
                        "1/1000" to 3
                    ),
                    headerActions = {
                        TextButton(onClick = { viewModel.cameraController.toggleShutterUnit() }) {
                            Text("ANGLE", color = CinemaCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
        ActiveDial.ISO -> {
            val activeIsoRange = viewModel.cameraController.getActiveSensorIsoRange()
            CinemaIsoWheelScrubber(
                currentIso = state.iso,
                activeIsoRange = activeIsoRange,
                isManualExposure = state.isManualExposure,
                isAutoIso = state.isAutoIso,
                onIsoChanged = { iso -> viewModel.cameraController.setIso(iso) },
                onToggleExposureMode = { viewModel.cameraController.toggleExposureMode() },
                onToggleAutoIso = { viewModel.cameraController.toggleAutoIso() },
                onClose = onClose
            )
        }
        ActiveDial.FOCUS -> {
            val maxDiopters = maxOf(0.1f, state.currentSensorSpec?.minimumFocusDistance ?: 10.0f)
            CinemaFocusWheelScrubber(
                currentFocusDistance = state.focusDistance,
                maxDiopters = maxDiopters,
                onFocusDistanceChanged = { dist -> viewModel.cameraController.setFocusDistance(dist) },
                onClose = onClose,
                headerActions = {
                    TextButton(onClick = {
                        viewModel.cameraController.setFocusMode(
                            if (state.focusMode == FocusMode.MANUAL) FocusMode.AUTO_CONTINUOUS else FocusMode.MANUAL
                        )
                    }) {
                        Text(
                            if (state.focusMode == FocusMode.MANUAL) "ENABLE AF" else "ENABLE MF",
                            color = CinemaCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }
        ActiveDial.WHITE_BALANCE -> {
            CinemaWhiteBalanceWheelScrubber(
                currentKelvin = state.wbKelvin,
                isManualWb = state.isManualWb,
                onKelvinChanged = { k -> viewModel.cameraController.setWhiteBalance(k, state.wbTint) },
                onToggleAutoWb = { viewModel.cameraController.toggleAutoWb() },
                onClose = onClose
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
                onClose = onClose,
                presets = listOf("-2.0" to 0, "-1.0" to 3, "0.0" to 6, "+1.0" to 9, "+2.0" to 12),
                headerActions = {
                    TextButton(onClick = { viewModel.cameraController.setEvCompensation(0.0f) }) {
                        Text("RESET 0 EV", color = CinemaCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                onClose = onClose,
                presets = listOf("50M" to 0, "100M" to 2, "150M" to 4, "200M" to 6, "280M" to 8)
            )
        }
        ActiveDial.LUT -> {
            QuickLutSelectorTray(
                state = state,
                onSelectPreset = { preset -> viewModel.cameraController.setLutPreset(preset) },
                onToggleBake = { viewModel.cameraController.setLutBakeEnabled(!state.isLutBakeEnabled) },
                onClose = onClose
            )
        }
        ActiveDial.TOOLS -> {
            MonitorQuickPopover(
                state = state,
                cameraController = viewModel.cameraController,
                onOpenFocusDial = { onSwitchToDial(ActiveDial.FOCUS) },
                onOpenProSuite = onOpenProSuite,
                onClose = onClose
            )
        }
        ActiveDial.NONE -> {}
    }
}

@Composable
private fun QuickLutSelectorTray(
    state: CinemaState,
    onSelectPreset: (LutPreset) -> Unit,
    onToggleBake: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(22.dp.toPx())
                drawRoundRect(color = CinemaGlassSheetBg, cornerRadius = r)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(CinemaSpecularTop, CinemaSpecularBottom)),
                    cornerRadius = r,
                    style = Stroke(0.5.dp.toPx())
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "3D LUT ENGINE",
                    color = CinemaAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.08.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .drawBehind {
                            val r = CornerRadius(8.dp.toPx())
                            drawRoundRect(
                                color = if (state.isLutBakeEnabled) CinemaAmber else Color(0x4D000000),
                                cornerRadius = r
                            )
                            drawRoundRect(
                                color = if (state.isLutBakeEnabled) CinemaAmber else CinemaSpecularBottom,
                                cornerRadius = r,
                                style = Stroke(0.5.dp.toPx())
                            )
                        }
                        .clickable(onClick = onToggleBake)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (state.isLutBakeEnabled) "BAKE: ON" else "PREVIEW ONLY",
                        color = if (state.isLutBakeEnabled) CinemaObsidian else CinemaWhite,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close LUT Tray",
                tint = CinemaWhite,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onClose)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LutPreset.entries.forEach { preset ->
                val isSelected = state.lutPreset == preset && (preset == LutPreset.OFF || state.showLutPreview)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .drawBehind {
                            val r = CornerRadius(10.dp.toPx())
                            drawRoundRect(
                                color = if (isSelected) CinemaAmber.copy(alpha = 0.22f) else Color(0x4D000000),
                                cornerRadius = r
                            )
                            drawRoundRect(
                                color = if (isSelected) CinemaAmber.copy(alpha = 0.85f) else CinemaSpecularBottom,
                                cornerRadius = r,
                                style = Stroke(0.5.dp.toPx())
                            )
                        }
                        .clickable { onSelectPreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = preset.label,
                        color = if (isSelected) CinemaAmber else CinemaWhite,
                        fontSize = 9.5.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
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
    onSelectCodec: (VideoCodec) -> Unit,
    onToggleDeband: (Boolean) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "CINEMA RECORDING & FORMAT SETTINGS",
                color = CinemaAmber,
                fontSize = 14.sp,
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
                Text("RECORDING CONTAINER", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = state.recordingFormat == fmt,
                            onClick = { onSelectFormat(fmt) },
                            label = { Text(fmt.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                if (state.recordingFormat == RecordingFormat.VIDEO_PRO) {
                    Text("VIDEO CODEC (COLOR DEPTH)", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SKY DEBAND FILTER", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (state.isDebandEnabled) "ON • 2-tap gradient smoothing" else "OFF • Pure 60fps single-pass",
                                color = if (state.isDebandEnabled) CinemaCyan else CinemaGrayDeep,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !state.isDebandEnabled,
                                onClick = { onToggleDeband(false) },
                                label = { Text("OFF (FAST)", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = state.isDebandEnabled,
                                onClick = { onToggleDeband(true) },
                                label = { Text("ON (SKY SMOOTHING)", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Text("RESOLUTION & ASPECT RATIO (${state.supportedResolutions.size} AVAILABLE)", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

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
                            color = if (isSelected) Color(0x28F5A623) else CinemaElevated,
                            border = BorderStroke(
                                width = 0.75.dp,
                                color = if (isSelected) CinemaAmber else CinemaBorderInactive
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
                                        color = if (isSelected) CinemaAmber else CinemaWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${res.width} × ${res.height} • Aspect Ratio: ${res.aspectLabel}",
                                        color = CinemaGray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        style = TabularScreenStyle
                                    )
                                }
                                if (isSelected) {
                                    Text("ACTIVE", color = CinemaAmber, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }

                if (state.recordingFormat == RecordingFormat.VIDEO_PRO) {
                    Text("TARGET BITRATE", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(50, 100, 150, 200, 240, 280).forEach { mbps ->
                            FilterChip(
                                selected = state.targetBitrateMbps == mbps,
                                onClick = { onSelectBitrate(mbps) },
                                label = { Text("$mbps Mbps", fontSize = 11.sp, style = TabularScreenStyle) }
                            )
                        }
                    }
                }

                Text("COLOR PROFILE & TONE CURVE", color = CinemaGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                Text("DONE", color = CinemaAmber, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CinemaElevated,
        shape = RoundedCornerShape(12.dp)
    )
}
