package com.blowaway.service

import java.lang.ref.WeakReference

object AccessibilityBridge {
    private var serviceReference: WeakReference<BlowAccessibilityService>? = null

    fun attach(service: BlowAccessibilityService) {
        serviceReference = WeakReference(service)
    }

    fun detach(service: BlowAccessibilityService) {
        if (serviceReference?.get() === service) {
            serviceReference = null
        }
    }

    fun dismissHeadsUp(): Boolean {
        val service = serviceReference?.get() ?: return false
        service.dismissHeadsUp()
        return true
    }
}
