package com.kunal.pixelcineraw.ui

import android.content.Context
import android.hardware.DataSpace
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

/**
 * Google's official Camera2 zero-stretch SurfaceView with true Actua OLED HDR boost.
 * Maintains exact aspect ratio via onMeasure without any matrix distortion.
 * Surface format is kept stable so surfaceDestroyed never fires during color profile switching.
 * Automatically applies DATASPACE_BT2020_HLG and extended-range brightness (4.0x) on Android 14+.
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

    init {
        holder.addCallback(this)
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
        post { applyHdrBoostInternal(activeProfile) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        post { applyHdrBoostInternal(activeProfile) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        appliedProfile = null
    }

    /**
     * Applies HDR brightness boost and BT.2020 HLG data space directly to the native SurfaceControl
     * without modifying SurfaceHolder format or destroying the active camera Surface.
     * On Pixel 9a (Android 14+), SurfaceFlinger utilizes extended range brightness to boost
     * OLED specular highlights up to 4.0x peak Actua luminance.
     */
    private var activeProfile: com.kunal.pixelcineraw.camera.ColorProfile = com.kunal.pixelcineraw.camera.ColorProfile.REC709
    private var appliedProfile: com.kunal.pixelcineraw.camera.ColorProfile? = null

    fun setHdrBoost(enabled: Boolean) {
        val profile = if (enabled) com.kunal.pixelcineraw.camera.ColorProfile.HLG_10BIT else com.kunal.pixelcineraw.camera.ColorProfile.REC709
        setHdrMode(profile)
    }

    fun setHdrMode(profile: com.kunal.pixelcineraw.camera.ColorProfile, force: Boolean = false) {
        if (!force && activeProfile == profile && appliedProfile == profile) return
        activeProfile = profile
        isHdrEnabled = profile == com.kunal.pixelcineraw.camera.ColorProfile.HLG_10BIT || profile == com.kunal.pixelcineraw.camera.ColorProfile.HDR10_PLUS
        applyHdrBoostInternal(profile)
    }

    private fun applyHdrBoostInternal(profile: com.kunal.pixelcineraw.camera.ColorProfile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val sc = surfaceControl
                if (sc != null && sc.isValid) {
                    val transaction = SurfaceControl.Transaction()
                    when (profile) {
                        com.kunal.pixelcineraw.camera.ColorProfile.HLG_10BIT -> {
                            transaction.setDataSpace(sc, DataSpace.DATASPACE_BT2020_HLG)
                            transaction.setExtendedRangeBrightness(sc, 4.0f, 4.0f)
                            Log.i(TAG, "Applied HDR boost on SurfaceControl: HLG10, dataspace=BT2020_HLG (4.0x)")
                        }
                        com.kunal.pixelcineraw.camera.ColorProfile.HDR10_PLUS -> {
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
