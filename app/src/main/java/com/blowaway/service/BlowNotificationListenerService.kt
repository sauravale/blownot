package com.blowaway.service

import android.app.Notification
import android.app.NotificationManager
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
    private var activeNotificationKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            val settings = settingsRepository.settings.first()
            val ranking = Ranking()
            val hasRanking = currentRanking.getRanking(sbn.key, ranking)
            val importance = if (hasRanking) ranking.importance else 0
            val suppressedPeek = hasRanking &&
                ranking.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0
            if (!settings.enabled || shouldIgnore(sbn, settings.ignoreAlarms, settings.ignoreMedia, importance, suppressedPeek)) {
                BlowAwayLog.d("notification ignored package=${sbn.packageName} key=${sbn.key} category=${sbn.notification.category} importance=$importance suppressedPeek=$suppressedPeek ongoing=${sbn.isOngoing} flags=${sbn.notification.flags}")
                return@launch
            }
            val now = System.currentTimeMillis()
            BlowAwayLog.i("notification accepted package=${sbn.packageName} key=${sbn.key} category=${sbn.notification.category} importance=$importance suppressedPeek=$suppressedPeek windowMs=${settings.listeningWindowMillis}")
            activeNotificationKey = sbn.key
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
            BlowAwayLog.i("notification removed package=${sbn.packageName} key=${sbn.key}")
            notificationDao.deleteByKey(sbn.key)
            if (activeNotificationKey == sbn.key) {
                activeNotificationKey = null
                stateMachine.onIdle()
            } else {
                BlowAwayLog.d("ignored removal for inactive notification key=${sbn.key}")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun shouldIgnore(
        sbn: StatusBarNotification,
        ignoreAlarms: Boolean,
        ignoreMedia: Boolean,
        importance: Int,
        suppressedPeek: Boolean
    ): Boolean {
        val category = sbn.notification.category
        val flags = sbn.notification.flags
        val alarmAllowed = category == Notification.CATEGORY_ALARM && !ignoreAlarms
        val persistentSystemNotification = !alarmAllowed && (
            sbn.isOngoing ||
                flags and Notification.FLAG_ONGOING_EVENT != 0 ||
                flags and Notification.FLAG_NO_CLEAR != 0
            )
        val notLikelyHeadsUp = importance < NotificationManager.IMPORTANCE_HIGH || suppressedPeek
        return persistentSystemNotification ||
            notLikelyHeadsUp ||
            (sbn.packageName == "com.android.systemui" && sbn.key.contains("charging", ignoreCase = true)) ||
            (ignoreAlarms && category == Notification.CATEGORY_ALARM) ||
            (ignoreMedia && category == Notification.CATEGORY_TRANSPORT)
    }
}
