package com.kunal.pixelcineraw.camera

/**
 * Immutable UI and camera engine state emitted to Jetpack Compose.
 */
data class CinemaState(
    // Sensor & Lens
    val selectedLens: LensSelection = LensSelection.WIDE_1X,
    val availableLenses: List<LensSelection> = listOf(LensSelection.ULTRAWIDE, LensSelection.WIDE_1X, LensSelection.CROP_2X),
    val currentSensorSpec: CameraSensorSpec? = null,

    // Exposure (Shutter & ISO)
    val isManualExposure: Boolean = true,
    val shutterUnit: ShutterUnit = ShutterUnit.ANGLE,
    val shutterAngle: Float = 180f, // 180 degree rule default
    val shutterSpeedNs: Long = 20_833_333L, // 1/48s for 24fps default
    val iso: Int = 100,
    val isAutoIso: Boolean = false,
    val autoIsoCap: Int = 3200,

    // Focus
    val focusMode: FocusMode = FocusMode.AUTO_CONTINUOUS,
    val focusDistance: Float = 0.0f, // 0.0 = infinity, max = minimumFocusDistance (macro)
    val tapToFocusPoint: Pair<Float, Float>? = null, // Normalized [0, 1] x, y

    // White Balance
    val isManualWb: Boolean = false,
    val wbKelvin: Int = 5600,
    val wbTint: Int = 0,

    // Video Specs
    val targetFps: Int = 24, // 24, 30, 60
    val videoResolution: CustomResolution = CustomResolution.UHD_4K,
    val supportedResolutions: List<CustomResolution> = listOf(
        CustomResolution.UHD_4K,
        CustomResolution.DCI_4K,
        CustomResolution.QHD_1440P,
        CustomResolution.FHD_1080P,
        CustomResolution.OPEN_GATE_4_3
    ),
    val videoCodec: VideoCodec = VideoCodec.HEVC_10BIT,
    val targetBitrateMbps: Int = 100, // 50 to 200 Mbps
    val colorProfile: ColorProfile = ColorProfile.FLAT_LOG,
    val recordingFormat: RecordingFormat = RecordingFormat.VIDEO_PRO,
    val oisEnabed: Boolean = true,
    val eisEnabled: Boolean = false,

    // Recording Runtime
    val isRecording: Boolean = false,
    val recordingTimeSeconds: Long = 0L,
    val recordedBytes: Long = 0L,
    val droppedFramesCount: Int = 0,
    val currentSavedFilePath: String? = null,

    // Monitoring Overlays
    val showHistogram: Boolean = true,
    val histogramData: HistogramData = HistogramData(),
    val showFocusPeaking: Boolean = false,
    val peakingColor: PeakingColor = PeakingColor.GREEN,
    val peakingSensitivity: Float = 0.6f, // 0.1 to 1.0
    val showZebras: Boolean = false,
    val zebraIreThreshold: Int = 95, // 70 to 100%
    val showLutPreview: Boolean = true,
    val frameGuide: FrameGuide = FrameGuide.RATIO_2_39,
    val showGrid: Boolean = true,

    // System Telemetry
    val batteryPercent: Int = 100,
    val remainingStorageGb: Float = 64.0f,
    val remainingRecordingMinutes: Int = 120,
    val thermalStatus: ThermalStatus = ThermalStatus.NOMINAL,
    val measuredFps: Float = 24.0f,
    val audioPeakDbL: Float = -60f,
    val audioPeakDbR: Float = -60f,
    val audioGain: Float = 1.0f,
    val isExternalMicConnected: Boolean = false
)

/**
 * Blackmagic-style customizable resolutions with aspect ratio categorization
 */
data class CustomResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val aspectLabel: String
) {
    companion object {
        val UHD_4K = CustomResolution("4K UHD (3840×2160)", 3840, 2160, "16:9")
        val DCI_4K = CustomResolution("4K DCI (4096×2160)", 4096, 2160, "1.90:1")
        val QHD_1440P = CustomResolution("2.5K QHD (2560×1440)", 2560, 1440, "16:9")
        val FHD_1080P = CustomResolution("1080p FHD (1920×1080)", 1920, 1080, "16:9")
        val HD_720P = CustomResolution("720p HD (1280×720)", 1280, 720, "16:9")
        val OPEN_GATE_4_3 = CustomResolution("4:3 Open Gate (3840×2880)", 3840, 2880, "4:3")
    }
}

enum class FrameGuide(val label: String, val aspectRatio: Float?) {
    NONE("Off", null),
    RATIO_2_39("2.39:1 CinemaScope", 2.39f),
    RATIO_16_9("16:9 Broadcast", 16f / 9f),
    RATIO_4_3("4:3 Open Gate", 4f / 3f),
    RATIO_1_1("1:1 Square", 1.0f)
}

enum class ThermalStatus(val label: String, val isWarning: Boolean) {
    NOMINAL("NORMAL", false),
    LIGHT("WARM", false),
    MODERATE("THROTTLING RISK", true),
    SEVERE("CRITICAL HEAT", true),
    EMERGENCY("EMERGENCY SHUTDOWN", true)
}

data class HistogramData(
    val redBins: FloatArray = FloatArray(32),
    val greenBins: FloatArray = FloatArray(32),
    val blueBins: FloatArray = FloatArray(32),
    val lumaBins: FloatArray = FloatArray(32)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HistogramData
        return redBins.contentEquals(other.redBins) &&
                greenBins.contentEquals(other.greenBins) &&
                blueBins.contentEquals(other.blueBins) &&
                lumaBins.contentEquals(other.lumaBins)
    }

    override fun hashCode(): Int {
        var result = redBins.contentHashCode()
        result = 31 * result + greenBins.contentHashCode()
        result = 31 * result + blueBins.contentHashCode()
        result = 31 * result + lumaBins.contentHashCode()
        return result
    }
}
