package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    /**
     * WebView.getScrollY() (px) at the last onScrollChange. This is only the *invalidation signal*:
     * reading it inside a layout/draw block makes that block run again when the page scrolls.
     * The value that is actually used for positioning is read live from the WebView (see [cssScroll]).
     */
    var nativeScrollPx by mutableIntStateOf(0)
        private set

    fun onNativeScroll(y: Int) {
        nativeScrollPx = y
    }

    /** Kept for backward compatibility with older bridge scripts. Per-scroll-event JS→Java calls are synchronous
     *  IPC that stall the renderer's main thread (= scroll jank), so glass-bridge.js no longer calls this. */
    @JavascriptInterface
    fun scroll(y: Float) { /* no-op */ }

    /**
     * Current document scroll in CSS px (== dp).
     *
     * Subscribes to [nativeScrollPx] (so callers re-run on scroll) but returns WebView.getScrollY() *at call time*.
     * WebView syncs its scroll offset inside its own onDraw; a draw block that runs after the WebView was drawn
     * (glass is a later sibling) therefore sees the offset of the frame that is being produced right now,
     * instead of the previous frame's value that a state snapshot would give.
     */
    fun cssScroll(density: Float): Float {
        @Suppress("UNUSED_VARIABLE") val subscribe = nativeScrollPx
        return webView.scrollY / density
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }
}
