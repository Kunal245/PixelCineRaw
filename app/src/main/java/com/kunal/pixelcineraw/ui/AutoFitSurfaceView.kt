package com.kunal.pixelcineraw.ui

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.DataSpace
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View

/**
 * Google's official Camera2 zero-stretch SurfaceView.
 * Maintains exact aspect ratio via onMeasure without any matrix distortion.
 * Configures 10-bit BT2020_HLG HDR or 8-bit SDR SurfaceControl dataspace.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var aspectRatioWidth = 0
    private var aspectRatioHeight = 0
    private var currentHdrState: Boolean? = null

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
     * Configures the SurfaceHolder format and SurfaceControl DataSpace for 10-bit HDR (RGBA_1010102 + BT2020_HLG)
     * or standard 8-bit SDR (RGBA_8888 + SRGB).
     */
    fun setHdrMode(isHdr: Boolean) {
        if (currentHdrState == isHdr) return
        currentHdrState = isHdr

        if (isHdr) {
            holder.setFormat(PixelFormat.RGBA_1010102)
            applyDataSpace(DataSpace.DATASPACE_BT2020_HLG)
        } else {
            holder.setFormat(PixelFormat.RGBA_8888)
            applyDataSpace(DataSpace.DATASPACE_SRGB)
        }
    }

    fun applyCurrentDataSpace() {
        val isHdr = currentHdrState == true
        val ds = if (isHdr) DataSpace.DATASPACE_BT2020_HLG else DataSpace.DATASPACE_SRGB
        applyDataSpace(ds)
    }

    private fun applyDataSpace(dataSpace: Int) {
        try {
            if (surfaceControl.isValid) {
                SurfaceControl.Transaction()
                    .setDataSpace(surfaceControl, dataSpace)
                    .apply()
            }
        } catch (e: Exception) {
            Log.w("AutoFitSurfaceView", "Failed to set SurfaceControl DataSpace ($dataSpace)", e)
        }
    }
}
