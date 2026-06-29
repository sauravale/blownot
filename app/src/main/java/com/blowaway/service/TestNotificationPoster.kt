package com.blowaway.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blowaway.app.MainActivity
import com.blowaway.core.state.StateMachine
import com.blowaway.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class TestNotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val stateMachine: StateMachine,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val notificationSessionTracker: NotificationSessionTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun post() {
        ensureChannel()
        BlowAwayLog.i("posting test notification")
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BlowAway test notification")
            .setContentText("Use this heads-up notification to test blow dismissal.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, false)
            .build()

        val notificationId = nextNotificationId.getAndIncrement()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        scope.launch {
            val settings = settingsRepository.settings.first()
            val key = "test-notification:$notificationId"
            val now = System.currentTimeMillis()
            val session = notificationSessionTracker.markAccepted(
                key = key,
                packageName = context.packageName,
                acceptedAtMillis = now,
                listeningWindowMillis = settings.listeningWindowMillis,
                hardCapMillis = TEST_NOTIFICATION_SESSION_HARD_CAP_MILLIS
            )
            diagnosticsRepository.updateNotification(context.packageName, key)
            diagnosticsRepository.updateCooldown(settings.listeningWindowMillis)
            BlowAwayLog.i("test notification opened listening window windowMs=${settings.listeningWindowMillis} sessionRemaining=${session.remainingMillis(now)}")
            stateMachine.onHeadsUpDetected()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BlowAway test notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Local heads-up notifications for testing BlowAway detection."
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "blowaway_test_notifications"
        const val TEST_NOTIFICATION_SESSION_HARD_CAP_MILLIS = 12_000L
        val nextNotificationId = AtomicInteger(9001)
    }
}
