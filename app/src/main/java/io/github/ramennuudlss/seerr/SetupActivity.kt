package io.github.ramennuudlss.seerr

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ramennuudlss.seerr.databinding.ActivitySetupBinding
import io.github.ramennuudlss.seerr.databinding.DialogHttpAuthBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run screen (and "Change server"): asks for the Seerr address and checks it. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        prefs.serverUrl?.let { binding.urlInput.setText(it) }
        binding.connectButton.setOnClickListener { connect() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
    }

    private fun connect(basicAuth: Pair<String, String>? = null) {
        val input = binding.urlInput.text?.toString().orEmpty()
        val candidates = Server.candidates(input)
        if (candidates.isEmpty()) {
            showError(getString(R.string.setup_error_empty))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            var lastError: Exception? = null
            for (url in candidates) {
                val auth = basicAuth ?: Uri.parse(url).host?.let { prefs.httpAuth(it) }
                val result = withContext(Dispatchers.IO) {
                    runCatching { Server.checkStatus(url, auth) }
                }
                result.onSuccess { status ->
                    onConnected(url, status.version)
                    return@launch
                }
                val e = result.exceptionOrNull() as? Exception
                if (e is Server.AuthRequiredException) {
                    setBusy(false)
                    askForBasicAuth(url)
                    return@launch
                }
                lastError = e
            }
            setBusy(false)
            showError(
                when (lastError) {
                    is Server.NotSeerrException -> getString(R.string.setup_error_not_seerr)
                    else -> getString(R.string.setup_error_unreachable, lastError?.message.orEmpty())
                }
            )
        }
    }

    /** For servers behind a reverse proxy that asks for a username and password. */
    private fun askForBasicAuth(url: String) {
        val host = Uri.parse(url).host ?: return
        val view = DialogHttpAuthBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.http_auth_title, host))
            .setView(view.root)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                val creds = view.username.text.toString() to view.password.text.toString()
                if (view.remember.isChecked) prefs.setHttpAuth(host, creds.first, creds.second)
                binding.urlInput.setText(url)
                connect(creds)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onConnected(url: String, version: String) {
        val changed = prefs.serverUrl != url
        prefs.serverUrl = url
        if (changed) {
            prefs.clearRequestState()
            InstantAlerts.disable(this)
        }
        RequestNotifier.schedule(this)
        Toast.makeText(this, getString(R.string.setup_connected, version), Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        binding.connectButton.isEnabled = !busy
        binding.urlInput.isEnabled = !busy
        if (busy) binding.errorText.isVisible = false
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.isVisible = true
    }
}
