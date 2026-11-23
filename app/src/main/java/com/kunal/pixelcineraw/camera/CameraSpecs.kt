package com.kunal.pixelcineraw.camera

import android.graphics.Rect
import android.util.Range
import android.util.Size

/**
 * Detailed hardware capabilities probed from CameraCharacteristics for Google Pixel 9a
 * and modern Camera2 devices.
 */
data class CameraSensorSpec(
    val cameraId: String,
    val lensFacing: Int, // CameraCharacteristics.LENS_FACING_BACK / FRONT
    val focalLengths: FloatArray,
    val apertures: FloatArray,
    val isoRange: Range<Int>,
    val exposureTimeRange: Range<Long>,
    val maxAnalogSensitivity: Int,
    val minimumFocusDistance: Float, // > 0 indicates manual focus support (macro distance in diopters = 1/meters)
    val activeArraySize: Rect,
    val pixelArraySize: Size,
    val isManualSensorCapable: Boolean,
    val isRawCapable: Boolean,
    val is10BitDynamicRangeSupported: Boolean,
    val supportsOis: Boolean,
    val supportsEis: Boolean,
    val physicalCameraIds: Set<String>,
    val previewSizes: List<Size>,
    val videoSizes: List<Size>,
    val isUltraWide: Boolean,
    val isMainWide: Boolean,
    val sensorOrientation: Int = 90
) {
    val maxZoomRatio: Float = 8f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraSensorSpec
        return cameraId == other.cameraId
    }

    override fun hashCode(): Int {
        return cameraId.hashCode()
    }
}

enum class LensSelection(val label: String, val factor: Float) {
    ULTRAWIDE("0.5x UW", 0.5f),
    WIDE_1X("1x WIDE", 1.0f),
    CROP_2X("2x LOSSLESS", 2.0f)
}

enum class ShutterUnit {
    SPEED, // e.g. 1/48s
    ANGLE  // e.g. 180°
}

enum class ColorProfile(val label: String, val description: String) {
    REC709("Rec.709", "Standard Dynamic Range (SDR)"),
    FLAT_LOG("Pixel-Log", "Flat Log-like Contrast Curve for Grading"),
    HLG_10BIT("HLG 10-Bit", "Hybrid Log-Gamma 10-bit HDR (BT.2020)")
}

enum class RecordingFormat(val label: String) {
    HEVC_10BIT("10-Bit HEVC (Pro)"),
    RAW_DNG("RAW Bayer DNG (12-bit)")
}

enum class FocusMode {
    MANUAL,
    AUTO_CONTINUOUS,
    LOCKED
}

enum class PeakingColor(val label: String, val hexColor: Long) {
    RED("Red", 0xFFFF2A2AL),
    GREEN("Green", 0xFF00FF66L),
    CYAN("Cyan", 0xFF00E5FFL),
    YELLOW("Yellow", 0xFFFFD700L)
}
