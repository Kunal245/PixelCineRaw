package com.kunal.pixelcineraw.camera

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Lightweight, zero-latency repository backed by SharedPreferences for persisting
 * cinema camera configurations across app relaunches.
 *
 * Saves settings asynchronously via SharedPreferences.Editor.apply() on background thread,
 * ensuring zero UI thread blocking or dropped frames.
 */
class CameraSettingsRepository(context: Context) {

    companion object {
        private const val TAG = "CameraSettingsRepo"
        private const val PREFS_NAME = "pixel_cine_raw_settings"

        // Keys: Format & Video
        private const val KEY_RES_WIDTH = "video_res_width"
        private const val KEY_RES_HEIGHT = "video_res_height"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_COLOR_PROFILE = "color_profile"
        private const val KEY_INTRA_MODE = "intra_frame_mode"
        private const val KEY_BITRATE_MBPS = "target_bitrate_mbps"
        private const val KEY_ISP_MODE = "isp_mode"
        private const val KEY_RECORD_WAV = "record_wav_sidecar"

        // Keys: Camera & Exposure
        private const val KEY_STABILIZATION_MODE = "stabilization_mode"
        private const val KEY_ORIENTATION_LOCK = "orientation_lock_mode"
        private const val KEY_SELECTED_LENS = "selected_lens"
        private const val KEY_ZOOM_RATIO = "zoom_ratio"
        private const val KEY_IS_MANUAL_EXPOSURE = "is_manual_exposure"
        private const val KEY_SHUTTER_UNIT = "shutter_unit"
        private const val KEY_SHUTTER_ANGLE = "shutter_angle"
        private const val KEY_SHUTTER_SPEED_NS = "shutter_speed_ns"
        private const val KEY_ISO = "iso"
        private const val KEY_ANTI_BANDING_MODE = "anti_banding_mode"
        private const val KEY_EV_COMPENSATION = "ev_compensation"
        private const val KEY_IS_MANUAL_WB = "is_manual_wb"
        private const val KEY_WB_KELVIN = "wb_kelvin"
        private const val KEY_WB_TINT = "wb_tint"
        private const val KEY_FOCUS_MODE = "focus_mode"
        private const val KEY_FOCUS_DISTANCE = "focus_distance"

        // Keys: Monitoring Overlays & Tools
        private const val KEY_SHOW_HISTOGRAM = "show_histogram"
        private const val KEY_SHOW_WAVEFORM = "show_waveform"
        private const val KEY_SHOW_FOCUS_PEAKING = "show_focus_peaking"
        private const val KEY_PEAKING_COLOR = "peaking_color"
        private const val KEY_SHOW_ZEBRAS = "show_zebras"
        private const val KEY_ZEBRA_THRESHOLD = "zebra_ire_threshold"
        private const val KEY_FRAME_GUIDE = "frame_guide"
        private const val KEY_SHOW_GRID = "show_grid"
        private const val KEY_SHOW_HORIZON = "show_horizon_leveler"
        private const val KEY_SHOW_FALSE_COLOR = "show_false_color"
        private const val KEY_SHOW_LUT_PREVIEW = "show_lut_preview"
        private const val KEY_LUT_PRESET = "lut_preset"
        private const val KEY_IS_LUT_BAKE = "is_lut_bake_enabled"
        private const val KEY_ANAMORPHIC = "anamorphic_de_squeeze"
        private const val KEY_GYROFLOW_LOGGING = "is_gyroflow_logging_enabled"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads saved settings and merges them into the initial default state.
     */
    fun loadSettings(defaultState: CinemaState): CinemaState {
        return try {
            val resWidth = prefs.getInt(KEY_RES_WIDTH, defaultState.videoResolution.width)
            val resHeight = prefs.getInt(KEY_RES_HEIGHT, defaultState.videoResolution.height)
            val resolution = defaultState.supportedResolutions.find { it.width == resWidth && it.height == resHeight }
                ?: CustomResolution.fromSize(resWidth, resHeight)

            val targetFps = prefs.getInt(KEY_TARGET_FPS, defaultState.targetFps)

            val colorProfile = prefs.getString(KEY_COLOR_PROFILE, null)?.let { name ->
                try { ColorProfile.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.colorProfile

            val intraMode = prefs.getString(KEY_INTRA_MODE, null)?.let { name ->
                try { IntraFrameMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.intraFrameMode

            val targetBitrate = prefs.getInt(KEY_BITRATE_MBPS, defaultState.targetBitrateMbps)

            val ispMode = prefs.getString(KEY_ISP_MODE, null)?.let { name ->
                try { IspMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.ispMode

            val recordWav = prefs.getBoolean(KEY_RECORD_WAV, defaultState.recordWavSidecar)

            val stabMode = prefs.getString(KEY_STABILIZATION_MODE, null)?.let { name ->
                try { StabilizationMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.stabilizationMode

            val orientLock = prefs.getString(KEY_ORIENTATION_LOCK, null)?.let { name ->
                try { OrientationLockMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.orientationLockMode

            val selectedLens = prefs.getString(KEY_SELECTED_LENS, null)?.let { name ->
                try { LensSelection.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.selectedLens

            val zoomRatio = prefs.getFloat(KEY_ZOOM_RATIO, defaultState.zoomRatio)
            val isManualExposure = prefs.getBoolean(KEY_IS_MANUAL_EXPOSURE, defaultState.isManualExposure)

            val shutterUnit = prefs.getString(KEY_SHUTTER_UNIT, null)?.let { name ->
                try { ShutterUnit.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.shutterUnit

            val shutterAngle = prefs.getFloat(KEY_SHUTTER_ANGLE, defaultState.shutterAngle)
            val shutterSpeedNs = prefs.getLong(KEY_SHUTTER_SPEED_NS, defaultState.shutterSpeedNs)
            val iso = prefs.getInt(KEY_ISO, defaultState.iso)
            val antiBandingMode = prefs.getString(KEY_ANTI_BANDING_MODE, null)?.let { name ->
                try { AntiBandingMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.antiBandingMode
            val evCompensation = prefs.getFloat(KEY_EV_COMPENSATION, defaultState.evCompensation)

            val isManualWb = prefs.getBoolean(KEY_IS_MANUAL_WB, defaultState.isManualWb)
            val wbKelvin = prefs.getInt(KEY_WB_KELVIN, defaultState.wbKelvin)
            val wbTint = prefs.getInt(KEY_WB_TINT, defaultState.wbTint)

            val focusMode = prefs.getString(KEY_FOCUS_MODE, null)?.let { name ->
                try { FocusMode.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.focusMode

            val focusDistance = prefs.getFloat(KEY_FOCUS_DISTANCE, defaultState.focusDistance)

            val showHistogram = prefs.getBoolean(KEY_SHOW_HISTOGRAM, defaultState.showHistogram)
            val showWaveform = prefs.getBoolean(KEY_SHOW_WAVEFORM, defaultState.showWaveform)
            val showFocusPeaking = prefs.getBoolean(KEY_SHOW_FOCUS_PEAKING, defaultState.showFocusPeaking)

            val peakingColor = prefs.getString(KEY_PEAKING_COLOR, null)?.let { name ->
                try { PeakingColor.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.peakingColor

            val showZebras = prefs.getBoolean(KEY_SHOW_ZEBRAS, defaultState.showZebras)
            val zebraThreshold = prefs.getInt(KEY_ZEBRA_THRESHOLD, defaultState.zebraIreThreshold)

            val frameGuide = prefs.getString(KEY_FRAME_GUIDE, null)?.let { name ->
                try { FrameGuide.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.frameGuide

            val showGrid = prefs.getBoolean(KEY_SHOW_GRID, defaultState.showGrid)
            val showHorizon = prefs.getBoolean(KEY_SHOW_HORIZON, defaultState.showHorizonLeveler)
            val showFalseColor = prefs.getBoolean(KEY_SHOW_FALSE_COLOR, defaultState.showFalseColor)
            val showLutPreview = prefs.getBoolean(KEY_SHOW_LUT_PREVIEW, defaultState.showLutPreview)

            val lutPreset = prefs.getString(KEY_LUT_PRESET, null)?.let { name ->
                try { LutPreset.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.lutPreset

            val isLutBake = prefs.getBoolean(KEY_IS_LUT_BAKE, defaultState.isLutBakeEnabled)

            val anamorphic = prefs.getString(KEY_ANAMORPHIC, null)?.let { name ->
                try { AnamorphicDeSqueeze.valueOf(name) } catch (_: Exception) { null }
            } ?: defaultState.anamorphicDeSqueeze

            val isGyroflow = prefs.getBoolean(KEY_GYROFLOW_LOGGING, defaultState.isGyroflowLoggingEnabled)

            defaultState.copy(
                videoResolution = resolution,
                targetFps = targetFps,
                colorProfile = colorProfile,
                intraFrameMode = intraMode,
                targetBitrateMbps = targetBitrate,
                ispMode = ispMode,
                recordWavSidecar = recordWav,
                stabilizationMode = stabMode,
                orientationLockMode = orientLock,
                selectedLens = selectedLens,
                zoomRatio = zoomRatio,
                isManualExposure = isManualExposure,
                shutterUnit = shutterUnit,
                shutterAngle = shutterAngle,
                shutterSpeedNs = shutterSpeedNs,
                iso = iso,
                antiBandingMode = antiBandingMode,
                evCompensation = evCompensation,
                isManualWb = isManualWb,
                wbKelvin = wbKelvin,
                wbTint = wbTint,
                focusMode = focusMode,
                focusDistance = focusDistance,
                showHistogram = showHistogram,
                showWaveform = showWaveform,
                showFocusPeaking = showFocusPeaking,
                peakingColor = peakingColor,
                showZebras = showZebras,
                zebraIreThreshold = zebraThreshold,
                frameGuide = frameGuide,
                showGrid = showGrid,
                showHorizonLeveler = showHorizon,
                showFalseColor = showFalseColor,
                showLutPreview = showLutPreview,
                lutPreset = lutPreset,
                isLutBakeEnabled = isLutBake,
                anamorphicDeSqueeze = anamorphic,
                isGyroflowLoggingEnabled = isGyroflow
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load camera settings, using defaults", e)
            defaultState
        }
    }

    /**
     * Asynchronously persists the current cinema camera state to SharedPreferences.
     */
    fun saveSettings(state: CinemaState) {
        prefs.edit().apply {
            putInt(KEY_RES_WIDTH, state.videoResolution.width)
            putInt(KEY_RES_HEIGHT, state.videoResolution.height)
            putInt(KEY_TARGET_FPS, state.targetFps)
            putString(KEY_COLOR_PROFILE, state.colorProfile.name)
            putString(KEY_INTRA_MODE, state.intraFrameMode.name)
            putInt(KEY_BITRATE_MBPS, state.targetBitrateMbps)
            putString(KEY_ISP_MODE, state.ispMode.name)
            putBoolean(KEY_RECORD_WAV, state.recordWavSidecar)

            putString(KEY_STABILIZATION_MODE, state.stabilizationMode.name)
            putString(KEY_ORIENTATION_LOCK, state.orientationLockMode.name)
            putString(KEY_SELECTED_LENS, state.selectedLens.name)
            putFloat(KEY_ZOOM_RATIO, state.zoomRatio)
            putBoolean(KEY_IS_MANUAL_EXPOSURE, state.isManualExposure)
            putString(KEY_SHUTTER_UNIT, state.shutterUnit.name)
            putFloat(KEY_SHUTTER_ANGLE, state.shutterAngle)
            putLong(KEY_SHUTTER_SPEED_NS, state.shutterSpeedNs)
            putInt(KEY_ISO, state.iso)
            putString(KEY_ANTI_BANDING_MODE, state.antiBandingMode.name)
            putFloat(KEY_EV_COMPENSATION, state.evCompensation)
            putBoolean(KEY_IS_MANUAL_WB, state.isManualWb)
            putInt(KEY_WB_KELVIN, state.wbKelvin)
            putInt(KEY_WB_TINT, state.wbTint)
            putString(KEY_FOCUS_MODE, state.focusMode.name)
            putFloat(KEY_FOCUS_DISTANCE, state.focusDistance)

            putBoolean(KEY_SHOW_HISTOGRAM, state.showHistogram)
            putBoolean(KEY_SHOW_WAVEFORM, state.showWaveform)
            putBoolean(KEY_SHOW_FOCUS_PEAKING, state.showFocusPeaking)
            putString(KEY_PEAKING_COLOR, state.peakingColor.name)
            putBoolean(KEY_SHOW_ZEBRAS, state.showZebras)
            putInt(KEY_ZEBRA_THRESHOLD, state.zebraIreThreshold)
            putString(KEY_FRAME_GUIDE, state.frameGuide.name)
            putBoolean(KEY_SHOW_GRID, state.showGrid)
            putBoolean(KEY_SHOW_HORIZON, state.showHorizonLeveler)
            putBoolean(KEY_SHOW_FALSE_COLOR, state.showFalseColor)
            putBoolean(KEY_SHOW_LUT_PREVIEW, state.showLutPreview)
            putString(KEY_LUT_PRESET, state.lutPreset.name)
            putBoolean(KEY_IS_LUT_BAKE, state.isLutBakeEnabled)
            putString(KEY_ANAMORPHIC, state.anamorphicDeSqueeze.name)
            putBoolean(KEY_GYROFLOW_LOGGING, state.isGyroflowLoggingEnabled)
            apply()
        }
    }

    /**
     * Clears all saved preferences and returns a fresh factory-default CinemaState.
     */
    fun resetToDefaults(): CinemaState {
        prefs.edit().clear().apply()
        return CinemaState()
    }
}
