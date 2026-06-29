package com.blowaway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blowaway.core.state.AppState
import com.blowaway.data.settings.AppSettings
import com.blowaway.service.DiagnosticsState

@Composable
fun DebugScreen(
    diagnostics: DiagnosticsState,
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onStartLiveMonitor: () -> Unit,
    onStopLiveMonitor: () -> Unit,
    onDebugGesture: (String) -> Unit,
    onDismissActivePopup: () -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Live diagnostics", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStartLiveMonitor, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("Live mic")
            }
            Button(
                onClick = onStopLiveMonitor,
                enabled = diagnostics.appState == AppState.DebugMicMonitor,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Text("Stop")
            }
        }

        Text("Startup timing", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onUpdateSettings(settings.copy(startupSettlingMillis = 0, startupCalibrationMillis = 0)) },
                modifier = Modifier.weight(1f)
            ) { Text("No wait") }
            OutlinedButton(
                onClick = { onUpdateSettings(settings.copy(startupSettlingMillis = 120, startupCalibrationMillis = 80)) },
                modifier = Modifier.weight(1f)
            ) { Text("Fast") }
            OutlinedButton(
                onClick = { onUpdateSettings(settings.copy(startupSettlingMillis = 350, startupCalibrationMillis = 250)) },
                modifier = Modifier.weight(1f)
            ) { Text("Current") }
        }
        TimingSlider(
            label = "Settling",
            valueMillis = settings.startupSettlingMillis,
            onValueChange = { onUpdateSettings(settings.copy(startupSettlingMillis = it)) }
        )
        TimingSlider(
            label = "Calibration",
            valueMillis = settings.startupCalibrationMillis,
            onValueChange = { onUpdateSettings(settings.copy(startupCalibrationMillis = it)) }
        )

        Text("Gesture tests", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onDebugGesture("up_high") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Swipe, contentDescription = null)
                Text("Up high")
            }
            Button(onClick = { onDebugGesture("left_high") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Swipe, contentDescription = null)
                Text("Left high")
            }
            Button(onClick = { onDebugGesture("right_high") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Swipe, contentDescription = null)
                Text("Right high")
            }
        }
        Button(onClick = onDismissActivePopup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Swipe, contentDescription = null)
            Text("Dismiss popup")
        }
        Waveform(diagnostics.waveform)
        Metric("Current RMS", diagnostics.rms)
        Metric("Peak", diagnostics.peak)
        Metric("Noise floor", diagnostics.noiseFloor)
        Metric("Blow confidence", diagnostics.blowConfidence)
        Metric("Speech confidence", diagnostics.speechConfidence)
        DebugRow("Detector reason", diagnostics.detectorReason.ifBlank { "None" })
        DebugRow("Current application", diagnostics.currentApplication.ifBlank { "None" })
        DebugRow("Current notification", diagnostics.currentNotificationKey.ifBlank { "None" })
        DebugRow("Accessibility bounds", diagnostics.accessibilityBounds?.flattenToString() ?: "Unavailable")
        DebugRow("Gesture coordinates", diagnostics.gestureCoordinates.ifBlank { "Unavailable" })
        DebugRow("State machine state", diagnostics.appState.name)
        DebugRow("Total dismissals", diagnostics.totalDismissals.toString())
        DebugRow("False triggers", diagnostics.falseTriggers.toString())
        DebugRow("Missed detections", diagnostics.missedDetections.toString())
    }
}

@Composable
private fun TimingSlider(label: String, valueMillis: Long, onValueChange: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DebugRow(label, "${valueMillis}ms")
        Slider(
            value = valueMillis.coerceIn(0, 1_000).toFloat(),
            onValueChange = { onValueChange((it / 20f).toInt() * 20L) },
            valueRange = 0f..1_000f,
            steps = 49,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Waveform(values: List<Float>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        if (values.isEmpty()) return@Canvas
        val step = size.width / values.size.coerceAtLeast(1)
        val mid = size.height / 2f
        values.zipWithNext().forEachIndexed { index, pair ->
            drawLine(
                color = Color(0xFF0EA5E9),
                start = Offset(index * step, mid - pair.first * mid),
                end = Offset((index + 1) * step, mid - pair.second * mid),
                strokeWidth = 3f
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: Float) {
    Column {
        DebugRow(label, "%.3f".format(value))
        LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) }
    )
}