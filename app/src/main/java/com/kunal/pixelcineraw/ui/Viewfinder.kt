package com.kunal.pixelcineraw.ui

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kunal.pixelcineraw.camera.CinemaViewModel
import kotlinx.coroutines.launch

/**
 * Cinema Viewfinder hosting native TextureView surface with aspect-ratio preservation
 * and animated Tap-to-Focus reticle.
 */
@Composable
fun Viewfinder(
    viewModel: CinemaViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var tapOffset by remember { mutableStateOf<Offset?>(null) }
    val reticleScale = remember { Animatable(1.4f) }
    val reticleAlpha = remember { Animatable(1.0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val normX = offset.x / size.width
                        val normY = offset.y / size.height
                        viewModel.cameraController.tapToFocus(normX, normY)

                        tapOffset = offset
                        coroutineScope.launch {
                            reticleScale.snapTo(1.4f)
                            reticleAlpha.snapTo(1.0f)
                            reticleScale.animateTo(1.0f, tween(250))
                            reticleAlpha.animateTo(0.0f, tween(1500))
                            tapOffset = null
                        }
                    }
                },
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                            surfaceTexture.setDefaultBufferSize(3840, 2160) // 4K native buffer
                            val surface = Surface(surfaceTexture)
                            viewModel.attachPreviewSurface(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            viewModel.detachPreviewSurface()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            }
        )

        // Animated Tap-to-Focus Cinema Reticle
        tapOffset?.let { offset ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = reticleScale.value
                val alpha = reticleAlpha.value
                val boxSize = 72.dp.toPx() * scale
                val half = boxSize / 2f

                // Outer square
                drawRect(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    topLeft = Offset(offset.x - half, offset.y - half),
                    size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Center crosshair
                val crossLength = 8.dp.toPx()
                drawLine(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    start = Offset(offset.x - crossLength, offset.y),
                    end = Offset(offset.x + crossLength, offset.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color(0xFFFFD700).copy(alpha = alpha),
                    start = Offset(offset.x, offset.y - crossLength),
                    end = Offset(offset.x, offset.y + crossLength),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
    }
}
