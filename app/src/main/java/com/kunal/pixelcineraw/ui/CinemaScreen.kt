package com.kunal.pixelcineraw.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kunal.pixelcineraw.camera.*
import com.kunal.pixelcineraw.ui.theme.*
import java.util.Locale
import kotlin.math.abs

private val TabularScreenStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v1.5.0 Cinema Camera Interface (Blackmagic Cinema Monitor + Apple Pro tactile smoothness).
 *
 * All transitions (dial reveal, clean viewfinder HUD hide, ProToolsDrawer slide) execute strictly
 * in the GraphicsLayer / Draw phase (`Modifier.graphicsLayer`, `Modifier.drawBehind`) with
 * zero layout invalidations (`<= 0.5%` UI jank).
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
 * v1.5.0 Vertical (Portrait) Cinema Layout.
 *
 * Uses a zero-jank overlay architecture so opening/closing any dial, LUT selector,
 * or Tools bar animates strictly in `graphicsLayer` without resizing the viewfinder `SurfaceView`.
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
        if (nowMs - lastVuUiUpdateMs >= 83L) {
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
        ColorProfile.HLG_10BIT -> CinemaGold
        ColorProfile.HDR10_PLUS -> CinemaHdrPink
        ColorProfile.FLAT_LOG -> CinemaAmber
        ColorProfile.REC709 -> CinemaAutoGreen
    }

    val isOrientLocked = state.orientationLockMode != OrientationLockMode.AUTO
    val orientIcon = when (state.orientationLockMode) {
        OrientationLockMode.AUTO -> Icons.Default.ScreenRotation
        OrientationLockMode.LOCK_LANDSCAPE -> Icons.Default.StayCurrentLandscape
        OrientationLockMode.LOCK_PORTRAIT -> Icons.Default.StayCurrentPortrait
    }

    val dialProgress by animateFloatAsState(
        targetValue = if (activeDial != ActiveDial.NONE) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "portraitDialProgress"
    )
    var lastActiveDial by remember { mutableStateOf(ActiveDial.NONE) }
    if (activeDial != ActiveDial.NONE) {
        lastActiveDial = activeDial
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaObsidian)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (activeDial != ActiveDial.NONE) {
                    onActiveDialChanged(ActiveDial.NONE)
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ====================================================================
            // 1. MINIMAL OBSIDIAN TOP HEADER
            // ====================================================================
            Row(
                modifier = Modifier
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Format/FPS pill, Profile pill, Thermal indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box {
                        CinemaPill(
                            label = "FORMAT",
                            value = "$resTitle·${state.targetFps}p",
                            subText = portraitAspectLabel,
                            isActive = true,
                            onClick = { showFormatPopover = true }
                        )

                        DropdownMenu(
                            expanded = showFormatPopover,
                            onDismissRequest = { showFormatPopover = false },
                            shape = RoundedCornerShape(10.dp),
                            containerColor = Color(0xF20B0C0E),
                            border = BorderStroke(0.75.dp, CinemaBorderActive),
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
                                onColorProfileSelected = { profile ->
                                    viewModel.cameraController.setColorProfile(profile)
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

                    CinemaPill(
                        label = "PROFILE",
                        value = state.colorProfile.label,
                        dotColor = profileDotColor,
                        isActive = state.colorProfile != ColorProfile.REC709,
                        onClick = {
                            val nextProfile = when (state.colorProfile) {
                                ColorProfile.REC709 -> ColorProfile.FLAT_LOG
                                ColorProfile.FLAT_LOG -> ColorProfile.HLG_10BIT
                                ColorProfile.HLG_10BIT -> ColorProfile.HDR10_PLUS
                                ColorProfile.HDR10_PLUS -> ColorProfile.REC709
                            }
                            viewModel.cameraController.setColorProfile(nextProfile)
                        }
                    )

                    ThermalIndicator(status = state.thermalStatus)
                }

                // Right: Timecode Pill (when recording) or Torch / Orient / Pro
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.isRecording) {
                        CinemaTimecodePill(
                            isRecording = true,
                            isPaused = state.isRecordingPaused,
                            recordingSeconds = throttledRecordingSeconds
                        )
                    } else {
                        val torchEnabled = !state.isFrontCamera
                        CompactHeaderIcon(
                            icon = Icons.Default.FlashlightOn,
                            contentDescription = "Torch Light",
                            isActive = state.isTorchOn,
                            enabled = torchEnabled,
                            activeFill = CinemaAmber,
                            activeTint = CinemaObsidian,
                            onClick = { viewModel.cameraController.toggleTorch() }
                        )

                        CompactHeaderIcon(
                            icon = orientIcon,
                            contentDescription = "Orientation Lock",
                            isActive = isOrientLocked,
                            activeFill = CinemaElevated,
                            activeTint = CinemaAmber,
                            onClick = { viewModel.cameraController.cycleOrientationLockMode() }
                        )
                    }

                    CompactHeaderIcon(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "Pro Tools",
                        isActive = showProSheet,
                        activeFill = CinemaAmber,
                        activeTint = CinemaObsidian,
                        onClick = { showProSheet = true }
                    )
                }
            }

            // ====================================================================
            // 2. FULL-HEIGHT UNOBSTRUCTED VERTICAL VIEWFINDER (Center 75%+ Clear)
            // ====================================================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(0.75.dp, CinemaBorderInactive, RoundedCornerShape(18.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Viewfinder(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )

                MonitoringOverlays(
                    state = state.copy(showHistogram = false, showLutPreview = false),
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(resWidth.toFloat() / resHeight.toFloat(), matchHeightConstraintsFirst = true)
                )

                if (state.showHistogram) {
                    MovableHistogramHUD(
                        data = state.histogramData,
                        isHlg = state.colorProfile == ColorProfile.HLG_10BIT,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 10.dp, start = 10.dp)
                    )
                }

                if (state.showLutPreview && state.lutPreset != LutPreset.OFF) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .drawBehind {
                                val r = CornerRadius(5.dp.toPx())
                                drawRoundRect(color = CinemaPillBgDark, cornerRadius = r)
                                drawRoundRect(color = CinemaAmber.copy(alpha = 0.7f), cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "LUT · ${state.lutPreset.label}",
                            color = CinemaAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (state.isCleanViewfinder && state.isRecording) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 14.dp, top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(CinemaCrimson, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val mins = throttledRecordingSeconds / 60
                        val secs = throttledRecordingSeconds % 60
                        Text(
                            text = "REC %02d:%02d".format(mins, secs),
                            color = CinemaWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            style = TabularScreenStyle
                        )
                    }
                }

                // Bottom-of-viewfinder cluster: Recording MB pill + Zero-Jank Zoom Hub
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
                                .drawBehind {
                                    val r = CornerRadius(10.dp.toPx())
                                    drawRoundRect(color = CinemaPillBgDark, cornerRadius = r)
                                    drawRoundRect(color = CinemaCrimson, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                                }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(CinemaCrimson, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "%02d:%02d · %dMB".format(mins, secs, mbRecorded),
                                color = CinemaWhite,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                style = TabularScreenStyle
                            )
                            if (state.isRecordingPaused) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "PAUSED",
                                    color = CinemaAmber,
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
            // 3. OBSIDIAN BOTTOM EXPOSURE + LUT/TOOLS DOCK & TACTILE SHUTTER BAR
            // ====================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CinemaPillBgDark)
                    .drawBehind {
                        drawLine(
                            color = CinemaBorderInactive,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 0.75.dp.toPx()
                        )
                    }
                    .navigationBarsPadding()
                    .padding(top = 6.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Exposure + LUT + TOOLS strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 10.dp)
                        .drawBehind {
                            val r = CornerRadius(12.dp.toPx())
                            drawRoundRect(color = CinemaElevated, cornerRadius = r)
                            drawRoundRect(color = CinemaBorderInactive, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
                        if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_50) {
                            val flickerSpeed = if (state.targetFps <= 30) "1/50" else "1/100"
                            "180° $flickerSpeed"
                        } else if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_60) {
                            val flickerSpeed = if (state.targetFps <= 30) "1/60" else "1/120"
                            "180° $flickerSpeed"
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
                        modifier = Modifier.weight(1.15f)
                    )

                    val isoVal = if (state.isAutoIso) "A·${state.iso}" else "${state.iso}"
                    BorderlessExposureItem(
                        label = "ISO",
                        value = isoVal,
                        isActive = activeDial == ActiveDial.ISO,
                        isAuto = state.isAutoIso || !state.isManualExposure,
                        onClick = {
                            onActiveDialChanged(if (activeDial == ActiveDial.ISO) ActiveDial.NONE else ActiveDial.ISO)
                        },
                        modifier = Modifier.weight(0.95f)
                    )

                    val wbVal = if (state.isManualWb) "${state.wbKelvin}K" else "AWB"
                    BorderlessExposureItem(
                        label = "WB",
                        value = wbVal,
                        isActive = activeDial == ActiveDial.WHITE_BALANCE,
                        isAuto = !state.isManualWb,
                        onClick = {
                            onActiveDialChanged(if (activeDial == ActiveDial.WHITE_BALANCE) ActiveDial.NONE else ActiveDial.WHITE_BALANCE)
                        },
                        modifier = Modifier.weight(0.95f)
                    )

                    val focusVal = when (state.focusMode) {
                        FocusMode.AUTO_CONTINUOUS -> "AF"
                        FocusMode.LOCKED -> "AF·L"
                        FocusMode.MANUAL -> {
                            if (state.focusDistance <= 0.05f) "MF ∞"
                            else String.format(Locale.US, "%.1fm", 1.0f / state.focusDistance)
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
                        modifier = Modifier.weight(0.9f)
                    )

                    val evVal = if (state.evCompensation > 0f) "+%.1f".format(state.evCompensation)
                    else "%.1f".format(state.evCompensation)
                    BorderlessExposureItem(
                        label = "EV",
                        value = evVal,
                        isActive = activeDial == ActiveDial.EV,
                        isAuto = false,
                        showModeDot = false,
                        onClick = {
                            onActiveDialChanged(if (activeDial == ActiveDial.EV) ActiveDial.NONE else ActiveDial.EV)
                        },
                        modifier = Modifier.weight(0.8f)
                    )

                    val lutActive = state.showLutPreview && state.lutPreset != LutPreset.OFF
                    BorderlessExposureItem(
                        label = "LUT",
                        value = if (lutActive) state.lutPreset.shortLabel else "OFF",
                        isActive = activeDial == ActiveDial.LUT || lutActive,
                        isAuto = !lutActive,
                        showModeDot = false,
                        onClick = {
                            onActiveDialChanged(if (activeDial == ActiveDial.LUT) ActiveDial.NONE else ActiveDial.LUT)
                        },
                        modifier = Modifier.weight(0.85f)
                    )

                    val toolsActive = state.showFocusPeaking || state.showZebras ||
                            state.showFalseColor || state.showHorizonLeveler ||
                            state.showHistogram || state.showWaveform
                    BorderlessExposureItem(
                        label = "TOOLS",
                        value = if (toolsActive) "ON" else "HUD",
                        isActive = activeDial == ActiveDial.TOOLS || toolsActive,
                        isAuto = false,
                        showModeDot = false,
                        onClick = {
                            onActiveDialChanged(if (activeDial == ActiveDial.TOOLS) ActiveDial.NONE else ActiveDial.TOOLS)
                        },
                        modifier = Modifier.weight(0.85f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Shutter Bar (Box ensures CinemaShutterButton is dead-center regardless of side widths)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    // Left: Last Clip Thumbnail (46.dp)
                    val clipThumbnail = state.lastRecordedClipThumbnail
                    val clipUri = state.lastRecordedClipUri

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CinemaElevated)
                            .border(0.75.dp, CinemaBorderActive, RoundedCornerShape(10.dp))
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
                                tint = CinemaGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Center: Zero-Jank Concentric Cinema Shutter Button (68.dp -> 26.dp square)
                    CinemaShutterButton(
                        isRecording = state.isRecording,
                        onClick = onRecordToggle,
                        modifier = Modifier.align(Alignment.Center),
                        outerSize = 68.dp,
                        innerCircleSize = 54.dp,
                        innerSquareSize = 26.dp
                    )

                    // Right: Audio (WAV/AAC + VU) & Pause (while recording) / AE-AF Lock
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .drawBehind {
                                    val r = CornerRadius(10.dp.toPx())
                                    drawRoundRect(color = CinemaElevated, cornerRadius = r)
                                    val stroke = if (state.recordWavSidecar) CinemaAmber.copy(alpha = 0.65f) else CinemaBorderInactive
                                    drawRoundRect(color = stroke, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
                                }
                                .clickable { viewModel.cameraController.toggleRecordWavSidecar() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (state.recordWavSidecar) "WAV" else "AAC",
                                color = if (state.recordWavSidecar) CinemaAmber else CinemaWhite,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            VuBar(peakDb = throttledAudioPeakL)
                            Spacer(modifier = Modifier.height(2.dp))
                            VuBar(peakDb = throttledAudioPeakR)
                        }

                        if (state.isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .drawBehind {
                                        drawCircle(color = if (state.isRecordingPaused) CinemaAmber else CinemaElevated)
                                        drawCircle(
                                            color = if (state.isRecordingPaused) CinemaAmber else CinemaBorderActive,
                                            style = Stroke(0.75.dp.toPx())
                                        )
                                    }
                                    .clickable { viewModel.cameraController.setRecordingPaused(!state.isRecordingPaused) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Pause/Resume",
                                    tint = if (state.isRecordingPaused) CinemaObsidian else CinemaWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .drawBehind {
                                        drawCircle(color = if (state.isAeAfLocked) CinemaCrimson else CinemaElevated)
                                        drawCircle(
                                            color = if (state.isAeAfLocked) CinemaCrimson else CinemaBorderInactive,
                                            style = Stroke(0.75.dp.toPx())
                                        )
                                    }
                                    .clickable { viewModel.cameraController.toggleAeAfLock() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "AE/AF Lock",
                                    tint = CinemaWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Expandable Rotary Dial / LUT / Tools Scrubber (animates purely in graphicsLayer!)
        if (dialProgress > 0.01f && lastActiveDial != ActiveDial.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = 142.dp)
                    .graphicsLayer {
                        alpha = dialProgress
                        translationY = (1f - dialProgress) * 20.dp.toPx()
                        scaleY = 0.94f + 0.06f * dialProgress
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
                    onClose = { onActiveDialChanged(ActiveDial.NONE) }
                )
            }
        }
    }

    // PRO TOOLS BOTTOM SHEET (•••)
    if (showProSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProSheet = false },
            containerColor = Color(0xF50B0C0E),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRO MONITORING & ASSIST",
                        color = CinemaAmber,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    TextButton(onClick = {
                        showProSheet = false
                        onShowSettings()
                    }) {
                        Text("ALL SETTINGS...", color = CinemaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProSheetToggleCard(
                        title = "PEAKING",
                        sub = if (state.showFocusPeaking) "ACTIVE" else "OFF",
                        isActive = state.showFocusPeaking,
                        activeColor = CinemaAutoGreen,
                        onClick = { viewModel.cameraController.toggleFocusPeaking() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "ZEBRAS",
                        sub = if (state.showZebras) "ACTIVE" else "OFF",
                        isActive = state.showZebras,
                        activeColor = CinemaGold,
                        onClick = { viewModel.cameraController.toggleZebras() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProSheetToggleCard(
                        title = "GUIDES",
                        sub = state.frameGuide.name.replace("RATIO_", ""),
                        isActive = state.frameGuide != FrameGuide.NONE,
                        activeColor = CinemaAmber,
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
                        activeColor = CinemaAutoGreen,
                        onClick = { viewModel.cameraController.toggleHorizonLeveler() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProSheetToggleCard(
                        title = "HISTOGRAM",
                        sub = if (state.showHistogram) "ON-SCREEN" else "OFF",
                        isActive = state.showHistogram,
                        activeColor = CinemaAmber,
                        onClick = { viewModel.cameraController.toggleHistogram() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "3D LUT",
                        sub = state.lutPreset.label,
                        isActive = state.showLutPreview,
                        activeColor = CinemaAmber,
                        onClick = { viewModel.cameraController.toggleLutPreview() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProSheetToggleCard(
                        title = "DEBAND",
                        sub = if (state.isDebandEnabled) "ON (SKY)" else "OFF (FAST)",
                        isActive = state.isDebandEnabled,
                        activeColor = CinemaCyan,
                        onClick = { viewModel.cameraController.toggleDeband() },
                        modifier = Modifier.weight(1f)
                    )
                    ProSheetToggleCard(
                        title = "CAMERA",
                        sub = if (state.isFrontCamera) "FRONT" else "REAR",
                        isActive = state.isFrontCamera,
                        activeColor = CinemaAmber,
                        onClick = { viewModel.cameraController.flipCamera() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProSheetToggleCard(
                        title = "CLEAN HUD",
                        sub = if (state.isCleanViewfinder) "ON" else "OFF",
                        isActive = state.isCleanViewfinder,
                        activeColor = CinemaAmber,
                        onClick = {
                            showProSheet = false
                            viewModel.cameraController.toggleCleanViewfinder()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun CompactHeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeFill: Color,
    activeTint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .drawBehind {
                drawCircle(color = if (isActive) activeFill else CinemaElevated)
                drawCircle(
                    color = if (isActive) activeTint.copy(alpha = 0.35f) else CinemaBorderInactive,
                    style = Stroke(0.75.dp.toPx())
                )
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (!enabled) CinemaGrayDeep else if (isActive) activeTint else CinemaWhite,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun BorderlessExposureItem(
    label: String,
    value: String,
    isActive: Boolean,
    isAuto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showModeDot: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = if (isActive) CinemaAmber else CinemaGray,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
            if (showModeDot) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isAuto) CinemaAutoGreen else CinemaManualAmber)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (isActive) CinemaAmber else CinemaWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularScreenStyle,
            maxLines = 1,
            softWrap = false
        )
    }
}

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
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) activeColor.copy(alpha = 0.14f) else CinemaElevated,
        border = BorderStroke(
            0.75.dp,
            if (isActive) activeColor else CinemaBorderInactive
        ),
        modifier = modifier.height(54.dp)
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
                    color = if (isActive) activeColor else CinemaWhite,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = sub,
                    color = CinemaGray,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else CinemaBorderInactive)
            )
        }
    }
}

/**
 * v1.5.0 Horizontal (Landscape) Cinema Layout.
 *
 * Uses zero-jank `graphicsLayer` animations for Clean Viewfinder HUD hide,
 * expandable Rotary/LUT/Tools dials, and ProToolsDrawer slide-in.
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
    val hudProgress by animateFloatAsState(
        targetValue = if (!state.isCleanViewfinder) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "hudVisibility"
    )
    val dialProgress by animateFloatAsState(
        targetValue = if (activeDial != ActiveDial.NONE && !state.isCleanViewfinder) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "landscapeDialProgress"
    )
    var lastActiveDial by remember { mutableStateOf(ActiveDial.NONE) }
    if (activeDial != ActiveDial.NONE) {
        lastActiveDial = activeDial
    }

    val drawerProgress by animateFloatAsState(
        targetValue = if (state.isProToolsDrawerOpen) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "proDrawerSlide"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaObsidian)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (activeDial != ActiveDial.NONE) {
                    onActiveDialChanged(ActiveDial.NONE)
                }
            }
    ) {
        // 1. Live Camera2 Viewfinder Surface
        Viewfinder(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Monitoring Overlays (Center 75%+ unobstructed)
        val vRes = state.videoResolution
        val resWidth = maxOf(vRes.width, vRes.height)
        val resHeight = minOf(vRes.width, vRes.height)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp, bottom = 58.dp),
            contentAlignment = Alignment.Center
        ) {
            MonitoringOverlays(
                state = state,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(resWidth.toFloat() / resHeight.toFloat(), matchHeightConstraintsFirst = true)
            )
        }

        // 3. Clean Viewfinder Mode Glowing REC Indicator
        if (state.isCleanViewfinder && state.isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 20.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(CinemaCrimson, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val mins = state.recordingTimeSeconds / 60
                val secs = state.recordingTimeSeconds % 60
                Text(
                    text = "REC %02d:%02d".format(mins, secs),
                    color = CinemaWhite,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    style = TabularScreenStyle
                )
            }
        }

        // 4. Top Status Bar (graphicsLayer fade + slide)
        if (hudProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationY = -(1f - hudProgress) * 44.dp.toPx()
                    }
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
                    onColorProfileSelected = { profile -> viewModel.cameraController.setColorProfile(profile) },
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
        }

        // 5. Left Monitoring Rail
        if (hudProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = 52.dp, bottom = 64.dp)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationX = -(1f - hudProgress) * 58.dp.toPx()
                    }
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
        }

        // 6. Right Cinema Action Rail
        if (hudProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 52.dp, bottom = 64.dp)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationX = (1f - hudProgress) * 72.dp.toPx()
                    }
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
        }

        // 7. Bottom Exposure Deck & Zoom Hub
        if (hudProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = hudProgress
                        translationY = (1f - hudProgress) * 64.dp.toPx()
                    }
            ) {
                BottomControlBar(
                    state = state,
                    activeDial = activeDial,
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
                    },
                    onLutClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.LUT) ActiveDial.NONE else ActiveDial.LUT)
                    },
                    onToolsClick = {
                        onActiveDialChanged(if (activeDial == ActiveDial.TOOLS) ActiveDial.NONE else ActiveDial.TOOLS)
                    }
                )
            }
        }

        // 8. Expandable Rotary Dial / LUT / Tools Wheel (purely graphicsLayer transition)
        if (dialProgress > 0.01f && lastActiveDial != ActiveDial.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 72.dp, end = 82.dp, bottom = 70.dp)
                    .graphicsLayer {
                        alpha = dialProgress
                        translationY = (1f - dialProgress) * 24.dp.toPx()
                        scaleY = 0.94f + 0.06f * dialProgress
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
                    onClose = { onActiveDialChanged(ActiveDial.NONE) }
                )
            }
        }

        // 9. Pro Cinema Suite Drawer (graphicsLayer slide-in from right)
        if (drawerProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = drawerProgress
                        translationX = (1f - drawerProgress) * 280.dp.toPx()
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
}

/**
 * Shared High-Precision Rotary Dial, LUT, & Tools Scrubber.
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
                            Text("SWITCH TO SPEED", color = CinemaCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                            Text("SWITCH TO ANGLE", color = CinemaCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                onSelectPreset = { preset ->
                    viewModel.cameraController.setLutPreset(preset)
                },
                onToggleBake = {
                    viewModel.cameraController.setLutBakeEnabled(!state.isLutBakeEnabled)
                },
                onClose = onClose
            )
        }
        ActiveDial.TOOLS -> {
            QuickMonitoringToolsTray(
                state = state,
                cameraController = viewModel.cameraController,
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
            .background(CinemaPillBgDark, RoundedCornerShape(14.dp))
            .border(0.75.dp, CinemaBorderActive, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "3D LUT PRESETS",
                    color = CinemaAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(4.dp.toPx())
                            drawRoundRect(
                                color = if (state.isLutBakeEnabled) CinemaAmber else CinemaElevated,
                                cornerRadius = r
                            )
                            drawRoundRect(
                                color = if (state.isLutBakeEnabled) CinemaAmber else CinemaBorderInactive,
                                cornerRadius = r,
                                style = Stroke(0.75.dp.toPx())
                            )
                        }
                        .clickable(onClick = onToggleBake)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (state.isLutBakeEnabled) "BAKE: ON" else "PREVIEW ONLY",
                        color = if (state.isLutBakeEnabled) CinemaObsidian else CinemaWhite,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close LUT Tray", tint = CinemaWhite)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LutPreset.entries.forEach { preset ->
                val isSelected = state.lutPreset == preset && (preset == LutPreset.OFF || state.showLutPreview)
                TrayChip(
                    label = preset.label,
                    isSelected = isSelected,
                    activeColor = CinemaAmber,
                    onClick = { onSelectPreset(preset) }
                )
            }
        }
    }
}

@Composable
private fun QuickMonitoringToolsTray(
    state: CinemaState,
    cameraController: Camera2Controller,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CinemaPillBgDark, RoundedCornerShape(14.dp))
            .border(0.75.dp, CinemaBorderActive, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MONITORING SCOPES & ASSISTS",
                color = CinemaAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close Tools Tray", tint = CinemaWhite)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TrayChip(
                label = "PEAKING",
                isSelected = state.showFocusPeaking,
                activeColor = CinemaAutoGreen,
                onClick = { cameraController.toggleFocusPeaking() }
            )
            TrayChip(
                label = "ZEBRAS",
                isSelected = state.showZebras,
                activeColor = CinemaGold,
                onClick = { cameraController.toggleZebras() }
            )
            TrayChip(
                label = "FALSE COLOR",
                isSelected = state.showFalseColor,
                activeColor = CinemaCrimson,
                onClick = { cameraController.toggleFalseColor() }
            )
            TrayChip(
                label = "HISTOGRAM",
                isSelected = state.showHistogram,
                activeColor = CinemaAmber,
                onClick = { cameraController.toggleHistogram() }
            )
            TrayChip(
                label = "WAVEFORM",
                isSelected = state.showWaveform,
                activeColor = CinemaCyan,
                onClick = { cameraController.toggleWaveform() }
            )
            TrayChip(
                label = "LEVELER",
                isSelected = state.showHorizonLeveler,
                activeColor = CinemaAutoGreen,
                onClick = { cameraController.toggleHorizonLeveler() }
            )
            TrayChip(
                label = "GRID",
                isSelected = state.showGrid,
                activeColor = CinemaAmber,
                onClick = { cameraController.toggleGrid() }
            )
            val guideLabel = if (state.frameGuide == FrameGuide.NONE) "GUIDES" else "GUIDE ${state.frameGuide.name.replace("RATIO_", "")}"
            TrayChip(
                label = guideLabel,
                isSelected = state.frameGuide != FrameGuide.NONE,
                activeColor = CinemaAmber,
                onClick = {
                    val nextGuide = when (state.frameGuide) {
                        FrameGuide.NONE -> FrameGuide.RATIO_2_39
                        FrameGuide.RATIO_2_39 -> FrameGuide.RATIO_16_9
                        FrameGuide.RATIO_16_9 -> FrameGuide.RATIO_4_3
                        FrameGuide.RATIO_4_3 -> FrameGuide.RATIO_1_1
                        FrameGuide.RATIO_1_1 -> FrameGuide.NONE
                    }
                    cameraController.setFrameGuide(nextGuide)
                }
            )
        }
    }
}

@Composable
private fun TrayChip(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .drawBehind {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(
                    color = if (isSelected) activeColor.copy(alpha = 0.22f) else CinemaElevated,
                    cornerRadius = r
                )
                drawRoundRect(
                    color = if (isSelected) activeColor else CinemaBorderInactive,
                    cornerRadius = r,
                    style = Stroke(0.75.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) activeColor else CinemaWhite,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
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
