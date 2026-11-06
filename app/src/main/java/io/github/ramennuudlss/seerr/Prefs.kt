package io.github.ramennuudlss.seerr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Small typed wrapper around the app's SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("seerr", Context.MODE_PRIVATE)

    /** Base URL of the Seerr server, normalised, without a trailing slash. */
    var serverUrl: String?
        get() = sp.getString(KEY_SERVER, null)
        set(value) = sp.edit { putString(KEY_SERVER, value) }

    var notificationsEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = sp.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    var plexInBrowser: Boolean
        get() = sp.getBoolean(KEY_PLEX_BROWSER, true)
        set(value) = sp.edit { putBoolean(KEY_PLEX_BROWSER, value) }

    var pullToRefresh: Boolean
        get() = sp.getBoolean(KEY_PULL_REFRESH, true)
        set(value) = sp.edit { putBoolean(KEY_PULL_REFRESH, value) }

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) = sp.edit { putBoolean(KEY_ASKED_NOTIF, value) }

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }

    var skippedVersion: String?
        get() = sp.getString(KEY_SKIPPED_VERSION, null)
        set(value) = sp.edit { putString(KEY_SKIPPED_VERSION, value) }

    /** JSON map of requestId -> last seen state, used by the notification worker. */
    var requestState: String?
        get() = sp.getString(KEY_REQUEST_STATE, null)
        set(value) = sp.edit { putString(KEY_REQUEST_STATE, value) }

    // Instant alerts: the ntfy server/topic Seerr publishes to, which the app listens on.
    var ntfyEnabled: Boolean
        get() = sp.getBoolean(KEY_NTFY_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_NTFY_ENABLED, value) }

    var ntfyServer: String?
        get() = sp.getString(KEY_NTFY_SERVER, null)
        set(value) = sp.edit { putString(KEY_NTFY_SERVER, value) }

    var ntfyTopic: String?
        get() = sp.getString(KEY_NTFY_TOPIC, null)
        set(value) = sp.edit { putString(KEY_NTFY_TOPIC, value) }

    /** Full Authorization header value for the ntfy server ("Bearer …" or "Basic …"), if needed. */
    var ntfyAuth: String?
        get() = sp.getString(KEY_NTFY_AUTH, null)
        set(value) = sp.edit { putString(KEY_NTFY_AUTH, value) }

    /** Last ntfy message id received, so missed messages are fetched after a reconnect. */
    var ntfyLastId: String?
        get() = sp.getString(KEY_NTFY_LAST_ID, null)
        set(value) = sp.edit { putString(KEY_NTFY_LAST_ID, value) }

    /** Credentials for a reverse proxy in front of Seerr that uses HTTP basic auth. */
    fun httpAuth(host: String): Pair<String, String>? {
        val user = sp.getString("auth_user_$host", null) ?: return null
        val pass = sp.getString("auth_pass_$host", null) ?: return null
        return user to pass
    }

    fun setHttpAuth(host: String, user: String, pass: String) = sp.edit {
        putString("auth_user_$host", user)
        putString("auth_pass_$host", pass)
    }

    fun clearHttpAuth() = sp.edit {
        sp.all.keys.filter { it.startsWith("auth_") }.forEach { remove(it) }
    }

    fun clearRequestState() = sp.edit { remove(KEY_REQUEST_STATE) }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_PLEX_BROWSER = "plex_in_browser"
        const val KEY_PULL_REFRESH = "pull_to_refresh"
        const val KEY_ASKED_NOTIF = "asked_notification_permission"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_SKIPPED_VERSION = "skipped_version"
        const val KEY_REQUEST_STATE = "request_state"
        const val KEY_NTFY_ENABLED = "ntfy_enabled"
        const val KEY_NTFY_SERVER = "ntfy_server"
        const val KEY_NTFY_TOPIC = "ntfy_topic"
        const val KEY_NTFY_AUTH = "ntfy_auth"
        const val KEY_NTFY_LAST_ID = "ntfy_last_id"
    }
}
