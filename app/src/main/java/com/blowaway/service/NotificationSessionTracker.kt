package com.blowaway.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class NotificationSessionTracker @Inject constructor() {
    data class Snapshot(
        val active: Boolean,
        val key: String?,
        val packageName: String?,
        val acceptedAtMillis: Long,
        val activeUntilMillis: Long,
        val hardUntilMillis: Long,
        val removedAtMillis: Long?,
        val reason: String
    ) {
        fun remainingMillis(nowMillis: Long): Long = (hardUntilMillis - nowMillis).coerceAtLeast(0L)
    }

    private data class ActiveSession(
        val key: String,
        val packageName: String,
        val acceptedAtMillis: Long,
        val activeUntilMillis: Long,
        val hardUntilMillis: Long,
        val removedAtMillis: Long? = null
    )

    @Volatile private var activeSession: ActiveSession? = null

    fun markAccepted(
        key: String,
        packageName: String,
        acceptedAtMillis: Long,
        listeningWindowMillis: Long,
        hardCapMillis: Long
    ): Snapshot {
        val activeUntil = acceptedAtMillis + listeningWindowMillis.coerceAtLeast(1_000L)
        val hardUntil = acceptedAtMillis + max(activeUntil - acceptedAtMillis, hardCapMillis.coerceAtLeast(1_000L))
        val session = ActiveSession(
            key = key,
            packageName = packageName,
            acceptedAtMillis = acceptedAtMillis,
            activeUntilMillis = activeUntil,
            hardUntilMillis = hardUntil
        )
        activeSession = session
        return session.snapshot(acceptedAtMillis, "accepted")
    }

    fun markRemoved(key: String, removedAtMillis: Long): Snapshot {
        val current = activeSession
        return if (current?.key == key) {
            val removed = current.copy(removedAtMillis = removedAtMillis)
            activeSession = null
            removed.snapshot(removedAtMillis, "removed")
        } else {
            snapshot(removedAtMillis).copy(reason = "removed inactive key")
        }
    }

    fun snapshot(nowMillis: Long): Snapshot {
        val current = activeSession ?: return Snapshot(
            active = false,
            key = null,
            packageName = null,
            acceptedAtMillis = 0L,
            activeUntilMillis = 0L,
            hardUntilMillis = 0L,
            removedAtMillis = null,
            reason = "none"
        )
        if (nowMillis >= current.hardUntilMillis) {
            activeSession = null
            return current.snapshot(nowMillis, "expired")
        }
        return current.snapshot(nowMillis, "active")
    }

    fun clear(reason: String, nowMillis: Long = System.currentTimeMillis()): Snapshot {
        val current = activeSession
        activeSession = null
        return current?.snapshot(nowMillis, reason) ?: snapshot(nowMillis).copy(reason = reason)
    }

    private fun ActiveSession.snapshot(nowMillis: Long, reason: String): Snapshot {
        return Snapshot(
            active = removedAtMillis == null && nowMillis < hardUntilMillis,
            key = key,
            packageName = packageName,
            acceptedAtMillis = acceptedAtMillis,
            activeUntilMillis = activeUntilMillis,
            hardUntilMillis = hardUntilMillis,
            removedAtMillis = removedAtMillis,
            reason = reason
        )
    }
}
