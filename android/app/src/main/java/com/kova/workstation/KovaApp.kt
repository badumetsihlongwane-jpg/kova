package com.kova.workstation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class KovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_WORKSTATION,
                "Active Workstation Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps your remote Windows workstation session alive in the background."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_WORKSTATION = "kova_workstation_session"
    }
}
