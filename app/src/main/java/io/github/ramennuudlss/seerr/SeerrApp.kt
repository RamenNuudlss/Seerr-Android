package io.github.ramennuudlss.seerr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SeerrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            RequestNotifier.CHANNEL_ID,
            getString(R.string.channel_requests),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.channel_requests_desc) }
        val instant = NotificationChannel(
            NtfyListenerService.INSTANT_CHANNEL_ID,
            getString(R.string.channel_instant),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_instant_desc) }
        val listener = NotificationChannel(
            NtfyListenerService.LISTENER_CHANNEL_ID,
            getString(R.string.channel_listener),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.channel_listener_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(channel, instant, listener))
        RequestNotifier.schedule(this)
        InstantAlerts.ensureRunning(this)
    }
}
