package com.kunal.pixelcineraw.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.DataSpace
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.kunal.pixelcineraw.camera.ColorProfile

/**
 * Google's official Camera2 zero-stretch SurfaceView with true Actua OLED HDR boost.
 * Maintains exact aspect ratio via onMeasure without any matrix distortion.
 * Surface format is kept stable so surfaceDestroyed never fires during color profile switching.
 * Locks display pacing to exact targetFps during recording and gently tapers HDR EDR from 4.0x to 2.0x.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "AutoFitSurfaceView"
    }

    private var aspectRatioWidth = 0
    private var aspectRatioHeight = 0
    private var fullBleedMode = true
    private var isHdrEnabled = false
    private var activeProfile: ColorProfile = ColorProfile.REC709
    private var appliedProfile: ColorProfile? = null
    private var activeRecording: Boolean = false
    private var appliedRecording: Boolean? = null
    private var activeTargetFps: Int = 60
    private var appliedFrameRate: Float = -1f

    init {
        holder.addCallback(this)
    }

    /**
     * Resolves the parent Activity from the context hierarchy.
     */
    private fun getActivity(): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Enables or disables edge-to-edge full-bleed center-crop viewfinder filling.
     */
    fun setFullBleed(enabled: Boolean) {
        if (fullBleedMode != enabled) {
            fullBleedMode = enabled
            requestLayout()
        }
    }

    /**
     * Sets the aspect ratio for this view.
     * The size of the view will be measured based on the ratio calculated from the parameters.
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "Dimensions cannot be negative." }
        if (aspectRatioWidth != width || aspectRatioHeight != height) {
            aspectRatioWidth = width
            aspectRatioHeight = height
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)

        if (fullBleedMode || aspectRatioWidth == 0 || aspectRatioHeight == 0) {
            // Fill 100% edge-to-edge; HlgGlRenderer.computeAspectMatrix performs 1:1 square-pixel center-crop
            setMeasuredDimension(width, height)
        } else {
            // Fits within parent preserving aspect ratio (aspect-fit / letterbox / pillarbox)
            if (width < height * aspectRatioWidth / aspectRatioHeight) {
                setMeasuredDimension(width, width * aspectRatioHeight / aspectRatioWidth)
            } else {
                setMeasuredDimension(height * aspectRatioWidth / aspectRatioHeight, height)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        post {
            applyColorProfileSurfaceState(
                profile = activeProfile,
                force = true,
                isRecording = activeRecording,
                targetFps = activeTargetFps
            )
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        post {
            applyColorProfileSurfaceState(
                profile = activeProfile,
                force = true,
                isRecording = activeRecording,
                targetFps = activeTargetFps
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        appliedProfile = null
        appliedRecording = null
        appliedFrameRate = -1f
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        post {
            applyColorProfileSurfaceState(
                profile = activeProfile,
                force = true,
                isRecording = activeRecording,
                targetFps = activeTargetFps
            )
        }
    }

    /**
     * Applies color profile surface state and recording thermal pacing across all modes:
     * - HLG10: DATASPACE_BT2020_HLG + ExtendedRangeBrightness (4.0x idle, 2.0x recording)
     * - HDR10+: DATASPACE_BT2020_PQ + ExtendedRangeBrightness (4.0x idle, 2.0x recording)
     * - REC.709 & PIXEL-LOG: DATASPACE_SRGB + 1.0x ExtendedRangeBrightness + COLOR_MODE_DEFAULT
     * - Recording active: locks Surface.setFrameRate(targetFps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
     */
    fun applyColorProfileSurfaceState(
        profile: ColorProfile,
        force: Boolean = false,
        isRecording: Boolean = activeRecording,
        targetFps: Int = activeTargetFps
    ) {
        val targetFrameRate = if (isRecording) targetFps.toFloat() else 0f
        if (!force &&
            activeProfile == profile &&
            appliedProfile == profile &&
            activeRecording == isRecording &&
            appliedRecording == isRecording &&
            activeTargetFps == targetFps &&
            appliedFrameRate == targetFrameRate
        ) {
            return
        }

        activeProfile = profile
        activeRecording = isRecording
        activeTargetFps = targetFps
        isHdrEnabled = profile == ColorProfile.HLG_10BIT || profile == ColorProfile.HDR10_PLUS

        // Ensure Activity window colorMode matches active profile immediately
        getActivity()?.let { activity ->
            val targetColorMode = if (isHdrEnabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (activity.window.colorMode != targetColorMode) {
                activity.window.colorMode = targetColorMode
            }
        }

        if (force || appliedProfile != profile || appliedRecording != isRecording) {
            applyHdrBoostInternal(profile, isRecording)
        }
        if (force || appliedFrameRate != targetFrameRate) {
            applyFrameRateLockInternal(isRecording, targetFps)
        }
    }

    fun setHdrBoost(enabled: Boolean) {
        val profile = if (enabled) ColorProfile.HLG_10BIT else ColorProfile.REC709
        applyColorProfileSurfaceState(profile)
    }

    fun setHdrMode(profile: ColorProfile, force: Boolean = false) {
        applyColorProfileSurfaceState(profile, force)
    }

    private fun applyFrameRateLockInternal(isRecording: Boolean, targetFps: Int) {
        try {
            val surf = holder.surface
            if (surf != null && surf.isValid) {
                if (isRecording) {
                    val fpsFloat = targetFps.toFloat()
                    surf.setFrameRate(fpsFloat, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    appliedFrameRate = fpsFloat
                    Log.i(TAG, "Locked OLED display pacing to ${targetFps}Hz (FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)")
                } else {
                    surf.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    appliedFrameRate = 0f
                    Log.i(TAG, "Restored adaptive OLED display pacing (0Hz DEFAULT)")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update Surface frame rate", e)
        }
    }

    private fun applyHdrBoostInternal(profile: ColorProfile, isRecording: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val sc = surfaceControl
                if (sc != null && sc.isValid) {
                    val hdrRatio = if (isRecording) 2.0f else 4.0f
                    val transaction = SurfaceControl.Transaction()
                    when (profile) {
                        ColorProfile.HLG_10BIT -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_BT2020_HLG)
                            transaction.setExtendedRangeBrightness(sc, hdrRatio, hdrRatio)
                            Log.i(TAG, "Applied HDR boost on SurfaceControl: HLG10, dataspace=BT2020_HLG (${hdrRatio}x)")
                        }
                        ColorProfile.HDR10_PLUS -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_BT2020_PQ)
                            transaction.setExtendedRangeBrightness(sc, hdrRatio, hdrRatio)
                            Log.i(TAG, "Applied HDR boost on SurfaceControl: HDR10+, dataspace=BT2020_PQ (${hdrRatio}x)")
                        }
                        else -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_SRGB)
                            transaction.setExtendedRangeBrightness(sc, 1.0f, 1.0f)
                            Log.i(TAG, "Applied SDR mode on SurfaceControl: dataspace=SRGB (1.0x)")
                        }
                    }
                    transaction.apply()
                    appliedProfile = profile
                    appliedRecording = isRecording
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set HDR boost on SurfaceControl", e)
            }
        }
    }
}
