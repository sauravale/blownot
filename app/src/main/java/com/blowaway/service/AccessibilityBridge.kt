package com.blowaway.service

import java.lang.ref.WeakReference

object AccessibilityBridge {
    private var serviceReference: WeakReference<BlowAccessibilityService>? = null

    fun attach(service: BlowAccessibilityService) {
        serviceReference = WeakReference(service)
        BlowAwayLog.i("accessibility attached")
    }

    fun detach(service: BlowAccessibilityService) {
        if (serviceReference?.get() === service) {
            serviceReference = null
            BlowAwayLog.i("accessibility detached")
        }
    }

    fun dismissHeadsUp(): Boolean {
        val service = serviceReference?.get()
        if (service == null) {
            BlowAwayLog.w("dismiss requested but accessibility service is unavailable")
            return false
        }
        BlowAwayLog.i("dismiss requested via accessibility bridge")
        service.dismissHeadsUp()
        return true
    }

    fun hasVisibleHeadsUp(nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = 800): Boolean {
        return serviceReference?.get()?.hasVisibleHeadsUp(nowMillis, maxAgeMillis) == true
    }

    fun dispatchDebugGesture(kind: String): Boolean {
        val service = serviceReference?.get()
        if (service == null) {
            BlowAwayLog.w("debug gesture requested but accessibility service is unavailable")
            return false
        }
        service.dispatchDebugGesture(kind)
        return true
    }
}
