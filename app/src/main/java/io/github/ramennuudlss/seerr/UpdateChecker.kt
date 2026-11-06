package io.github.ramennuudlss.seerr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Sideloaded APKs don't get Play Store updates, so check this project's GitHub
 * releases once a day and offer the new APK.
 */
object UpdateChecker {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private data class Release(val version: String, val apkUrl: String?, val pageUrl: String)

    fun checkIfDue(activity: Activity) {
        val prefs = Prefs(activity)
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < DAY_MS) return
        check(activity, manual = false)
    }

    fun checkNow(activity: Activity) = check(activity, manual = true)

    private fun check(activity: Activity, manual: Boolean) {
        val prefs = Prefs(activity)
        thread(name = "update-check") {
            val release = runCatching { fetchLatest() }.getOrNull()
            if (release != null) prefs.lastUpdateCheck = System.currentTimeMillis()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    release == null ->
                        if (manual) Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
                    isNewer(release.version, BuildConfig.VERSION_NAME) &&
                        (manual || prefs.skippedVersion != release.version) ->
                        showDialog(activity, release, prefs)
                    manual -> Toast.makeText(activity, R.string.update_none, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "${Server.USER_AGENT_SUFFIX}/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return null
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            return Release(tag, apk, json.optString("html_url"))
        } finally {
            conn.disconnect()
        }
    }

    /** Compares dotted versions like 1.2.10 vs 1.2.9. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun showDialog(activity: Activity, release: Release, prefs: Prefs) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(
                activity.getString(R.string.update_available_message, release.version, BuildConfig.VERSION_NAME)
            )
            .setPositiveButton(R.string.update_download) { _, _ ->
                val url = release.apkUrl ?: release.pageUrl
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            .setNeutralButton(R.string.update_skip) { _, _ -> prefs.skippedVersion = release.version }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
}
