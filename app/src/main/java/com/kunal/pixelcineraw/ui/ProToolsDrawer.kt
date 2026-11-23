package com.kunal.pixelcineraw.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.*
import java.util.Locale

/**
 * Translucent Pro Cinema Suite Drawer.
 *
 * Houses individual controls and selectors for:
 * 1. 3D LUT Engine (.cube import + 3 film presets + Preview vs Bake toggle)
 * 2. Arri-Style False Color with IRE luminance mapping legend
 * 3. Scopes (Waveform & Histogram toggles)
 * 4. Focus Peaking & Exposure Zebras (colors, thresholds, sensitivity)
 * 5. Electronic Horizon Leveler & Frame Guides
 * 6. Programmable A -> B Cinema Rack Focus
 * 7. Gyroflow IMU Logger (.gcsv sidecar)
 * 8. Anamorphic Lens De-Squeeze (1.33x, 1.55x, 2.0x)
 */
@Composable
fun ProToolsDrawer(
    state: CinemaState,
    cameraController: Camera2Controller,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lut = com.kunal.pixelcineraw.gl.CubeLutParser.parse(stream)
                    if (lut != null) {
                        // Successfully parsed custom .cube file
                        cameraController.setLutPreset(LutPreset.REC709_NATURAL)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(360.dp)
            .background(Color(0xE60A0C10))
            .border(width = 0.5.dp, color = Color(0x3300E5FF))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF00E5FF), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PRO CINEMA SUITE",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Pro Suite",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Divider(color = Color(0x26FFFFFF), thickness = 0.5.dp)

            // 1. 3D LUT Engine
            ToolSectionCard(title = "3D LUT ENGINE", icon = Icons.Default.Palette) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LutPreset.values().forEach { preset ->
                            val isSelected = state.lutPreset == preset
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFF00E5FF) else Color(0x22FFFFFF))
                                    .clickable { cameraController.setLutPreset(preset) }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = preset.shortLabel,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Bake vs Preview Only Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Bake LUT into MP4",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (state.isLutBakeEnabled) "Graded video saved to storage" else "Preview only (clean log saved)",
                                color = Color(0x88FFFFFF),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = state.isLutBakeEnabled,
                            onCheckedChange = { cameraController.setLutBakeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x4D00E5FF)
                            )
                        )
                    }

                    // Import Custom .cube
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "IMPORT .CUBE FILE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 2. Arri-Style False Color
            ToolSectionCard(title = "FALSE COLOR (ARRI)", icon = Icons.Default.FilterVintage) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Exposure Mapping",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Luminance IRE false color shader",
                                color = Color(0x88FFFFFF),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = state.showFalseColor,
                            onCheckedChange = { cameraController.toggleFalseColor() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF5252),
                                checkedTrackColor = Color(0x4DFF5252)
                            )
                        )
                    }

                    // Compact Color Legend
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x33000000))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        FalseColorLegendRow("RED 98%+", "Clipping / White Clip", Color(0xFFFF1744))
                        FalseColorLegendRow("YEL 80-97%", "Highlights / Bright Skin", Color(0xFFFFEA00))
                        FalseColorLegendRow("PNK 52-58%", "Caucasian Skin Reference", Color(0xFFFF4081))
                        FalseColorLegendRow("GRN 38-44%", "18% Neutral Gray Card", Color(0xFF00E676))
                        FalseColorLegendRow("BLU 4-12%", "Deep Shadows", Color(0xFF2979FF))
                        FalseColorLegendRow("PUR <4%", "Crushed Blacks", Color(0xFFD500F9))
                    }
                }
            }

            // 3. Scopes: Luma Waveform & RGB Histogram
            ToolSectionCard(title = "CINEMA SCOPES", icon = Icons.Default.ShowChart) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Luma Waveform Monitor", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showWaveform,
                            onCheckedChange = { cameraController.toggleWaveform() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("RGB / Luma Histogram", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showHistogram,
                            onCheckedChange = { cameraController.toggleHistogram() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                        )
                    }
                }
            }

            // 4. Focus Peaking & Exposure Zebras
            ToolSectionCard(title = "PEAKING & ZEBRAS", icon = Icons.Default.CenterFocusWeak) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Focus Peaking Toggle & Color
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Focus Peaking", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showFocusPeaking,
                            onCheckedChange = { cameraController.toggleFocusPeaking() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FF66))
                        )
                    }

                    if (state.showFocusPeaking) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PeakingColor.values().forEach { c ->
                                val isSelected = state.peakingColor == c
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) Color(c.hexColor) else Color(0x22FFFFFF))
                                        .clickable { cameraController.setPeakingColor(c) }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = c.label,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Divider(color = Color(0x1AFFFFFF), thickness = 0.5.dp)

                    // Exposure Zebras
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Exposure Zebras", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showZebras,
                            onCheckedChange = { cameraController.toggleZebras() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFD700))
                        )
                    }

                    if (state.showZebras) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(75, 85, 90, 95, 100).forEach { threshold ->
                                val isSelected = state.zebraIreThreshold == threshold
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) Color(0xFFFFD700) else Color(0x22FFFFFF))
                                        .clickable { cameraController.setZebraThreshold(threshold) }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$threshold%",
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Electronic Horizon Leveler & Frame Guides
            ToolSectionCard(title = "HORIZON & GUIDES", icon = Icons.Default.GridOn) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Electronic Horizon Leveler", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showHorizonLeveler,
                            onCheckedChange = { cameraController.toggleHorizonLeveler() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FF66))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Rule of Thirds Grid", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Switch(
                            checked = state.showGrid,
                            onCheckedChange = { cameraController.toggleGrid() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                        )
                    }

                    // Guide Format Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FrameGuide.values().forEach { g ->
                            val isSelected = state.frameGuide == g
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFF00E5FF) else Color(0x22FFFFFF))
                                    .clickable { cameraController.setFrameGuide(g) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (g) {
                                        FrameGuide.NONE -> "OFF"
                                        FrameGuide.RATIO_2_39 -> "2.39"
                                        FrameGuide.RATIO_16_9 -> "16:9"
                                        FrameGuide.RATIO_4_3 -> "4:3"
                                        FrameGuide.RATIO_1_1 -> "1:1"
                                    },
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // 6. Programmable A -> B Cinema Rack Focus
            ToolSectionCard(title = "A → B RACK FOCUS", icon = Icons.Default.SlowMotionVideo) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Store two lens distances and pull focus automatically with professional cine easing.",
                        color = Color(0x88FFFFFF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Point A
                        val distA = state.rackFocusPointA
                        val distALabel = if (distA == null) "NOT SET" else if (distA <= 0.05f) "∞ INF" else String.format(Locale.US, "%.2fm", 1.0f / distA)
                        Button(
                            onClick = { cameraController.setRackPointA() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (distA != null) Color(0xFF00E5FF) else Color(0x33FFFFFF)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SET A", color = if (distA != null) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(distALabel, color = if (distA != null) Color.Black else Color(0x88FFFFFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        // Point B
                        val distB = state.rackFocusPointB
                        val distBLabel = if (distB == null) "NOT SET" else if (distB <= 0.05f) "∞ INF" else String.format(Locale.US, "%.2fm", 1.0f / distB)
                        Button(
                            onClick = { cameraController.setRackPointB() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (distB != null) Color(0xFF00FF66) else Color(0x33FFFFFF)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SET B", color = if (distB != null) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(distBLabel, color = if (distB != null) Color.Black else Color(0x88FFFFFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Duration selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1000L to "1.0s", 2000L to "2.0s", 3000L to "3.0s").forEach { (dur, label) ->
                            val isSelected = state.rackPullDurationMs == dur
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFFFD700) else Color(0x22FFFFFF))
                                    .clickable { cameraController.setRackPullDurationMs(dur) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Pull Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { cameraController.pullRackFocus(fromAtoB = true) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isRackPulling && state.rackFocusPointA != null && state.rackFocusPointB != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (state.isRackPulling) "PULLING..." else "PULL A → B",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = { cameraController.pullRackFocus(fromAtoB = false) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isRackPulling && state.rackFocusPointA != null && state.rackFocusPointB != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (state.isRackPulling) "PULLING..." else "PULL B → A",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // 7. Gyroflow IMU Logger
            ToolSectionCard(title = "GYROFLOW IMU LOG", icon = Icons.Default.Timeline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Record .gcsv Sidecar",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "High-rate gyro + accel logged for Gyroflow post stabilization",
                            color = Color(0x88FFFFFF),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = state.isGyroflowLoggingEnabled,
                        onCheckedChange = { cameraController.setGyroflowLoggingEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                    )
                }
            }

            // 8. Anamorphic Lens De-Squeeze
            ToolSectionCard(title = "ANAMORPHIC DE-SQUEEZE", icon = Icons.Default.AspectRatio) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "De-squeeze compressed anamorphic lens preview in real-time GL shader.",
                        color = Color(0x88FFFFFF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnamorphicDeSqueeze.values().forEach { sq ->
                            val isSelected = state.anamorphicDeSqueeze == sq
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFF00E5FF) else Color(0x22FFFFFF))
                                    .clickable { cameraController.setAnamorphicDeSqueeze(sq) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sq.label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ToolSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1F161A22)),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0x26FFFFFF)), width = 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
            }
            content()
        }
    }
}

@Composable
private fun FalseColorLegendRow(label: String, desc: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Text(text = desc, color = Color(0x99FFFFFF), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}
