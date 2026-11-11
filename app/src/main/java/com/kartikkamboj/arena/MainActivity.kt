package com.kartikkamboj.arena

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kartikkamboj.arena.core.AppLog
import com.kartikkamboj.arena.di.AppContainer
import com.kartikkamboj.arena.ui.ArenaApp
import com.kartikkamboj.arena.ui.theme.ArenaTheme
import com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason

/**
 * The only Activity, and it stays thin on purpose: install the splash, hand the WebView the two
 * system dialogs it cannot start by itself (a document picker, a browser tab), route Back, and draw
 * the shell. No ViewModel, no navigation graph, no screens of our own — the website is the app.
 *
 * `configChanges` in the manifest keeps rotation from recreating this class at all, and the view
 * itself lives in [AppContainer] so even a real recreation re-attaches the same page instead of
 * loading a second one.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as ArenaApplication).container }

    private val engine get() = container.engine

    /**
     * One launcher for every file input on the page. `ACTION_GET_CONTENT` rather than the document
     * picker contract, because the site's own accept list decides what is offered and a browser is
     * allowed to answer with anything the user can reach — photos, recent files, a cloud provider.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = result.data?.let { readUris(it, result.resultCode) }
        engine.deliverPickResult(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // The splash holds until the site paints its first frame, and never longer than 1.2s: a cold
        // start should look deliberate, and a slow network must not turn into a screen nobody can use.
        val deadline = SystemClock.elapsedRealtime() + 1_200L
        splash.setKeepOnScreenCondition {
            !engine.state.value.firstPaint && SystemClock.elapsedRealtime() < deadline
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        engine.onOpenExternal = { url, reason -> openExternally(url, reason) }
        engine.onPickFiles = { types, multiple -> launchPicker(types, multiple) }

        // Back belongs to the page first: an arena.ai thread is a browser history entry, and leaving
        // it is what a user expects. Only an exhausted history closes the app.
        onBackPressedDispatcher.addCallback(this) {
            if (!engine.goBack()) this@MainActivity.finish()
        }

        handleDeepLink(intent)

        setContent {
            ArenaTheme {
                ArenaApp(container = container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** A tapped arena.ai link opens in the same window, and only ever after the navigation policy. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        engine.load(data)
    }

    private fun launchPicker(acceptTypes: Array<String>, allowMultiple: Boolean) {
        val mime = acceptTypes.firstOrNull { it.contains('/') } ?: "*/*"
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mime
            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.ifEmpty { arrayOf("*/*") })
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching { picker.launch(Intent.createChooser(intent, "Attach to Arena")) }
        if (launched.isFailure) {
            AppLog.w("no document picker on this device")
            // Never leave the page's input waiting: a callback that is not answered blocks uploads
            // for the rest of the session.
            engine.deliverPickResult(null)
            engine.say("This device has no document picker, so the file could not be attached.")
        }
    }

    private fun readUris(data: Intent, resultCode: Int): Array<Uri>? {
        if (resultCode != RESULT_OK) return null
        val clip = data.clipData
        if (clip != null) {
            val all = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(all::add)
            if (all.isNotEmpty()) return all.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    /**
     * Everything that is not arena.ai leaves to a real browser.
     *
     * http(s) goes to a Chrome Custom Tab — the documented in-app browser, where the user's own
     * profile and the provider's rules apply. Anything else (mailto:, tel:, an app's own scheme) goes
     * to the system handler. If no browser exists at all, the failure is reported rather than
     * swallowed. No cookie crosses between that tab and this app's WebView, and the app never claims
     * otherwise: see docs/AUTHENTICATION.md.
     */
    private fun openExternally(url: String, reason: HandoffReason) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val plainView = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (reason == HandoffReason.APP_HANDLER || !url.startsWith("http")) {
            if (runCatching { startActivity(plainView) }.isFailure) {
                engine.say("No app on this device handles that kind of link.")
            }
            return
        }
        val tabOpened = runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        }.isSuccess
        if (!tabOpened) {
            if (runCatching { startActivity(plainView) }.isFailure) {
                engine.say("No browser is available on this device, so the link could not be opened.")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.onHostStart()
    }

    override fun onStop() {
        engine.onHostStop()
        super.onStop()
    }

    override fun onDestroy() {
        // The hooks hold Activity references; drop them whether or not the process is finishing.
        engine.onOpenExternal = null
        engine.onPickFiles = null
        if (isFinishing) engine.shutdown()
        super.onDestroy()
    }
}
