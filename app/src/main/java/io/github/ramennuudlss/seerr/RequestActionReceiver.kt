package io.github.ramennuudlss.seerr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/** Handles the Approve / Decline buttons on a new-request notification. */
class RequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (requestId < 0) return

        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "request-action") {
            try {
                val base = Prefs(app).serverUrl ?: return@thread
                val action = if (approve) "approve" else "decline"
                val res = Server.request(
                    "POST", "$base/api/v1/request/$requestId/$action",
                    Server.cookie(base), Server.proxyAuth(app, base)
                )
                val result = when {
                    res.ok -> app.getString(if (approve) R.string.action_approved else R.string.action_declined)
                    res.code == 401 || res.code == 403 -> app.getString(R.string.action_not_allowed)
                    else -> app.getString(R.string.action_failed)
                }
                showResult(app, notificationId, result, title, autoDismiss = res.ok)
            } finally {
                pending.finish()
            }
        }
    }

    private fun showResult(context: Context, id: Int, result: String, title: String, autoDismiss: Boolean) {
        if (!NtfyListenerService.canPostNotifications(context)) return
        val builder = NotificationCompat.Builder(context, NtfyListenerService.INSTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seerr)
            .setColor(ContextCompat.getColor(context, R.color.seerr_accent))
            .setContentTitle(result)
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
        if (autoDismiss) builder.setTimeoutAfter(5_000)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_APPROVE = "approve"
        private const val EXTRA_TITLE = "title"

        fun pendingIntent(
            context: Context, requestId: Int, notificationId: Int, title: String, approve: Boolean,
        ): PendingIntent {
            val intent = Intent(context, RequestActionReceiver::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_APPROVE, approve)
                .putExtra(EXTRA_TITLE, title)
            // Distinct request codes so Approve and Decline don't overwrite each other.
            val requestCode = notificationId * 2 + if (approve) 0 else 1
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
