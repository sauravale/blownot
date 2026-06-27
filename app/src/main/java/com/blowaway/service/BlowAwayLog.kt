package com.blowaway.service

import android.util.Log

object BlowAwayLog {
    private const val TAG = "BlowAway"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }
}
