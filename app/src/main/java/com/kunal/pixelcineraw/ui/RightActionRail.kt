package com.kunal.pixelcineraw.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import java.util.Locale

/**
 * Ergonomic right-hand cinema thumb action rail.
 *
 * Houses the Torch video light, tactile concentric cinema shutter, live timecode + MB counter,
 * pause/resume toggle, last clip thumbnail preview, and AE/AF lock button.
 */
@Composable
fun RightActionRail(
    state: CinemaState,
    onRecordToggle: () -> Unit,
    onPauseToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onAeAfLockToggle: () -> Unit,
    onFlipCamera: () -> Unit = {},
    onToggleProDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(76.dp)
            .background(Color(0xB30C0E12))
            .border(width = 0.5.dp, color = Color(0x26FFFFFF))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top Cluster: Flip Camera, Pro Suite, Torch & AE/AF Lock
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Flip Camera Button (Front / Rear)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (state.isFrontCamera) Color(0xFF00E5FF) else Color(0x33FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (state.isFrontCamera) Color(0xFF66E5FF) else Color(0x22FFFFFF),
                        shape = CircleShape
                    )
                    .clickable(enabled = !state.isRecording) { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Flip Camera",
                        tint = if (state.isFrontCamera) Color.Black else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (state.isFrontCamera) "FRONT" else "REAR",
                        color = if (state.isFrontCamera) Color.Black else Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Pro Cinema Suite Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (state.isProToolsDrawerOpen) Color(0xFF00E5FF) else Color(0x26FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (state.isProToolsDrawerOpen) Color(0xFF80EEFF) else Color(0x22FFFFFF),
                        shape = CircleShape
                    )
                    .clickable { onToggleProDrawer() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Pro Suite",
                        tint = if (state.isProToolsDrawerOpen) Color.Black else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "PRO",
                        color = if (state.isProToolsDrawerOpen) Color.Black else Color.White,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Torch / Video Light Button (Disabled on front camera)
            val torchEnabled = !state.isFrontCamera
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (state.isTorchOn) Color(0xFFFFCC00) else if (!torchEnabled) Color(0x11FFFFFF) else Color(0x33FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (state.isTorchOn) Color(0xFFFFE066) else Color(0x22FFFFFF),
                        shape = CircleShape
                    )
                    .clickable(enabled = torchEnabled) { onTorchToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FlashlightOn,
                    contentDescription = "Torch Light",
                    tint = if (state.isTorchOn) Color.Black else if (!torchEnabled) Color(0x44FFFFFF) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // AE/AF Lock Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (state.isAeAfLocked) Color(0xFFFF3344) else Color(0x26FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (state.isAeAfLocked) Color(0xFFFF6677) else Color(0x22FFFFFF),
                        shape = CircleShape
                    )
                    .clickable { onAeAfLockToggle() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (state.isAeAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "AE/AF Lock",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "LOCK",
                        color = Color.White,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 2. Middle Cluster: Large Concentric Record Shutter Button & Pause Toggle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Live File Size Readout when Recording
            AnimatedVisibility(visible = state.isRecording) {
                val mbRecorded = state.recordedBytes / (1024 * 1024)
                Text(
                    text = "${mbRecorded}MB",
                    color = Color(0xCCFFFFFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Large Concentric Cinema Record Button
            val infiniteTransition = rememberInfiniteTransition(label = "RecPulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "RecordPulseScale"
            )

            Box(
                modifier = Modifier
                    .size(58.dp)
                    .scale(if (state.isRecording) pulseScale else 1.0f)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(2.5.dp, Color.White, CircleShape)
                    .clickable { onRecordToggle() },
                contentAlignment = Alignment.Center
            ) {
                // Inner concentric trigger
                Box(
                    modifier = Modifier
                        .size(if (state.isRecording) 24.dp else 44.dp)
                        .clip(if (state.isRecording) RoundedCornerShape(4.dp) else CircleShape)
                        .background(Color(0xFFFF1744))
                )
            }

            // Live Timecode below shutter
            if (state.isRecording) {
                val mins = state.recordingTimeSeconds / 60
                val secs = state.recordingTimeSeconds % 60
                val timeString = String.format(Locale.US, "%02d:%02d", mins, secs)
                Text(
                    text = timeString,
                    color = Color(0xFFFF1744),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )

                // Pause / Resume Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (state.isRecordingPaused) Color(0xFFFFCC00) else Color(0x33FFFFFF))
                        .border(1.dp, Color(0x44FFFFFF), CircleShape)
                        .clickable { onPauseToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = "Pause/Resume",
                        tint = if (state.isRecordingPaused) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3. Bottom Cluster: Quick Clip Thumbnail Preview
        val clipThumbnail = state.lastRecordedClipThumbnail
        val clipUri = state.lastRecordedClipUri

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1C23))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
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
                            android.util.Log.w("RightActionRail", "Failed to launch video viewer", e)
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
                    tint = Color(0x88FFFFFF),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
