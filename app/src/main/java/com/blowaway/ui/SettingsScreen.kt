package com.blowaway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blowaway.data.settings.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("General")
        ToggleRow("Enable BlowAway", settings.enabled) { onUpdate(settings.copy(enabled = it)) }
        ToggleRow("Start on boot", settings.startOnBoot) { onUpdate(settings.copy(startOnBoot = it)) }
        ToggleRow("Foreground service enabled", settings.foregroundServiceEnabled) {
            onUpdate(settings.copy(foregroundServiceEnabled = it))
        }

        SectionTitle("Detection")
        SliderRow("Sensitivity", settings.sensitivity, 0.1f..1f) {
            onUpdate(settings.copy(sensitivity = it))
        }
        SliderRow("Cooldown", settings.cooldownMillis / 1_000f, 1f..8f) {
            onUpdate(settings.copy(cooldownMillis = (it * 1_000).toLong()))
        }
        Button(onClick = {
            onUpdate(
                settings.copy(
                    averageRms = 0.12f,
                    averageDurationMillis = 320,
                    peakAmplitude = 0.38f,
                    spectralCentroid = 3_300f
                )
            )
        }) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Text("Calibration")
        }

        SectionTitle("Notification Behaviour")
        SliderRow("Listening window", settings.listeningWindowMillis / 1_000f, 1f..8f) {
            onUpdate(settings.copy(listeningWindowMillis = (it * 1_000).toLong()))
        }
        SliderRow("Gesture duration", settings.gestureDurationMillis.toFloat(), 150f..250f) {
            onUpdate(settings.copy(gestureDurationMillis = it.toLong()))
        }

        SectionTitle("Conditions")
        ToggleRow("Screen must be on", settings.screenMustBeOn) { onUpdate(settings.copy(screenMustBeOn = it)) }
        ToggleRow("Device unlocked only", settings.deviceUnlockedOnly) { onUpdate(settings.copy(deviceUnlockedOnly = it)) }
        ToggleRow("Ignore alarms", settings.ignoreAlarms) { onUpdate(settings.copy(ignoreAlarms = it)) }
        ToggleRow("Ignore phone calls", settings.ignorePhoneCalls) { onUpdate(settings.copy(ignorePhoneCalls = it)) }
        ToggleRow("Ignore navigation", settings.ignoreNavigation) { onUpdate(settings.copy(ignoreNavigation = it)) }
        ToggleRow("Ignore media", settings.ignoreMedia) { onUpdate(settings.copy(ignoreMedia = it)) }
        ToggleRow("Ignore DND", settings.ignoreDnd) { onUpdate(settings.copy(ignoreDnd = it)) }
        ToggleRow("Ignore charging", settings.ignoreCharging) { onUpdate(settings.copy(ignoreCharging = it)) }

        SectionTitle("Per-app settings")
        Text("Whitelist and blacklist rules are stored locally and applied by package name.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label)
            Text("%.2f".format(value))
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
