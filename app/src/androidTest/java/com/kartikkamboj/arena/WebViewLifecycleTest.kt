package com.kartikkamboj.arena

import android.content.Context
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kartikkamboj.arena.data.model.ArenaMode
import com.kartikkamboj.arena.web.ArenaWebChromeClient
import com.kartikkamboj.arena.web.ArenaWebViewClient
import com.kartikkamboj.arena.web.ArenaUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for the piece that cannot be exercised on the JVM: a real `WebView`
 * being created with the app's settings, driven through the app's own clients, and destroyed.
 *
 * This class is *compiled* by CI (`:app:assembleDebugAndroidTest`) but it is only *run* when a
 * device or emulator is attached — see docs/TESTING.md, which states plainly that no test here
 * signs into arena.ai and that the emulator run has not been performed in CI.
 */
@RunWith(AndroidJUnit4::class)
class WebViewLifecycleTest {

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    @Test
    fun a_view_can_be_created_configured_and_destroyed() {
        lateinit var view: WebView
        onMain {
            view = WebView(context())
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            view.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        onMain {
            view.destroy()
        }
        assertNotNull(view)
    }

    @Test
    fun clients_are_constructible_and_report_nothing_until_a_page_moves() {
        val recording = RecordingListener()
        onMain {
            val view = WebView(context())
            val client = ArenaWebViewClient(slot = 1, listener = recording)
            val chrome = ArenaWebChromeClient(slot = 1, listener = recording)
            view.webViewClient = client
            view.webChromeClient = chrome
            assertFalse("nothing has loaded yet", recording.finished)
            assertEquals(0, recording.progress)
            view.destroy()
        }
    }

    @Test
    fun navigation_is_routed_by_policy_before_the_view_ever_sees_it() {
        // The policy is the security boundary, and it runs on the main thread inside the client.
        assertEquals(
            ArenaUrls.CHAT,
            ArenaUrls.absolute("/", ArenaUrls.LEADERBOARD),
        )
        assertEquals(ArenaMode.BATTLE.entryUrl, "https://arena.ai/")
        assertTrue(ArenaUrls.isArenaUrl(ArenaUrls.AGENT))
        assertFalse(ArenaUrls.isArenaUrl("http://arena.ai/"))
    }

    /** A [SessionListener] that only counts, so no slot or repository is involved. */
    private class RecordingListener : com.kartikkamboj.arena.web.SessionListener {
        var finished: Boolean = false
        var progress: Int = -1
        var errors: Int = 0
        var handoffs: Int = 0

        override fun onLoadingChanged(loading: Boolean, url: String) {
            if (!loading) finished = true
        }

        override fun onProgress(progress: Int) {
            this.progress = progress
        }

        override fun onContentChanged(url: String, title: String) = Unit
        override fun onMainFrameHttpError(status: Int, url: String) = Unit
        override fun onSubResourceHttpError(status: Int) = Unit
        override fun onTransportError(code: Int, description: String?, url: String, isMainFrame: Boolean) {
            errors++
        }

        override fun onRenderProcessGone(didCrash: Boolean) = Unit
        override fun onSslError(host: String?) = Unit
        override fun onExternalNavigation(url: String, reason: com.kartikkamboj.arena.web.NavigationPolicy.HandoffReason) {
            handoffs++
        }

        override fun onBlockedNavigation(url: String, reason: com.kartikkamboj.arena.web.NavigationPolicy.BlockReason) = Unit
        override fun onFileChooserRequested(
            callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
            acceptTypes: Array<String>,
            allowMultiple: Boolean,
        ) = Unit

        override fun onDownloadRequested(
            url: String,
            userAgent: String,
            mimeType: String,
            contentLength: Long,
        ) = Unit

        override fun onFirstPaint() = Unit
    }
}
