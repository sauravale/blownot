package com.blowaway.service

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import com.blowaway.core.state.StateMachine
import com.blowaway.data.db.NotificationDao
import com.blowaway.data.db.NotificationEntity
import com.blowaway.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BlowNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var stateMachine: StateMachine
    @Inject lateinit var diagnosticsRepository: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.enabled || shouldIgnore(sbn.notification.category, settings.ignoreAlarms, settings.ignoreMedia)) {
                return@launch
            }
            val now = System.currentTimeMillis()
            val ranking = Ranking()
            val importance = if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else 0
            notificationDao.upsert(
                NotificationEntity(
                    key = sbn.key,
                    timestampMillis = now,
                    packageName = sbn.packageName,
                    category = sbn.notification.category,
                    importance = importance,
                    activeUntilMillis = now + settings.listeningWindowMillis
                )
            )
            diagnosticsRepository.updateNotification(sbn.packageName, sbn.key)
            stateMachine.onHeadsUpDetected()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        scope.launch {
            notificationDao.deleteByKey(sbn.key)
            stateMachine.onIdle()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun shouldIgnore(category: String?, ignoreAlarms: Boolean, ignoreMedia: Boolean): Boolean {
        return (ignoreAlarms && category == android.app.Notification.CATEGORY_ALARM) ||
            (ignoreMedia && category == android.app.Notification.CATEGORY_TRANSPORT)
    }
}
