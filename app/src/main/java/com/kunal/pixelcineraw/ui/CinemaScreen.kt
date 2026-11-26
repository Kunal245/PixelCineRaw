package com.kunal.pixelcineraw.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.*
import java.util.Locale
import kotlin.math.abs

/**
 * Modern flagship cinema camera interface.
 *
 * Supports dual adaptive layouts:
 * 1. Landscape (Horizontal): Full cinema monitor view with pro side rails and top status bar.
 * 2. Portrait (Vertical): Minimal, ultra-modern iPhone Pro / Apple Final Cut Camera mobile interface
 *    with a full-height 9:16 / 3:4 vertical viewfinder, minimal glass header, and clean thumb dock.
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
            1_000_000_000L / 100, // 50Hz flicker-free sync
            1_000_000_000L / 96,
            1_000_000_000L / 60,
            1_000_000_000L / 50,  // 50Hz flicker-free sync
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

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    if (isPortrait) {
        PortraitCinemaLayout(
            viewModel = viewModel,
            state = state,
            activeDial = activeDial,
            onActiveDialChanged = { activeDial = it },
            onRecordToggle = onRecordToggle,
            onShowSettings = { showSettingsDialog = true },
            fpsValues = fpsValues,
            shutterAngles = shutterAngles,
            shutterSpeedsNs = shutterSpeedsNs,
            evValues = evValues,
            bitrateValues = bitrateValues,
            modifier = modifier
        )
    } else {
        LandscapeCinemaLayout(
            viewModel = viewModel,
            state = state,
            activeDial = activeDial,
            onActiveDialChanged = { activeDial = it },
            onRecordToggle = onRecordToggle,
            onShowSettings = { showSettingsDialog = true },
            fpsValues = fpsValues,
            shutterAngles = shutterAngles,
            shutterSpeedsNs = shutterSpeedsNs,
            evValues = evValues,
            bitrateValues = bitrateValues,
            modifier = modifier
        )
    }

    // Cinema Settings Dialog (Format, Codec, Bitrate, Profile, Resolution)
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
 * Minimal, Ultra-Modern iPhone Pro / Apple Final Cut Camera Vertical (Portrait) Layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitCinemaLayout(
    viewModel: CinemaViewModel,
    state: CinemaState,
    activeDial: ActiveDial,
    onActiveDialChanged: (ActiveDial) -> Unit,
    onRecordToggle: () -> Unit,
    onShowSettings: () -> Unit,
    fpsValues: List<Int>,
    shutterAngles: List<Float>,
    shutterSpeedsNs: List<Long>,
    evValues: List<Float>,
    bitrateValues: List<Int>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showFormatPopover by remember { mutableStateOf(false) }
    var showProSheet by remember { mutableStateOf(false) }

    // 1Hz recording timer & 12Hz VU meter UI throttle during recording
    var throttledRecordingSeconds by remember { mutableLongStateOf(state.recordingTimeSeconds) }
    var throttledRecordedBytes by remember { mutableLongStateOf(state.recordedBytes) }
    var lastTimerUiUpdateMs by remember { mutableLongStateOf(0L) }
    if (!state.isRecording) {
        throttledRecordingSeconds = 0L
        throttledRecordedBytes = 0L
        lastTimerUiUpdateMs = 0L
    } else {
        val nowMs = SystemClock.uptimeMillis()
        if (state.recordingTimeSeconds != throttledRecordingSeconds || nowMs - lastTimerUiUpdateMs >= 1000L) {
            throttledRecordingSeconds = state.recordingTimeSeconds
            throttledRecordedBytes = state.recordedBytes
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
        if (nowMs - lastVuUiUpdateMs >= 83L) { // 12Hz
            throttledAudioPeakL = state.audioPeakDbL
            throttledAudioPeakR = state.audioPeakDbR
            lastVuUiUpdateMs = nowMs
        }
    }

    val vRes = state.videoResolution
    val resWidth = minOf(vRes.width, vRes.height)
    val resHeight = maxOf(vRes.width, vRes.height)

    val resTitle = when {
        resHeight >= 3800 || resWidth >= 2160 -> "4K"
        resHeight in 2400..3200 || resWidth in 1400..1800 -> "2.5K"
        resHeight >= 1920 || resWidth >= 1080 -> "1080"
        else -> "${resWidth}p"
    }

    val portraitAspectLabel = when {
        abs(vRes.aspectRatio - (4f / 3f)) < 0.05f || abs(vRes.aspectRatio - (3f / 4f)) < 0.05f -> "3:4"
        abs(vRes.aspectRatio - 2.39f) < 0.08f -> "2.39"
        else -> "9:16"
    }

    val profileDotColor = when (state.colorProfile) {
        ColorProfile.HLG_10BIT -> Color(0xFFFFD700)
        ColorProfile.HDR10_PLUS -> Color(0xFFFF007F)
        ColorProfile.FLAT_LOG -> Color(0xFF00E5FF)
        ColorProfile.REC709 -> Color(0xFF00FF66)
    }

    val isOrientLocked = state.orientationLockMode != OrientationLockMode.AUTO
    val orientIcon = when (state.orientationLockMode) {
        OrientationLockMode.AUTO -> Icons.Default.ScreenRotation
        OrientationLockMode.LOCK_LANDSCAPE -> Icons.Default.StayCurrentLandscape
        OrientationLockMode.LOCK_PORTRAIT -> Icons.Default.StayCurrentPortrait
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (activeDial != ActiveDial.NONE) {
                    onActiveDialChanged(ActiveDial.NONE)
                }
            }
    ) {
        // ====================================================================
        // 1. SINGLE MINIMAL TOP GLASS HEADER
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Compact Format/FPS pill + Color Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Format/FPS pill (e.g. "4K 60 · 9:16")
                Box {
                    Row(
                        modifier = Modifier
                            .background(Color(0xB314161E), RoundedCornerShape(16.dp))
                            .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { showFormatPopover = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$resTitle ${state.targetFps} · $portraitAspectLabel",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

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
                                viewModel.cameraController.setVideoResolution(res)
                                showFormatPopover = false
                            },
                            onFpsSelected = { fps ->
                                viewModel.cameraController.setTargetFps(fps)
                                showFormatPopover = false
                            },
                            onAntiBandingSelected = { mode ->
                                viewModel.cameraController.setAntiBandingMode(mode)
                                showFormatPopover = false
                            },
                            onAeMeteringModeSelected = { mode ->
                                viewModel.cameraController.setAeMeteringMode(mode)
                                showFormatPopover = false
                            },
                            onOpenAdvanced = {
                                showFormatPopover = false
                                onShowSettings()
                            },
                            onResetDefaults = {
                                showFormatPopover = false
                                viewModel.cameraController.resetToDefaults()
                            }
                        )
                    }
                }

                // Color Profile Badge (Tap to cycle REC.709 -> LOG -> HLG10 -> HDR10+)
                Row(
                    modifier = Modifier
                        .background(Color(0xB314161E), RoundedCornerShape(16.dp))
                        .border(0.6.dp, profileDotColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable {
                            val nextProfile = when (state.colorProfile) {
                                ColorProfile.REC709 -> ColorProfile.FLAT_LOG
                                ColorProfile.FLAT_LOG -> ColorProfile.HLG_10BIT
                                ColorProfile.HLG_10BIT -> ColorProfile.HDR10_PLUS
                                ColorProfile.HDR10_PLUS -> ColorProfile.REC709
                            }
                            viewModel.cameraController.setColorProfile(nextProfile)
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(profileDotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = state.colorProfile.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Right: Minimal Torch, Orientation Lock, and Pro Tools (•••) Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Torch Button
                val torchEnabled = !state.isFrontCamera
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (state.isTorchOn) Color(0xFFFFCC00) else Color(0x3314161E))
                        .border(0.6.dp, if (state.isTorchOn) Color(0xFFFFE066) else Color(0x22FFFFFF), CircleShape)
                        .clickable(enabled = torchEnabled) { viewModel.cameraController.toggleTorch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashlightOn,
                        contentDescription = "Torch Light",
                        tint = if (state.isTorchOn) Color.Black else if (!torchEnabled) Color(0x44FFFFFF) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Orientation Lock Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isOrientLocked) Color(0x4D00E5FF) else Color(0x3314161E))
                        .border(0.6.dp, if (isOrientLocked) Color(0xFF00E5FF) else Color(0x22FFFFFF), CircleShape)
                        .clickable { viewModel.cameraController.cycleOrientationLockMode() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = orientIcon,
                        contentDescription = "Orientation Lock",
                        tint = if (isOrientLocked) Color(0xFF00E5FF) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Pro Tools (•••) Bottom Sheet Launcher
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x3314161E))
                        .border(0.6.dp, Color(0x22FFFFFF), CircleShape)
                        .clickable { showProSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "Pro Tools",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ====================================================================
        // 2. FULL-HEIGHT UNOBSTRUCTED VERTICAL VIEWFINDER
        // ====================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(24.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Live 9:16 / 3:4 Vertical Camera Viewfinder Surface
            Viewfinder(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            // Monitoring Overlays (Zebras, Peaking, Frame Guides, Horizon Leveler)
            MonitoringOverlays(
                state = state.copy(showHistogram = false, showLutPreview = false),
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(resWidth.toFloat() / resHeight.toFloat(), matchHeightConstraintsFirst = true)
            )

            // Compact Floating Histogram in top-left (ONLY if enabled in Pro sheet)
            if (state.showHistogram) {
                MovableHistogramHUD(
                    data = state.histogramData,
                    isHlg = state.colorProfile == ColorProfile.HLG_10BIT,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 12.dp)
                )
            }

            // Compact Floating LUT badge in top-right (ONLY if enabled in Pro sheet)
            if (state.showLutPreview && state.lutPreset != LutPreset.OFF) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .border(0.8.dp, Color(0xFF00E5FF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "LUT · ${state.lutPreset.label}",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Clean Viewfinder Mode Glowing REC Indicator
            if (state.isCleanViewfinder && state.isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFFFF0033), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val mins = throttledRecordingSeconds / 60
                    val secs = throttledRecordingSeconds % 60
                    Text(
                        text = "REC %02d:%02d".format(mins, secs),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Floating iPhone Zoom Pill (0.5 • 1x • 2 • 5) anchored over bottom edge of viewfinder
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.isRecording) {
                    val mins = throttledRecordingSeconds / 60
                    val secs = throttledRecordingSeconds % 60
                    val mbRecorded = throttledRecordedBytes / (1024 * 1024)
                    Row(
                        modifier = Modifier
                            .background(Color(0xCCFF1744), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color.White, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "%02d:%02d · %dMB".format(mins, secs, mbRecorded),
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        if (state.isRecordingPaused) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PAUSED",
                                color = Color(0xFFFFCC00),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                CinemaZoomHub(
                    state = state,
                    onZoomChanged = { zoom -> viewModel.cameraController.setZoomRatio(zoom) },
                    onSliderVisibilityChanged = { visible -> viewModel.cameraController.setZoomSliderVisibility(visible) }
                )
            }
        }

        // ====================================================================
        // 3. ULTRA-CLEAN IPHONE BOTTOM CONTROL DOCK
        // ====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Layer 1: Expandable Rotary Dial
            androidx.compose.animation.AnimatedVisibility(
                visible = activeDial != ActiveDial.NONE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CinemaActiveDialScrubber(
                        activeDial = activeDial,
                        state = state,
                        viewModel = viewModel,
                        fpsValues = fpsValues,
                        shutterAngles = shutterAngles,
                        shutterSpeedsNs = shutterSpeedsNs,
                        evValues = evValues,
                        bitrateValues = bitrateValues,
                        onClose = { onActiveDialChanged(ActiveDial.NONE) }
                    )
                }
            }

            // Layer 2: Minimal Borderless Exposure Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
                    .background(Color(0x99111318), RoundedCornerShape(28.dp))
                    .border(0.6.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. SHUTTER
                val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
                    if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_50) {
                        val flickerSpeed = if (state.targetFps <= 30) "1/50" else "1/100"
                        "180° ($flickerSpeed)"
                    } else if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_60) {
                        val flickerSpeed = if (state.targetFps <= 30) "1/60" else "1/120"
                        "180° ($flickerSpeed)"
                    } else {
                        "${state.shutterAngle.toInt()}°"
                    }
                } else {
                    ColorMath.formatShutterSpeed(state.shutterSpeedNs)
                }
                BorderlessExposureItem(
                    label = if (state.shutterUnit == ShutterUnit.ANGLE) "SHUTTER" else "SPEED",
                    value = shutterVal,
                    isActive = activeDial == ActiveDial.SHUTTER,
                    isAuto = !state.isManualExposure,
                    onClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.SHUTTER) ActiveDial.NONE else ActiveDial.SHUTTER)
                    },
                    modifier = Modifier.weight(1f)
                )

                // 2. ISO
                val isoVal = if (state.isAutoIso) "AUTO ${state.iso}" else "${state.iso}"
                BorderlessExposureItem(
                    label = "ISO",
                    value = isoVal,
                    isActive = activeDial == ActiveDial.ISO,
                    isAuto = state.isAutoIso || !state.isManualExposure,
                    onClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.ISO) ActiveDial.NONE else ActiveDial.ISO)
                    },
                    modifier = Modifier.weight(1f)
                )

                // 3. WB
                val wbVal = if (state.isManualWb) "${state.wbKelvin}K" else "AWB"
                BorderlessExposureItem(
                    label = "WB",
                    value = wbVal,
                    isActive = activeDial == ActiveDial.WHITE_BALANCE,
                    isAuto = !state.isManualWb,
                    onClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.WHITE_BALANCE) ActiveDial.NONE else ActiveDial.WHITE_BALANCE)
                    },
                    modifier = Modifier.weight(1f)
                )

                // 4. FOCUS
                val focusVal = when (state.focusMode) {
                    FocusMode.AUTO_CONTINUOUS -> "AF"
                    FocusMode.LOCKED -> "AF·L"
                    FocusMode.MANUAL -> {
                        if (state.focusDistance <= 0.05f) "MF ∞"
                        else String.format(Locale.US, "MF %.1fm", 1.0f / state.focusDistance)
                    }
                }
                BorderlessExposureItem(
                    label = "FOCUS",
                    value = focusVal,
                    isActive = activeDial == ActiveDial.FOCUS,
                    isAuto = state.focusMode != FocusMode.MANUAL,
                    onClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.FOCUS) ActiveDial.NONE else ActiveDial.FOCUS)
                    },
                    modifier = Modifier.weight(1f)
                )

                // 5. EV
                val evVal = if (state.evCompensation > 0f) "+%.1f".format(state.evCompensation)
                else "%.1f".format(state.evCompensation)
                BorderlessExposureItem(
                    label = "EV",
                    value = evVal,
                    isActive = activeDial == ActiveDial.EV,
                    isAuto = false,
                    onClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.EV) ActiveDial.NONE else ActiveDial.EV)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Layer 3: iPhone Shutter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Last Clip Thumbnail (46.dp)
                val clipThumbnail = state.lastRecordedClipThumbnail
                val clipUri = state.lastRecordedClipUri

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF16181F))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable {
                            if (clipUri != null) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(clipUri), "video/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.w("PortraitCinema", "Failed to launch video viewer", e)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (clipThumbnail != null) {
                        Image(
                            bitmap = clipThumbnail.asImageBitmap(),
                            contentDescription = "Last Recorded Clip",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = "No Clips",
                            tint = Color(0x88FFFFFF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Center: Concentric Cinema Shutter Button (70.dp)
                val infiniteTransition = rememberInfiniteTransition(label = "PortraitRecPulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "RecordPulseScale"
                )

                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .graphicsLayer {
                            val s = if (state.isRecording) pulseScale else 1.0f
                            scaleX = s
                            scaleY = s
                        }
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable { onRecordToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (state.isRecording) 28.dp else 54.dp)
                            .clip(if (state.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                            .background(Color(0xFFFF1744))
                    )
                }

                // Right: Audio (WAV/AAC + mini VU) & AE/AF Lock (46.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Audio Pill
                    Column(
                        modifier = Modifier
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xB312141A))
                            .border(
                                0.8.dp,
                                if (state.recordWavSidecar) Color(0xFFFFCC00).copy(alpha = 0.5f) else Color(0x26FFFFFF),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.cameraController.toggleRecordWavSidecar() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (state.recordWavSidecar) "WAV" else "AAC",
                            color = if (state.recordWavSidecar) Color(0xFFFFCC00) else Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        VuBar(peakDb = throttledAudioPeakL)
                        Spacer(modifier = Modifier.height(2.dp))
                        VuBar(peakDb = throttledAudioPeakR)
                    }

                    // AE/AF Lock Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (state.isAeAfLocked) Color(0xFFFF1744) else Color(0x3314161E))
                            .border(
                                1.dp,
                                if (state.isAeAfLocked) Color(0xFFFF6677) else Color(0x22FFFFFF),
                                CircleShape
                            )
                            .clickable { viewModel.cameraController.toggleAeAfLock() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "AE/AF Lock",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // ====================================================================
    // PRO TOOLS BOTTOM SHEET (•••)
    // ====================================================================
    if (showProSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProSheet = false },
            containerColor = Color(0xF212141A),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRO MONITORING & ASSIST",
                        color = Color(0xFFFFCC00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    TextButton(onClick = {
                        showProSheet = false
                        onShowSettings()
                    }) {
                        Text("ALL SETTINGS...", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Monitoring Toggles Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProSheetToggleCard(
                        title = "PEAKING",
                        sub = if (state.showFocusPeaking) "ACTIVE" else "OFF",
                        isActive = state.showFocusPeaking,
                        activeColor = Color(0xFF00FF66),
                        onClick = { viewModel.cameraController.toggleFocusPeaking() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "ZEBRAS",
                        sub = if (state.showZebras) "ACTIVE" else "OFF",
                        isActive = state.showZebras,
                        activeColor = Color(0xFFFFD700),
                        onClick = { viewModel.cameraController.toggleZebras() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProSheetToggleCard(
                        title = "GUIDES",
                        sub = state.frameGuide.name.replace("RATIO_", ""),
                        isActive = state.frameGuide != FrameGuide.NONE,
                        activeColor = Color(0xFF00E5FF),
                        onClick = {
                            val nextGuide = when (state.frameGuide) {
                                FrameGuide.NONE -> FrameGuide.RATIO_2_39
                                FrameGuide.RATIO_2_39 -> FrameGuide.RATIO_16_9
                                FrameGuide.RATIO_16_9 -> FrameGuide.RATIO_4_3
                                FrameGuide.RATIO_4_3 -> FrameGuide.RATIO_1_1
                                FrameGuide.RATIO_1_1 -> FrameGuide.NONE
                            }
                            viewModel.cameraController.setFrameGuide(nextGuide)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "LEVELER",
                        sub = if (state.showHorizonLeveler) "ACTIVE" else "OFF",
                        isActive = state.showHorizonLeveler,
                        activeColor = Color(0xFF00FF66),
                        onClick = { viewModel.cameraController.toggleHorizonLeveler() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProSheetToggleCard(
                        title = "HISTOGRAM",
                        sub = if (state.showHistogram) "ON-SCREEN" else "OFF",
                        isActive = state.showHistogram,
                        activeColor = Color(0xFF00E5FF),
                        onClick = { viewModel.cameraController.toggleHistogram() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "3D LUT",
                        sub = state.lutPreset.label,
                        isActive = state.showLutPreview,
                        activeColor = Color(0xFF00E5FF),
                        onClick = { viewModel.cameraController.toggleLutPreview() },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Camera Flip, Deband & Clean Viewfinder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProSheetToggleCard(
                        title = "DEBAND",
                        sub = if (state.isDebandEnabled) "ON (SKY)" else "OFF (FAST)",
                        isActive = state.isDebandEnabled,
                        activeColor = Color(0xFF00E5FF),
                        onClick = { viewModel.cameraController.toggleDeband() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "CAMERA",
                        sub = if (state.isFrontCamera) "FRONT" else "REAR",
                        isActive = state.isFrontCamera,
                        activeColor = Color(0xFF00E5FF),
                        onClick = { viewModel.cameraController.flipCamera() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProSheetToggleCard(
                        title = "CLEAN HUD",
                        sub = if (state.isCleanViewfinder) "ON" else "OFF",
                        isActive = state.isCleanViewfinder,
                        activeColor = Color(0xFFFFCC00),
                        onClick = {
                            showProSheet = false
                            viewModel.cameraController.toggleCleanViewfinder()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Minimal borderless exposure item for single-pill iPhone bottom dock.
 */
@Composable
private fun BorderlessExposureItem(
    label: String,
    value: String,
    isActive: Boolean,
    isAuto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = if (isActive) Color(0xFFFFCC00) else Color(0x88FFFFFF),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (isAuto) Color(0xFF00FF66) else Color(0xFFFFCC00))
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (isActive) Color(0xFFFFCC00) else Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Clean Pro Sheet Toggle Card.
 */
@Composable
private fun ProSheetToggleCard(
    title: String,
    sub: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) activeColor.copy(alpha = 0.15f) else Color(0x3320222A),
        border = BorderStroke(
            0.8.dp,
            if (isActive) activeColor else Color(0x22FFFFFF)
        ),
        modifier = modifier.height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    color = if (isActive) activeColor else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = sub,
                    color = Color(0x88FFFFFF),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else Color(0x33FFFFFF))
            )
        }
    }
}

/**
 * Exact, untouched Horizontal (Landscape) Cinema Layout.
 */
@Composable
private fun LandscapeCinemaLayout(
    viewModel: CinemaViewModel,
    state: CinemaState,
    activeDial: ActiveDial,
    onActiveDialChanged: (ActiveDial) -> Unit,
    onRecordToggle: () -> Unit,
    onShowSettings: () -> Unit,
    fpsValues: List<Int>,
    shutterAngles: List<Float>,
    shutterSpeedsNs: List<Long>,
    evValues: List<Float>,
    bitrateValues: List<Int>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Tapping outside dismisses active dial without toggling clean viewfinder HUD
                if (activeDial != ActiveDial.NONE) {
                    onActiveDialChanged(ActiveDial.NONE)
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
                onResolutionSelected = { res -> viewModel.cameraController.setVideoResolution(res) },
                onFpsSelected = { fps -> viewModel.cameraController.setTargetFps(fps) },
                onColorProfileClick = {
                    val nextProfile = when (state.colorProfile) {
                        ColorProfile.REC709 -> ColorProfile.FLAT_LOG
                        ColorProfile.FLAT_LOG -> ColorProfile.HLG_10BIT
                        ColorProfile.HLG_10BIT -> ColorProfile.HDR10_PLUS
                        ColorProfile.HDR10_PLUS -> ColorProfile.REC709
                    }
                    viewModel.cameraController.setColorProfile(nextProfile)
                },
                onIntraModeSelected = { mode -> viewModel.cameraController.setIntraFrameMode(mode) },
                onBitrateSelected = { mbps -> viewModel.cameraController.setBitrateMbps(mbps) },
                onIspClick = { viewModel.cameraController.toggleIspMode() },
                onAudioWavClick = { viewModel.cameraController.toggleRecordWavSidecar() },
                onAdvancedSettingsClick = onShowSettings,
                onStabilizationClick = { viewModel.cameraController.cycleStabilizationMode() },
                onOrientationLockClick = { viewModel.cameraController.cycleOrientationLockMode() },
                onAntiBandingClick = { viewModel.cameraController.cycleAntiBandingMode() },
                onAntiBandingModeSelected = { mode -> viewModel.cameraController.setAntiBandingMode(mode) },
                onAeMeteringClick = { viewModel.cameraController.cycleAeMeteringMode() },
                onAeMeteringModeSelected = { mode -> viewModel.cameraController.setAeMeteringMode(mode) },
                onAutoShutterClick = { viewModel.cameraController.setAutoShutter() },
                onResetDefaults = { viewModel.cameraController.resetToDefaults() }
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
                onStabilizationToggle = { viewModel.cameraController.cycleStabilizationMode() }
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
                onAeAfLockToggle = { viewModel.cameraController.toggleAeAfLock() },
                onFlipCamera = { viewModel.cameraController.flipCamera() },
                onToggleProDrawer = { viewModel.cameraController.toggleProToolsDrawer() }
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
                    onActiveDialChanged(if (activeDial == ActiveDial.SHUTTER) ActiveDial.NONE else ActiveDial.SHUTTER)
                },
                onIsoClick = {
                    onActiveDialChanged(if (activeDial == ActiveDial.ISO) ActiveDial.NONE else ActiveDial.ISO)
                },
                onWbClick = {
                    onActiveDialChanged(if (activeDial == ActiveDial.WHITE_BALANCE) ActiveDial.NONE else ActiveDial.WHITE_BALANCE)
                },
                onFocusClick = {
                    onActiveDialChanged(if (activeDial == ActiveDial.FOCUS) ActiveDial.NONE else ActiveDial.FOCUS)
                },
                onEvClick = {
                    onActiveDialChanged(if (activeDial == ActiveDial.EV) ActiveDial.NONE else ActiveDial.EV)
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
            CinemaActiveDialScrubber(
                activeDial = activeDial,
                state = state,
                viewModel = viewModel,
                fpsValues = fpsValues,
                shutterAngles = shutterAngles,
                shutterSpeedsNs = shutterSpeedsNs,
                evValues = evValues,
                bitrateValues = bitrateValues,
                onClose = { onActiveDialChanged(ActiveDial.NONE) }
            )
        }

        // 9. Pro Cinema Suite Drawer (Slides out smoothly from end)
        AnimatedVisibility(
            visible = state.isProToolsDrawerOpen,
            enter = slideInHorizontally { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            ProToolsDrawer(
                state = state,
                cameraController = viewModel.cameraController,
                onClose = { viewModel.cameraController.setProToolsDrawerOpen(false) }
            )
        }
    }
}

/**
 * Shared High-Precision Rotary Dial Scrubber.
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
                            Text("SWITCH TO ANGLE", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
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
                onClose = onClose,
                presets = listOf("50M" to 0, "100M" to 2, "150M" to 4, "200M" to 6, "280M" to 8)
            )
        }
        ActiveDial.NONE -> {}
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

                    // Sky Deband Anti-Banding Filter (OFF by default / ON optional)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SKY DEBAND FILTER", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (state.isDebandEnabled) "ON • 2-tap gradient smoothing" else "OFF • Pure 60fps single-pass",
                                color = if (state.isDebandEnabled) Color(0xFF00E5FF) else Color(0x66FFFFFF),
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
                            border = BorderStroke(
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
