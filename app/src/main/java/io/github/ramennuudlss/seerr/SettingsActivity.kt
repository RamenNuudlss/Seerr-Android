package io.github.ramennuudlss.seerr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ramennuudlss.seerr.databinding.ActivitySettingsBinding
import io.github.ramennuudlss.seerr.databinding.DialogNtfyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's own settings (Seerr's settings live inside the web UI as usual). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var updatingInstantUi = false

    private val instantNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            prefs.notificationsEnabled = granted
            binding.notificationsSwitch.isChecked = granted
            RequestNotifier.schedule(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.serverUrl.text = prefs.serverUrl ?: "—"
        binding.changeServer.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        binding.notificationsSwitch.isChecked = prefs.notificationsEnabled && hasNotificationPermission()
        binding.notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prefs.notificationsEnabled = checked
                RequestNotifier.schedule(this)
            }
        }

        binding.plexBrowserSwitch.isChecked = prefs.plexInBrowser
        binding.plexBrowserSwitch.setOnCheckedChangeListener { _, checked -> prefs.plexInBrowser = checked }

        binding.pullRefreshSwitch.isChecked = prefs.pullToRefresh
        binding.pullRefreshSwitch.setOnCheckedChangeListener { _, checked -> prefs.pullToRefresh = checked }

        setupInstantAlerts()

        binding.checkUpdates.setOnClickListener { UpdateChecker.checkNow(this) }
        binding.clearData.setOnClickListener { confirmClearData() }

        binding.version.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.github.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.serverUrl.text = prefs.serverUrl ?: "—"
        refreshInstantUi()
    }

    // ------------------------------------------------------------ instant alerts

    private fun setupInstantAlerts() {
        binding.instantSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingInstantUi) return@setOnCheckedChangeListener
            if (checked) {
                startInstantSetup()
            } else {
                InstantAlerts.disable(this)
                refreshInstantUi()
            }
        }
        binding.instantBattery.setOnClickListener { InstantAlerts.requestBatteryExemption(this) }
        binding.instantTest.setOnClickListener { sendInstantTest() }
        binding.instantEdit.setOnClickListener { showManualTopicDialog() }
        refreshInstantUi()
    }

    private fun refreshInstantUi() {
        val on = InstantAlerts.isConfigured(this)
        updatingInstantUi = true
        binding.instantSwitch.isChecked = on
        updatingInstantUi = false
        binding.instantSummary.setText(if (on) R.string.instant_summary_on else R.string.instant_summary_off)
        binding.instantDetails.isVisible = on
        binding.instantTopic.text = "${prefs.ntfyServer.orEmpty()}/${prefs.ntfyTopic.orEmpty()}"
        binding.instantBattery.isVisible = on && !InstantAlerts.isBatteryExempt(this)
    }

    private fun setInstantBusy(busy: Boolean) {
        binding.instantProgress.isVisible = busy
        binding.instantSwitch.isEnabled = !busy
    }

    private fun startInstantSetup() {
        setInstantBusy(true)
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { InstantAlerts.readServerState(this@SettingsActivity) }
            setInstantBusy(false)
            when (state) {
                is InstantAlerts.ServerState.NotAdmin ->
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle(R.string.instant_not_admin_title)
                        .setMessage(R.string.instant_not_admin_message)
                        .setPositiveButton(R.string.instant_enter_topic) { _, _ -> showManualTopicDialog() }
                        .setNegativeButton(R.string.cancel) { _, _ -> refreshInstantUi() }
                        .setOnCancelListener { refreshInstantUi() }
                        .show()
                is InstantAlerts.ServerState.Failed -> {
                    toast(getString(R.string.instant_failed, state.reason))
                    refreshInstantUi()
                }
                is InstantAlerts.ServerState.Configured -> confirmExistingTopic(state)
                is InstantAlerts.ServerState.NotConfigured -> confirmNewTopic(state)
            }
        }
    }

    private fun confirmExistingTopic(state: InstantAlerts.ServerState.Configured) {
        var message = getString(R.string.instant_existing_message, state.topic, state.server)
        if (state.missingRequestTypes) message += getString(R.string.instant_existing_add_types)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instant_existing_title)
            .setMessage(message)
            .setPositiveButton(R.string.instant_use_it) { _, _ ->
                lifecycleScope.launch {
                    if (state.missingRequestTypes) {
                        setInstantBusy(true)
                        val res = withContext(Dispatchers.IO) {
                            InstantAlerts.addRequestTypes(this@SettingsActivity, state.config)
                        }
                        setInstantBusy(false)
                        if (!res.ok) {
                            toast(getString(R.string.instant_save_failed, describe(res)))
                            refreshInstantUi()
                            return@launch
                        }
                    }
                    finishInstantSetup(state.server, state.topic, state.auth, serverTest = true)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> refreshInstantUi() }
            .setOnCancelListener { refreshInstantUi() }
            .show()
    }

    private fun confirmNewTopic(state: InstantAlerts.ServerState.NotConfigured) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instant_setup_title)
            .setMessage(R.string.instant_setup_message)
            .setPositiveButton(R.string.instant_use_ntfy_sh) { _, _ ->
                enableOnServer(state.config, InstantAlerts.DEFAULT_SERVER)
            }
            .setNeutralButton(R.string.instant_use_own) { _, _ -> askOwnServer(state.config) }
            .setNegativeButton(R.string.cancel) { _, _ -> refreshInstantUi() }
            .setOnCancelListener { refreshInstantUi() }
            .show()
    }

    private fun askOwnServer(config: org.json.JSONObject) {
        val view = DialogNtfyBinding.inflate(layoutInflater)
        view.topicLayout.isVisible = false
        view.tokenLayout.isVisible = false
        view.server.setText("https://")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instant_server_hint)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val server = normalizeServer(view.server.text?.toString().orEmpty())
                if (server == null) refreshInstantUi() else enableOnServer(config, server)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> refreshInstantUi() }
            .setOnCancelListener { refreshInstantUi() }
            .show()
    }

    private fun enableOnServer(config: org.json.JSONObject, server: String) {
        val topic = InstantAlerts.randomTopic()
        setInstantBusy(true)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                InstantAlerts.enableOnServer(this@SettingsActivity, config, server, topic)
            }
            setInstantBusy(false)
            if (!res.ok) {
                toast(getString(R.string.instant_save_failed, describe(res)))
                refreshInstantUi()
                return@launch
            }
            finishInstantSetup(server, topic, null, serverTest = true)
        }
    }

    /** For non-admins, or to point the app at a topic by hand. */
    private fun showManualTopicDialog() {
        val view = DialogNtfyBinding.inflate(layoutInflater)
        view.server.setText(prefs.ntfyServer ?: InstantAlerts.DEFAULT_SERVER)
        view.topic.setText(prefs.ntfyTopic.orEmpty())
        prefs.ntfyAuth?.takeIf { it.startsWith("Bearer ") }?.let { view.token.setText(it.removePrefix("Bearer ")) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instant_manual_title)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val server = normalizeServer(view.server.text?.toString().orEmpty())
                val topic = view.topic.text?.toString().orEmpty().trim()
                if (server == null || topic.isEmpty()) {
                    refreshInstantUi()
                    return@setPositiveButton
                }
                val token = view.token.text?.toString().orEmpty().trim()
                val auth = when {
                    token.isNotEmpty() -> "Bearer $token"
                    server == prefs.ntfyServer && prefs.ntfyAuth?.startsWith("Basic ") == true -> prefs.ntfyAuth
                    else -> null
                }
                finishInstantSetup(server, topic, auth, serverTest = false)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> refreshInstantUi() }
            .setOnCancelListener { refreshInstantUi() }
            .show()
    }

    private fun finishInstantSetup(server: String, topic: String, auth: String?, serverTest: Boolean) {
        InstantAlerts.activate(this, server, topic, auth)
        refreshInstantUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            instantNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!InstantAlerts.isBatteryExempt(this)) {
            showBatteryDialog()
        }
        if (serverTest) {
            // Let the listener connect, then have Seerr send its own test through ntfy.
            lifecycleScope.launch {
                delay(3_000)
                withContext(Dispatchers.IO) { InstantAlerts.sendServerTest(this@SettingsActivity) }
            }
        }
    }

    private fun showBatteryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.instant_battery_title)
            .setMessage(R.string.instant_battery_message)
            .setPositiveButton(R.string.instant_battery_allow) { _, _ -> InstantAlerts.requestBatteryExemption(this) }
            .setNegativeButton(R.string.instant_battery_skip, null)
            .show()
    }

    private fun sendInstantTest() {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                InstantAlerts.sendDirectTest(this@SettingsActivity, getString(R.string.app_name), getString(R.string.instant_test_body))
            }
            toast(if (res.ok) getString(R.string.instant_test_sent) else getString(R.string.instant_test_failed, describe(res)))
        }
    }

    private fun normalizeServer(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s == "https:" || s == "http:") return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s
    }

    private fun describe(res: Server.Response): String =
        if (res.code == -1) res.body ?: "network error" else "HTTP ${res.code}"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_data)
            .setMessage(R.string.settings_clear_data_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_data) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                WebView(this).apply { clearCache(true); destroy() }
                prefs.clearHttpAuth()
                prefs.clearRequestState()
                Toast.makeText(this, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .show()
    }
}
