package io.github.ramennuudlss.seerr

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Helpers for talking to the Seerr server outside the WebView. */
object Server {

    const val USER_AGENT_SUFFIX = "SeerrAndroid"

    /**
     * Turns whatever the user typed into candidate base URLs to try, most likely first.
     * "requests.example.com" -> https then http; "192.168.1.10:5055" -> http then https.
     */
    fun candidates(input: String): List<String> {
        var s = input.trim()
        if (s.isEmpty()) return emptyList()
        // Drop anything after the base path that people paste from the browser.
        s = s.substringBefore('#').substringBefore('?')
        listOf("/login", "/setup", "/requests", "/discover").forEach { suffix ->
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix)
        }
        s = s.trimEnd('/')
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return listOf(s)

        val host = s.substringBefore('/').substringBefore(':')
        val looksLocal = host == "localhost" ||
            host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") ||
            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) ||
            !host.contains('.')
        return if (looksLocal) listOf("http://$s", "https://$s") else listOf("https://$s", "http://$s")
    }

    data class Status(val version: String)

    /** Hits /api/v1/status. Returns the server version, or throws with a readable reason. */
    @Throws(IOException::class, NotSeerrException::class)
    fun checkStatus(baseUrl: String, basicAuth: Pair<String, String>? = null): Status {
        val conn = open("$baseUrl/api/v1/status", cookie = null, basicAuth = basicAuth)
        try {
            val code = conn.responseCode
            if (code == 401) throw AuthRequiredException()
            if (code !in 200..299) throw NotSeerrException()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = try { JSONObject(body) } catch (_: Exception) { throw NotSeerrException() }
            val version = json.optString("version", "")
            if (version.isEmpty()) throw NotSeerrException()
            return Status(version)
        } finally {
            conn.disconnect()
        }
    }

    /** GET a JSON object from the API using the WebView's session cookie. */
    fun getJson(url: String, cookie: String?, basicAuth: Pair<String, String>?): JSONObject? {
        val conn = open(url, cookie, basicAuth)
        return try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun open(url: String, cookie: String?, basicAuth: Pair<String, String>?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "$USER_AGENT_SUFFIX/${BuildConfig.VERSION_NAME}")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (basicAuth != null) {
            val token = Base64.encodeToString(
                "${basicAuth.first}:${basicAuth.second}".toByteArray(), Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    data class Response(val code: Int, val body: String?) {
        val ok get() = code in 200..299
    }

    /**
     * Any API call with the WebView's session cookie. Sends Seerr's CSRF token when the
     * server has CSRF protection on. Never throws; network errors return code -1.
     */
    fun request(
        method: String,
        url: String,
        cookie: String?,
        basicAuth: Pair<String, String>?,
        json: String? = null,
    ): Response {
        val conn = try {
            open(url, cookie, basicAuth)
        } catch (e: IOException) {
            return Response(-1, e.message)
        }
        return try {
            conn.requestMethod = method
            xsrfToken(cookie)?.let { conn.setRequestProperty("X-XSRF-TOKEN", it) }
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Response(code, stream?.bufferedReader()?.use { it.readText() })
        } catch (e: IOException) {
            Response(-1, e.message)
        } finally {
            conn.disconnect()
        }
    }

    private fun xsrfToken(cookie: String?): String? =
        cookie?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    /** The signed-in Seerr session cookie from the WebView, if any. */
    fun cookie(base: String): String? = CookieManager.getInstance().getCookie(base)

    /** Saved reverse-proxy credentials for the server, if any. */
    fun proxyAuth(context: Context, base: String): Pair<String, String>? =
        Uri.parse(base).host?.let { Prefs(context).httpAuth(it) }

    const val PERM_ADMIN = 2L
    const val PERM_MANAGE_REQUESTS = 16L

    fun canManageRequests(permissions: Long): Boolean =
        (permissions and PERM_ADMIN) != 0L || (permissions and PERM_MANAGE_REQUESTS) != 0L

    /** True if [url] points at the same scheme+host+port as the configured server. */
    fun isSameOrigin(url: Uri, base: String?): Boolean {
        if (base == null) return false
        val b = Uri.parse(base)
        return url.scheme.equals(b.scheme, true) &&
            url.host.equals(b.host, true) &&
            effectivePort(url) == effectivePort(b)
    }

    private fun effectivePort(u: Uri): Int = when {
        u.port != -1 -> u.port
        u.scheme.equals("https", true) -> 443
        else -> 80
    }

    class NotSeerrException : Exception()
    class AuthRequiredException : IOException("Server requires a username and password")
}
