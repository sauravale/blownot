package com.blowaway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blowaway.service.DiagnosticsState

@Composable
fun DebugScreen(diagnostics: DiagnosticsState) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Live diagnostics", style = MaterialTheme.typography.titleMedium)
        Waveform(diagnostics.waveform)
        Metric("Current RMS", diagnostics.rms)
        Metric("Noise floor", diagnostics.noiseFloor)
        Metric("Blow confidence", diagnostics.blowConfidence)
        Metric("Speech confidence", diagnostics.speechConfidence)
        DebugRow("Current application", diagnostics.currentApplication.ifBlank { "None" })
        DebugRow("Current notification", diagnostics.currentNotificationKey.ifBlank { "None" })
        DebugRow("Accessibility bounds", diagnostics.accessibilityBounds?.flattenToString() ?: "Unavailable")
        DebugRow("Gesture coordinates", diagnostics.gestureCoordinates.ifBlank { "Unavailable" })
        DebugRow("State machine state", diagnostics.appState.name)
        DebugRow("Cooldown timer", "${diagnostics.cooldownMillisRemaining} ms")
        DebugRow("Total dismissals", diagnostics.totalDismissals.toString())
        DebugRow("False triggers", diagnostics.falseTriggers.toString())
        DebugRow("Missed detections", diagnostics.missedDetections.toString())
        DebugRow("Debug logging export", "Available through Android Studio logcat and local Room exports")
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
