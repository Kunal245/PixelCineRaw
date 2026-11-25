package com.kunal.pixelcineraw.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.DataSpace
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.kunal.pixelcineraw.camera.ColorProfile

/**
 * Google's official Camera2 zero-stretch SurfaceView with true Actua OLED HDR boost.
 * Maintains exact aspect ratio via onMeasure without any matrix distortion.
 * Surface format is kept stable so surfaceDestroyed never fires during color profile switching.
 * Automatically applies DATASPACE_BT2020_HLG/PQ and extended-range brightness (4.0x) on Android 14+.
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
    private var isHdrEnabled = false
    private var activeProfile: ColorProfile = ColorProfile.REC709
    private var appliedProfile: ColorProfile? = null

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

        if (aspectRatioWidth == 0 || aspectRatioHeight == 0) {
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
        post { applyColorProfileSurfaceState(activeProfile, force = true) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        post { applyColorProfileSurfaceState(activeProfile, force = true) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        appliedProfile = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        post { applyColorProfileSurfaceState(activeProfile, force = true) }
    }

    /**
     * Applies color profile surface state across all modes and orientations:
     * - HLG10: DATASPACE_BT2020_HLG + 4.0x ExtendedRangeBrightness + ActivityInfo.COLOR_MODE_HDR
     * - HDR10+: DATASPACE_BT2020_PQ + 4.0x ExtendedRangeBrightness + ActivityInfo.COLOR_MODE_HDR
     * - REC.709 & PIXEL-LOG: DATASPACE_SRGB + 1.0x ExtendedRangeBrightness + ActivityInfo.COLOR_MODE_DEFAULT
     */
    fun applyColorProfileSurfaceState(profile: ColorProfile, force: Boolean = false) {
        if (!force && activeProfile == profile && appliedProfile == profile) return
        activeProfile = profile
        isHdrEnabled = profile == ColorProfile.HLG_10BIT || profile == ColorProfile.HDR10_PLUS

        // Ensure Activity window colorMode matches active profile immediately
        getActivity()?.let { activity ->
            val targetColorMode = if (isHdrEnabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            if (activity.window.colorMode != targetColorMode) {
                activity.window.colorMode = targetColorMode
            }
        }

        applyHdrBoostInternal(profile)
    }

    fun setHdrBoost(enabled: Boolean) {
        val profile = if (enabled) ColorProfile.HLG_10BIT else ColorProfile.REC709
        applyColorProfileSurfaceState(profile)
    }

    fun setHdrMode(profile: ColorProfile, force: Boolean = false) {
        applyColorProfileSurfaceState(profile, force)
    }

    private fun applyHdrBoostInternal(profile: ColorProfile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val sc = surfaceControl
                if (sc != null && sc.isValid) {
                    val transaction = SurfaceControl.Transaction()
                    when (profile) {
                        ColorProfile.HLG_10BIT -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_BT2020_HLG)
                            transaction.setExtendedRangeBrightness(sc, 4.0f, 4.0f)
                            Log.i(TAG, "Applied HDR boost on SurfaceControl: HLG10, dataspace=BT2020_HLG (4.0x)")
                        }
                        ColorProfile.HDR10_PLUS -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_BT2020_PQ)
                            transaction.setExtendedRangeBrightness(sc, 4.0f, 4.0f)
                            Log.i(TAG, "Applied HDR boost on SurfaceControl: HDR10+, dataspace=BT2020_PQ (4.0x)")
                        }
                        else -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_SRGB)
                            transaction.setExtendedRangeBrightness(sc, 1.0f, 1.0f)
                            Log.i(TAG, "Applied SDR mode on SurfaceControl: dataspace=SRGB (1.0x)")
                        }
                    }
                    transaction.apply()
                    appliedProfile = profile
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set HDR boost on SurfaceControl", e)
            }
        }
    }
}
