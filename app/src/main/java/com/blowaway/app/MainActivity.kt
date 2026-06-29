package com.blowaway.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.blowaway.BuildConfig
import com.blowaway.service.MicrophoneForegroundService
import com.blowaway.ui.BlowAwayTheme
import com.blowaway.ui.DebugScreen
import com.blowaway.ui.MainViewModel
import com.blowaway.ui.RecordingLabScreen
import com.blowaway.ui.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlowAwayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BlowAwayApp(
                        openAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        openNotificationAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        startService = {
                            val intent = Intent(this, MicrophoneForegroundService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        },
                        shareFile = ::shareFile
                    )
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording lab export"))
    }
}

@Composable
private fun BlowAwayApp(
    openAccessibility: () -> Unit,
    openNotificationAccess: () -> Unit,
    startService: () -> Unit,
    shareFile: (File) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val recordingLab by viewModel.recordingLab.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingMicAction?.invoke() ?: startService()
            pendingMicAction = null
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModel.postTestNotification()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    label = { Text("Debug") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("Lab") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("BlowAway", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = openAccessibility, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("Accessibility")
                }
                Button(onClick = openNotificationAccess, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                    Text("Notifications")
                }
            }
            Button(
                onClick = {
                    pendingMicAction = { startService() }
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("Start protected listening")
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.postTestNotification()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.NotificationAdd, contentDescription = null)
                Text("Test notification")
            }
            Spacer(Modifier.height(2.dp))
            when (selectedTab) {
                0 -> SettingsScreen(settings = settings, onUpdate = viewModel::updateSettings)
                1 -> DebugScreen(
                    diagnostics = diagnostics,
                    settings = settings,
                    onUpdateSettings = viewModel::updateSettings,
                    onStartLiveMonitor = {
                        pendingMicAction = {
                            startService()
                            viewModel.startDebugMicMonitor()
                        }
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopLiveMonitor = viewModel::stopDebugMicMonitor,
                    onDebugGesture = viewModel::dispatchDebugGesture,
                    onDismissActivePopup = viewModel::dismissActivePopup
                )
                else -> RecordingLabScreen(
                    state = recordingLab,
                    onStartRecording = { label ->
                        pendingMicAction = {
                            startService()
                            viewModel.startLabRecording(label)
                        }
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopRecording = viewModel::stopLabRecording,
                    onExport = { shareFile(viewModel.exportLabArchive()) },
                    onClear = viewModel::clearLabRecordings
                )
            }
        }
    }
}
