package io.github.ramennuudlss.seerr

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Native notifications for request updates.
 *
 * Seerr's own "Web Push" agent needs the browser Push API, which Android's WebView doesn't
 * have, so the app checks the server's request list itself (using the signed-in session)
 * and notifies on changes:
 *  - your request was approved / declined / became available
 *  - (admins and request managers) a new request is waiting for approval, or one failed
 */
class RequestNotifier(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        // Doubles as a watchdog that restarts the instant-alerts listener if Android killed it.
        InstantAlerts.ensureRunning(applicationContext)
        val base = prefs.serverUrl ?: return@withContext Result.success()
        if (!prefs.notificationsEnabled) return@withContext Result.success()
        // With instant alerts on, new/failed requests already arrive via ntfy.
        val instant = InstantAlerts.isConfigured(applicationContext)

        val cookie = withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(base)
        } ?: return@withContext Result.success() // not signed in
        val auth = Uri.parse(base).host?.let { prefs.httpAuth(it) }

        val me = Server.getJson("$base/api/v1/auth/me", cookie, auth)
            ?: return@withContext Result.success() // session expired or server offline
        val myId = me.optInt("id", -1)
        val permissions = me.optLong("permissions", 0L)
        val isManager = (permissions and PERM_ADMIN) != 0L || (permissions and PERM_MANAGE_REQUESTS) != 0L

        val list = Server.getJson(
            "$base/api/v1/request?take=40&skip=0&filter=all&sort=modified", cookie, auth
        ) ?: return@withContext Result.retry()
        val results = list.optJSONArray("results") ?: return@withContext Result.success()

        val stored = prefs.requestState?.let { runCatching { JSONObject(it) }.getOrNull() }
        val firstRun = stored == null
        val state = stored ?: JSONObject()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.optInt("id", -1).takeIf { it >= 0 }?.toString() ?: continue
            val media = r.optJSONObject("media") ?: continue
            val is4k = r.optBoolean("is4k", false)
            val reqStatus = r.optInt("status")
            val mediaStatus = if (is4k) media.optInt("status4k") else media.optInt("status")
            val requester = r.optJSONObject("requestedBy")
            val mine = requester?.optInt("id", -2) == myId
            val now = "$reqStatus:$mediaStatus"
            val prev = state.optString(id, "")
            state.put(id, now)

            if (firstRun || prev == now) continue
            val (prevReq, prevMedia) = prev.split(":").let {
                (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
            }

            val event: Pair<Int, Boolean>? = when {
                // New request from someone else that needs approval.
                prev.isEmpty() && isManager && !instant && !mine && reqStatus == REQ_PENDING ->
                    R.string.notif_new_request to true
                prev.isEmpty() -> null
                mine && reqStatus == REQ_APPROVED && prevReq == REQ_PENDING -> R.string.notif_approved to false
                mine && reqStatus == REQ_DECLINED && prevReq != REQ_DECLINED -> R.string.notif_declined to false
                mine && mediaStatus == MEDIA_AVAILABLE && prevMedia != MEDIA_AVAILABLE -> R.string.notif_available to false
                mine && mediaStatus == MEDIA_PARTIAL && prevMedia != MEDIA_PARTIAL &&
                    prevMedia != MEDIA_AVAILABLE -> R.string.notif_partial to false
                isManager && !instant && reqStatus == REQ_FAILED && prevReq != REQ_FAILED -> R.string.notif_failed to false
                else -> null
            }
            event ?: continue

            val type = media.optString("mediaType", "movie").ifEmpty { "movie" }
            val tmdbId = media.optInt("tmdbId", 0)
            val title = fetchTitle(base, type, tmdbId, cookie, auth) ?: "Request #$id"
            val (titleRes, isNew) = event
            val heading = applicationContext.getString(titleRes)
            val body = if (isNew) {
                applicationContext.getString(
                    R.string.notif_new_request_body,
                    requester?.optString("displayName").orEmpty().ifEmpty { "Someone" },
                    title
                )
            } else title
            val path = if (isNew) "/requests?filter=pending" else "/$type/$tmdbId"
            notify(id.toInt(), heading, body, path)
        }

        prefs.requestState = trim(state).toString()
        Result.success()
    }

    private fun fetchTitle(base: String, type: String, tmdbId: Int, cookie: String, auth: Pair<String, String>?): String? {
        if (tmdbId <= 0) return null
        val json = Server.getJson("$base/api/v1/$type/$tmdbId", cookie, auth) ?: return null
        return json.optString(if (type == "tv") "name" else "title").ifEmpty { null }
    }

    private fun notify(id: Int, title: String, body: String, path: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seerr)
            .setColor(ContextCompat.getColor(ctx, R.color.seerr_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }

    /** Keep the stored state small: only the most recently added entries. */
    private fun trim(state: JSONObject): JSONObject {
        val keys = state.keys().asSequence().toList()
        if (keys.size <= MAX_TRACKED) return state
        val keep = keys.mapNotNull { it.toIntOrNull() }.sortedDescending().take(MAX_TRACKED).toSet()
        return JSONObject().also { out ->
            keys.filter { k -> k.toIntOrNull()?.let { it in keep } == true }
                .forEach { out.put(it, state.get(it)) }
        }
    }

    companion object {
        const val CHANNEL_ID = "requests"
        private const val WORK_NAME = "request-notifications"
        private const val MAX_TRACKED = 400

        // Seerr enums (server/constants/media.ts, server/lib/permissions.ts)
        private const val REQ_PENDING = 1
        private const val REQ_APPROVED = 2
        private const val REQ_DECLINED = 3
        private const val REQ_FAILED = 4
        private const val MEDIA_PARTIAL = 4
        private const val MEDIA_AVAILABLE = 5
        private const val PERM_ADMIN = 2L
        private const val PERM_MANAGE_REQUESTS = 16L

        /** Starts or stops the periodic check to match the user's setting. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val prefs = Prefs(context)
            val needed = prefs.notificationsEnabled || InstantAlerts.isConfigured(context)
            if (!needed || prefs.serverUrl == null) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RequestNotifier>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
