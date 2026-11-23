package com.kunal.pixelcineraw.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View

/**
 * Google's official Camera2 zero-stretch SurfaceView.
 * Maintains exact aspect ratio via onMeasure without any matrix distortion.
 * Surface format is kept stable so surfaceDestroyed never fires during color profile switching.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var aspectRatioWidth = 0
    private var aspectRatioHeight = 0

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

    /**
     * Applies HDR brightness boost and BT.2020 HLG data space directly to the native SurfaceControl
     * without modifying SurfaceHolder format or destroying the active camera Surface.
     * On Pixel 9a (Android 14+), SurfaceFlinger utilizes extended range brightness to boost
     * OLED specular highlights.
     */
    fun setHdrBoost(enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val sc = surfaceControl
                if (sc.isValid) {
                    val transaction = android.view.SurfaceControl.Transaction()
                    if (enabled) {
                        transaction.setDataSpace(sc, android.hardware.DataSpace.DATASPACE_BT2020_HLG)
                        transaction.setExtendedRangeBrightness(sc, 4.0f, 4.0f)
                    } else {
                        transaction.setDataSpace(sc, android.hardware.DataSpace.DATASPACE_SRGB)
                        transaction.setExtendedRangeBrightness(sc, 1.0f, 1.0f)
                    }
                    transaction.apply()
                }
            } catch (e: Throwable) {
                android.util.Log.w("AutoFitSurfaceView", "Failed to set HDR boost on SurfaceControl", e)
            }
        }
    }
}
