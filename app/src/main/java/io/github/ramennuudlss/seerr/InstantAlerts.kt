package io.github.ramennuudlss.seerr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Instant new-request alerts.
 *
 * Seerr has a built-in ntfy notification agent that publishes every new request the moment
 * it's made. The app turns that agent on (when the signed-in user is a Seerr admin) and keeps
 * a live connection to the same ntfy topic in [NtfyListenerService], so alerts arrive in
 * seconds with no extra app, Firebase or polling.
 */
object InstantAlerts {

    const val DEFAULT_SERVER = "https://ntfy.sh"

    /** Seerr notification types the app needs: pending, failed, auto-approved, auto-requested. */
    const val REQUEST_TYPES = 2 or 16 or 128 or 4096

    // ------------------------------------------------------------ service control

    fun isConfigured(context: Context): Boolean {
        val p = Prefs(context)
        return p.ntfyEnabled && !p.ntfyServer.isNullOrBlank() && !p.ntfyTopic.isNullOrBlank()
    }

    /** Starts the listener if it should be running. Safe to call from anywhere. */
    fun ensureRunning(context: Context) {
        if (!isConfigured(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, NtfyListenerService::class.java))
        } catch (_: Exception) {
            // Android blocks starting it from the background unless the app is exempt from
            // battery optimisation; the next app launch or boot will start it.
        }
    }

    /** (Re)starts the listener with the current settings. */
    fun restart(context: Context) {
        if (!isConfigured(context)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyListenerService::class.java).setAction(NtfyListenerService.ACTION_RESTART)
            )
        } catch (_: Exception) {
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NtfyListenerService::class.java))
    }

    fun disable(context: Context) {
        Prefs(context).ntfyEnabled = false
        stop(context)
    }

    /** Saves the topic to listen on and starts listening. */
    fun activate(context: Context, server: String, topic: String, auth: String?) {
        val p = Prefs(context)
        p.ntfyServer = server.trim().trimEnd('/')
        p.ntfyTopic = topic.trim()
        p.ntfyAuth = auth
        p.ntfyLastId = null
        p.ntfyEnabled = true
        restart(context)
        RequestNotifier.schedule(context)
    }

    fun isBatteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun randomTopic(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return "seerr-" + (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    // ------------------------------------------------------------ Seerr's ntfy agent settings

    /** What the app found in Seerr's ntfy notification settings. */
    sealed class ServerState {
        /** Signed-in user isn't an admin (or isn't signed in), so settings can't be read. */
        data object NotAdmin : ServerState()

        data class Failed(val reason: String) : ServerState()

        /** ntfy agent is already on; listen to the same topic. */
        data class Configured(
            val config: JSONObject,
            val server: String,
            val topic: String,
            val auth: String?,
            val missingRequestTypes: Boolean,
        ) : ServerState()

        /** ntfy agent is off or incomplete; the app can set it up. */
        data class NotConfigured(val config: JSONObject) : ServerState()
    }

    private fun settingsUrl(base: String) = "$base/api/v1/settings/notifications/ntfy"

    /** Reads Seerr's ntfy agent settings. Blocking; call off the main thread. */
    fun readServerState(context: Context): ServerState {
        val base = Prefs(context).serverUrl ?: return ServerState.Failed("No server set")
        val res = Server.request("GET", settingsUrl(base), Server.cookie(base), Server.proxyAuth(context, base))
        if (res.code == 401 || res.code == 403) return ServerState.NotAdmin
        if (!res.ok) return ServerState.Failed(if (res.code == -1) res.body.orEmpty() else "HTTP ${res.code}")
        val config = runCatching { JSONObject(res.body.orEmpty()) }.getOrNull()
            ?: return ServerState.Failed("Unexpected response")
        val opts = config.optJSONObject("options") ?: JSONObject()
        val url = opts.optString("url").trim()
        val topic = opts.optString("topic").trim()
        if (!config.optBoolean("enabled") || url.isEmpty() || topic.isEmpty()) {
            return ServerState.NotConfigured(config)
        }
        val types = config.optInt("types", 0)
        val missing = types != 0 && (types and REQUEST_TYPES) != REQUEST_TYPES
        return ServerState.Configured(
            config = config,
            server = url.trimEnd('/').removeSuffix("/$topic"),
            topic = topic,
            auth = authHeader(opts),
            missingRequestTypes = missing,
        )
    }

    private fun authHeader(opts: JSONObject): String? {
        if (opts.optBoolean("authMethodToken")) {
            opts.optString("token").takeIf { it.isNotBlank() }?.let { return "Bearer $it" }
        }
        if (opts.optBoolean("authMethodUsernamePassword")) {
            val user = opts.optString("username")
            val pass = opts.optString("password")
            if (user.isNotBlank() && pass.isNotBlank()) {
                return "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            }
        }
        return null
    }

    /** Adds the request notification types to an already-enabled agent. Blocking. */
    fun addRequestTypes(context: Context, config: JSONObject): Server.Response {
        val updated = JSONObject(config.toString())
        updated.put("types", updated.optInt("types", 0) or REQUEST_TYPES)
        return save(context, updated)
    }

    /** Turns on Seerr's ntfy agent, publishing to [server]/[topic]. Blocking. */
    fun enableOnServer(context: Context, existing: JSONObject, server: String, topic: String): Server.Response {
        val config = JSONObject(existing.toString())
        val opts = config.optJSONObject("options") ?: JSONObject()
        opts.put("url", server.trim().trimEnd('/'))
        opts.put("topic", topic)
        if (!opts.has("priority")) opts.put("priority", 4)
        if (opts.optString("locale").isEmpty()) opts.put("locale", "en")
        if (!opts.has("tags")) opts.put("tags", "")
        // A fresh ntfy.sh topic needs no login.
        opts.put("authMethodToken", false)
        opts.put("authMethodUsernamePassword", false)
        config.put("options", opts)
        config.put("enabled", true)
        if (!config.has("embedPoster")) config.put("embedPoster", true)
        val types = config.optInt("types", 0)
        config.put("types", if (types == 0) REQUEST_TYPES else types or REQUEST_TYPES)
        return save(context, config)
    }

    private fun save(context: Context, config: JSONObject): Server.Response {
        val base = Prefs(context).serverUrl ?: return Server.Response(-1, "No server set")
        return Server.request(
            "POST", settingsUrl(base), Server.cookie(base), Server.proxyAuth(context, base), config.toString()
        )
    }

    /** Asks Seerr to send its own test notification through the ntfy agent. Blocking. */
    fun sendServerTest(context: Context): Server.Response {
        val base = Prefs(context).serverUrl ?: return Server.Response(-1, "No server set")
        val cookie = Server.cookie(base)
        val auth = Server.proxyAuth(context, base)
        val current = Server.request("GET", settingsUrl(base), cookie, auth)
        if (!current.ok) return current
        return Server.request("POST", "${settingsUrl(base)}/test", cookie, auth, current.body)
    }

    /** Publishes a test message straight to the topic (works for non-admins too). Blocking. */
    fun sendDirectTest(context: Context, title: String, message: String): Server.Response {
        val p = Prefs(context)
        val server = p.ntfyServer ?: return Server.Response(-1, "Not set up")
        val topic = p.ntfyTopic ?: return Server.Response(-1, "Not set up")
        val body = JSONObject().put("topic", topic).put("title", title).put("message", message)
        val conn = try {
            Server.open(server, cookie = null, basicAuth = null)
        } catch (e: Exception) {
            return Server.Response(-1, e.message)
        }
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            p.ntfyAuth?.let { conn.setRequestProperty("Authorization", it) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            Server.Response(conn.responseCode, null)
        } catch (e: Exception) {
            Server.Response(-1, e.message)
        } finally {
            conn.disconnect()
        }
    }
}
