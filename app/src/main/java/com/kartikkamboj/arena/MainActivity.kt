package com.kartikkamboj.arena

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.ui.ArenaApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only Activity. Everything below it is Compose; the web session is owned by the container so
 * this class stays thin: edge-to-edge, the two system hand-offs the WebView needs (browser tabs and
 * the document picker), window flags, and lifecycle plumbing.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as ArenaApplication).container }

    /** Deep-linked arena.ai URL, consumed by the UI layer once a slot is bound. */
    private val pendingLink = mutableStateOf<String?>(null)

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        container.session.deliverPickResult(uris?.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container.session.onOpenExternal = { url -> openExternally(url) }
        container.session.onPickFiles = { types, _ ->
            runCatching { pickFiles.launch(types.ifEmpty { arrayOf("*/*") }) }
                .onFailure {
                    AppLog.w("document picker unavailable", it)
                    container.session.deliverPickResult(null)
                }
        }

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.session.onHostResume()
                    Lifecycle.Event.ON_STOP -> {
                        // Capture the slot's jar while the process is definitely still alive.
                        container.session.onHostPause()
                    }

                    Lifecycle.Event.ON_DESTROY -> if (isFinishing) container.session.shutdown()
                    else -> Unit
                }
            },
        )

        lifecycleScope.launch {
            container.settings.settings
                .map { it.screenshotProtection }
                .distinctUntilChanged()
                .collect { applyScreenshotProtection(it) }
        }

        handleLink(intent)

        setContent {
            ArenaApp(
                container = container,
                pendingLink = pendingLink,
                onLinkConsumed = { pendingLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!intent.dataString.isNullOrBlank() && data.scheme == "https") {
            pendingLink.value = data.toString()
        }
    }

    /**
     * Sign-in hand-off. Chrome Custom Tabs keep the user in a real browser — where their identity
     * provider will actually work — instead of an embedded view that the provider refuses.
     */
    private fun openExternally(url: String) {
        val builder = CustomTabsIntent.Builder().setShowTitle(true)
        val opened = runCatching {
            builder.build().launchUrl(this, Uri.parse(url))
        }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { container.session.reportProblem("No browser is available to open that link.") }
        }
    }

    /** Re-applies the screenshot guard whenever the setting changes. */
    fun applyScreenshotProtection(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onDestroy() {
        container.session.onOpenExternal = null
        container.session.onPickFiles = null
        super.onDestroy()
    }

}
