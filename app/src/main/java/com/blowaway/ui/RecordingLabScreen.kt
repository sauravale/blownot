package com.blowaway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blowaway.service.RecordingLabState

private val ScenarioLabels = listOf(
    "Ambient quiet",
    "Ambient noisy",
    "Speech",
    "Blow close",
    "Blow normal",
    "Blow far",
    "Failed blow"
)

@Composable
fun RecordingLabScreen(
    state: RecordingLabState,
    onStartRecording: (String) -> Unit,
    onStopRecording: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Recording lab", style = MaterialTheme.typography.titleMedium)
        Text("Capture short labelled WAV clips for detector tuning.", style = MaterialTheme.typography.bodyMedium)
        LabStatus("Status", state.status)
        LabStatus("Clips", state.clipCount.toString())
        if (state.lastClipPath.isNotBlank()) LabStatus("Last clip", state.lastClipPath)
        if (state.lastExportPath.isNotBlank()) LabStatus("Last export", state.lastExportPath)

        Text("Scenarios", style = MaterialTheme.typography.titleSmall)
        ScenarioLabels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { label ->
                    Button(
                        onClick = { onStartRecording(label) },
                        enabled = !state.isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                        Text(label)
                    }
                }
                if (row.size == 1) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }

        Button(
            onClick = onStopRecording,
            enabled = state.isRecording,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Text("Stop recording")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onExport, enabled = state.clipCount > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Text("Export")
            }
            OutlinedButton(onClick = onClear, enabled = !state.isRecording && state.clipCount > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text("Clear")
            }
        }
    }
}

@Composable
private fun LabStatus(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
