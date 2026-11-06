package io.github.ramennuudlss.seerr

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import kotlin.math.abs
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Keeps a streaming connection to the ntfy topic Seerr publishes to and turns each message
 * into a notification within a second or two. ntfy sends a keepalive every ~45s, so the
 * connection is cheap to hold open. Reconnects with backoff, immediately on network changes,
 * and catches up on anything missed using the last message id.
 */
class NtfyListenerService : Service() {

    @Volatile private var running = false
    @Volatile private var reconnectRequested = false
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private val recentIds = ArrayDeque<String>()

    private var managerCheckedAt = 0L
    private var isManager = false

    /** What the ongoing notification currently says (null = connected and listening). */
    @Volatile private var listenerStatus: String? = null
    @Volatile private var listenerConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() promptly after startForegroundService(),
        // even if the service is about to stop again.
        ServiceCompat.startForeground(
            this,
            LISTENER_NOTIFICATION_ID,
            listenerNotification(listenerConnected, listenerStatus),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
        )
        if (!InstantAlerts.isConfigured(this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) reconnectNow()
        if (worker?.isAlive != true) {
            running = true
            worker = thread(name = "ntfy-listener") { listenLoop() }
            registerNetworkCallback()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        connection?.disconnect()
        worker?.interrupt()
        super.onDestroy()
    }

    // ------------------------------------------------------------ connection

    private fun listenLoop() {
        var backoff = MIN_BACKOFF
        while (running) {
            val prefs = Prefs(this)
            val server = prefs.ntfyServer
            val topic = prefs.ntfyTopic
            if (server.isNullOrBlank() || topic.isNullOrBlank()) break
            var connectedOk = false
            try {
                val url = buildString {
                    append(server.trimEnd('/')).append('/').append(Uri.encode(topic)).append("/json")
                    prefs.ntfyLastId?.let { append("?since=").append(Uri.encode(it)) }
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000 // ntfy keepalives arrive every ~45s
                conn.setRequestProperty("User-Agent", "${Server.USER_AGENT_SUFFIX}/${BuildConfig.VERSION_NAME}")
                prefs.ntfyAuth?.let { conn.setRequestProperty("Authorization", it) }

                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    updateListenerNotification(getString(R.string.instant_status_auth))
                    backoff = MAX_BACKOFF
                    throw IOException("ntfy auth failed ($code)")
                }
                if (code !in 200..299) throw IOException("ntfy HTTP $code")

                connectedOk = true
                backoff = MIN_BACKOFF
                updateListenerNotification(null)
                conn.inputStream.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) handleLine(line)
                    }
                }
            } catch (_: Exception) {
                // Fall through to reconnect.
            } finally {
                connection?.disconnect()
                connection = null
            }
            if (!running) break
            if (connectedOk) updateListenerNotification(getString(R.string.instant_status_reconnecting))
            if (reconnectRequested) {
                reconnectRequested = false
                continue
            }
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                // Woken early by a network change or restart.
            }
            reconnectRequested = false
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private fun reconnectNow() {
        reconnectRequested = true
        connection?.disconnect()
        worker?.interrupt()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                // Switching Wi-Fi <-> mobile data kills the old socket silently: reconnect now.
                if (previous != null && previous != network) reconnectNow()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    // ------------------------------------------------------------ messages

    private fun handleLine(line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (json.optString("event") != "message") return
        val id = json.optString("id")
        if (id.isNotEmpty()) {
            if (id in recentIds) return
            recentIds.addLast(id)
            while (recentIds.size > 50) recentIds.removeFirst()
            Prefs(this).ntfyLastId = id
        }

        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seerr:ntfy-message")
        wakeLock.acquire(15_000)
        try {
            showMessage(id, json)
        } catch (_: Exception) {
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun showMessage(id: String, json: JSONObject) {
        if (!canPostNotifications(this)) return
        val msg = parse(json.optString("title"), json.optString("message"))
        val notificationId = (id.ifEmpty { System.nanoTime().toString() }).hashCode()

        // Seerr only includes a link when its Application URL is set; use it when present.
        val clickPath = json.optString("click").takeIf { it.isNotEmpty() }
            ?.let { Uri.parse(it).path }
            ?.takeIf { it.length > 1 }
        // ntfy delivers the poster as "attachment" (Seerr publishes it as "attach").
        val posterUrl = (json.optJSONObject("attachment") ?: json.optJSONObject("attach"))
            ?.optString("url")?.takeIf { it.startsWith("http") }
        val poster = posterUrl?.let { loadBitmap(it) }
        val sentAt = json.optLong("time", 0L) * 1000L
        val pending = findPendingRequest(clickPath, msg.requester, sentAt)

        val path = clickPath
            ?: pending?.let { "/${it.mediaType}/${it.tmdbId}" }
            ?: if (msg.requester != null) "/requests" else "/"
        val open = PendingIntent.getActivity(
            this,
            notificationId,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Collapsed: title + "Requested by alex · Pending approval".
        // Expanded: the same line plus a short synopsis.
        val summary = listOfNotNull(
            msg.requester?.let { getString(R.string.notif_requested_by, it) },
            msg.status,
        ).joinToString(" · ").ifEmpty { msg.overview?.let { shorten(it, 90) }.orEmpty() }
        val synopsis = msg.overview?.takeIf { msg.requester != null }?.let { shorten(it, 180) }
        val expanded = if (synopsis != null) "$summary\n\n$synopsis" else summary

        val builder = NotificationCompat.Builder(this, INSTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seerr)
            .setColor(ContextCompat.getColor(this, R.color.seerr_accent))
            .setSubText(msg.event)
            .setContentTitle(msg.subject)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setLargeIcon(poster)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(if (sentAt > 0) sentAt else System.currentTimeMillis())
            .setShowWhen(true)
        if (pending != null) {
            builder.addAction(
                0, getString(R.string.action_approve),
                RequestActionReceiver.pendingIntent(this, pending.id, notificationId, msg.subject, approve = true)
            )
            builder.addAction(
                0, getString(R.string.action_decline),
                RequestActionReceiver.pendingIntent(this, pending.id, notificationId, msg.subject, approve = false)
            )
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    }

    private data class PendingMatch(val id: Int, val mediaType: String, val tmdbId: Int)

    /**
     * Finds the pending request a message is about, so the notification can offer
     * Approve/Decline. Matches on the link's TMDB id when Seerr sends one; otherwise on the
     * requester's name and the request having been made around the time of the message.
     * Returns null if the signed-in user can't manage requests.
     */
    private fun findPendingRequest(clickPath: String?, requester: String?, sentAt: Long): PendingMatch? {
        val link = clickPath?.let { Regex("""^/(movie|tv)/(\d+)""").find(it) }
        if (link == null && requester == null) return null // not a request message
        val base = Prefs(this).serverUrl ?: return null
        val cookie = Server.cookie(base) ?: return null
        val auth = Server.proxyAuth(this, base)
        if (!canManage(base, cookie, auth)) return null
        val list = Server.getJson(
            "$base/api/v1/request?take=20&skip=0&filter=pending&sort=added", cookie, auth
        ) ?: return null
        val results = list.optJSONArray("results") ?: return null
        val reference = if (sentAt > 0) sentAt else System.currentTimeMillis()

        var best: PendingMatch? = null
        var bestGap = Long.MAX_VALUE
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (r.optInt("status") != 1) continue
            val media = r.optJSONObject("media") ?: continue
            val type = media.optString("mediaType")
            val tmdbId = media.optInt("tmdbId")
            val candidate = PendingMatch(r.optInt("id"), type, tmdbId)
            if (link != null) {
                if (type == link.groupValues[1] && tmdbId.toString() == link.groupValues[2]) return candidate
                continue
            }
            val name = r.optJSONObject("requestedBy")?.optString("displayName").orEmpty()
            if (!name.equals(requester, ignoreCase = true)) continue
            val created = runCatching { Instant.parse(r.optString("createdAt")).toEpochMilli() }.getOrNull()
                ?: continue
            val gap = abs(created - reference)
            if (gap <= MATCH_WINDOW_MS && gap < bestGap) {
                best = candidate
                bestGap = gap
            }
        }
        return best
    }

    private fun canManage(base: String, cookie: String, auth: Pair<String, String>?): Boolean {
        val now = System.currentTimeMillis()
        if (now - managerCheckedAt < 60 * 60 * 1000L) return isManager
        val me = Server.getJson("$base/api/v1/auth/me", cookie, auth) ?: return false
        isManager = Server.canManageRequests(me.optLong("permissions", 0L))
        managerCheckedAt = now
        return isManager
    }

    private fun loadBitmap(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.let { bmp ->
        val max = 400
        if (bmp.width <= max && bmp.height <= max) bmp
        else {
            val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
    }

    // ------------------------------------------------------------ ongoing notification

    private fun listenerNotification(connected: Boolean, status: String? = null): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = status ?: if (connected) getString(R.string.instant_status_listening)
        else getString(R.string.instant_status_connecting)
        return NotificationCompat.Builder(this, LISTENER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seerr)
            .setContentTitle(getString(R.string.instant_listener_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    /** [status] null means connected and listening. */
    private fun updateListenerNotification(status: String?) {
        listenerStatus = status
        listenerConnected = status == null
        if (!canPostNotifications(this)) return
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(this).notify(
            LISTENER_NOTIFICATION_ID, listenerNotification(connected = status == null, status = status)
        )
    }

    companion object {
        const val ACTION_RESTART = "io.github.ramennuudlss.seerr.RESTART_LISTENER"
        const val INSTANT_CHANNEL_ID = "instant"
        const val LISTENER_CHANNEL_ID = "listener"
        private const val LISTENER_NOTIFICATION_ID = 1001
        private const val MATCH_WINDOW_MS = 10 * 60_000L
        private const val MIN_BACKOFF = 2_000L
        private const val MAX_BACKOFF = 5 * 60_000L

        fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /** The pieces of one of Seerr's ntfy messages. */
        data class ParsedMessage(
            val event: String?,
            val subject: String,
            val requester: String?,
            val status: String?,
            val overview: String?,
        )

        /**
         * Seerr titles its messages "<event> - <title>" and writes the body as the synopsis
         * followed by "**Requested By:** name" and "**Request Status:** status" lines.
         * Labels are localised, so the fields are read by position, not by label text.
         */
        fun parse(title: String, message: String): ParsedMessage {
            val dash = title.indexOf(" - ")
            val event = if (dash > 0) title.substring(0, dash).trim() else null
            val subject = (if (dash > 0) title.substring(dash + 3) else title).trim()
                .ifEmpty { "Seerr" }
            val field = Regex("""^\*\*(.+?):\*\*\s*(.+)$""")
            val values = mutableListOf<String>()
            val text = mutableListOf<String>()
            for (raw in message.lines()) {
                val line = raw.trim()
                val m = field.find(line)
                if (m != null) values += cleanMarkdown(m.groupValues[2])
                else if (values.isEmpty()) text += line
            }
            val overview = cleanMarkdown(text.joinToString(" ").replace(Regex("""\s+"""), " "))
                .ifEmpty { null }
            return ParsedMessage(event, subject, values.getOrNull(0), values.getOrNull(1), overview)
        }

        /** Cuts text at a word boundary near [max] characters. */
        fun shorten(text: String, max: Int): String {
            if (text.length <= max) return text
            val cut = text.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            return text.substring(0, cut).trimEnd(',', '.', ';', ':', ' ') + "…"
        }

        /** Seerr's ntfy messages are Markdown with escaped punctuation; show them as plain text. */
        fun cleanMarkdown(text: String): String =
            text.replace("**", "")
                .replace(Regex("""\\([\\`*_{}\[\]()#+\-.!|>~<])"""), "$1")
                .trim()
    }
}
