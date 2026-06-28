package com.blowaway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

        SectionTitle("Notification filters")
        ToggleRow("Ignore alarms", settings.ignoreAlarms) { onUpdate(settings.copy(ignoreAlarms = it)) }
        ToggleRow("Ignore media", settings.ignoreMedia) { onUpdate(settings.copy(ignoreMedia = it)) }

        Text(
            "Detection, cooldown, listening window, and gesture timing are managed automatically.",
            style = MaterialTheme.typography.bodyMedium
        )
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
