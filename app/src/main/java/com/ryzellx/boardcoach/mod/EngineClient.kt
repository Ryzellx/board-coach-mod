package com.ryzellx.boardcoach.mod

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Klien UCI untuk Stockfish (assets/stockfish.js).
 *
 * v1.6.0 — kenapa worker tetap diam walau attach=true:
 *  Halaman dirakit sebagai satu string HTML raksasa (JS 1.5 MB disisipkan ke
 *  string) lalu loadDataWithBaseURL. Cara itu rapuh: skrip bisa gagal parse dan
 *  kita tidak dapat sinyal apa pun.
 *  FIX: WebViewAssetLoader — engine.html + stockfish.js disajikan dari origin
 *  https://appassets.androidplatform.net/ (sah), jadi:
 *    - Worker('stockfish.js') boleh (same-origin https)
 *    - tidak ada string raksasa yang perlu di-escape
 *  Plus penghitung pesan supaya kelihatan "ada balasan atau nol".
 */
class EngineClient(private val context: Context) {

    data class Result(val best: String, val cp: Double?, val pv: String?)

    private var webView: WebView? = null
    private var attached = false

    @Volatile private var pageLoaded = false
    @Volatile private var workerMade = false
    @Volatile private var alive = false
    @Volatile private var engineReady = false
    @Volatile var msgCount = 0
    @Volatile private var sample = ""
    @Volatile private var firstError: String? = null

    @Volatile private var lastCp: Double? = null
    @Volatile private var lastPv: String? = null
    @Volatile private var best: String? = null
    private var latch: CountDownLatch? = null

    private val main = Handler(Looper.getMainLooper())
    private var watchdogTries = 0

    val isReady: Boolean get() = engineReady
    val isLoaded: Boolean get() = pageLoaded
    val isAlive: Boolean get() = alive

    /** Pesan error pertama (yang paling berguna) untuk panel. */
    val lastError: String? get() = firstError

    private val watchdog = object : Runnable {
        override fun run() {
            if (engineReady || webView == null) return
            watchdogTries++
            send("uci")
            if (watchdogTries > 7) {
                if (firstError == null) {
                    firstError = "diam (uci=${watchdogTries}x attach=$attached html=$pageLoaded worker=$workerMade msg=$msgCount samp=$sample)"
                }
                return
            }
            main.postDelayed(this, 4000)
        }
    }

    /** WAJIB dipanggil di main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onUci(line: String) { handleChunk(line) }
        }, "BCAndroid")

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                send("uci")
                send("setoption name Threads value 1")
                send("setoption name Hash value 16")
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, 4000)
            }
        }

        wv.loadUrl("https://appassets.androidplatform.net/assets/engine.html")
        webView = wv
        attachWindow(wv)
    }

    /** Tempelkan WebView ke WindowManager 1x1 supaya renderer tidak di-throttle. */
    private fun attachWindow(wv: WebView) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 0
                alpha = 0.01f
            }
            wm.addView(wv, lp)
            attached = true
        } catch (e: Throwable) {
            attached = false
            if (firstError == null) firstError = "attach: ${e.javaClass.simpleName}"
        }
    }

    private fun send(cmd: String) {
        val wv = webView ?: return
        val q = jsonQuote(cmd)
        wv.post { runCatching { wv.evaluateJavascript("send($q);", null) } }
    }

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }

    /**
     * Stockfish bisa mengirim BEBERAPA baris dalam satu postMessage (chunk,
     * kadang dengan '\n' di depan). Dulu kita cek prefix langsung ke chunk →
     * "uciok" tidak pernah cocok walau mesinnya jalan (worker=true msg=42).
     * Sekarang pecah per baris dulu, baru dicocokkan.
     */
    private fun handleChunk(data: String) {
        for (raw in data.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            msgCount++
            if (sample.length < 70) sample = (sample + "|" + line.take(32))
            handleLine(line)
        }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("ERR:") -> { if (firstError == null) firstError = line.take(140) }
            line.startsWith("LOG: worker") -> workerMade = true
            line.startsWith("id ") -> alive = true
            line.contains("uciok") -> { alive = true; send("isready") }
            line.contains("readyok") -> { engineReady = true; main.removeCallbacks(watchdog) }
            line.startsWith("info") || line.contains(" score ") -> parseInfo(line)
            line.startsWith("bestmove") -> {
                best = line.split(" ").getOrNull(1)
                latch?.countDown()
            }
        }
    }

    private fun parseInfo(line: String) {
        val t = line.split(" ")
        val si = t.indexOf("score")
        if (si >= 0 && si + 2 < t.size) {
            val v = t[si + 2].toDoubleOrNull()
            if (v != null) {
                lastCp = when (t[si + 1]) {
                    "cp" -> v
                    "mate" -> if (v >= 0) 100000.0 - v * 1000 else -100000.0 - v * 1000
                    else -> lastCp
                }
            }
        }
        val pi = t.indexOf("pv")
        if (pi >= 0 && pi + 1 < t.size) lastPv = t[pi + 1]
    }

    fun setSkill(skill: Int) { send("setoption name Skill Level value $skill") }

    /** Blocking di thread pemanggil — pakai dari WORKER thread. */
    fun analyze(fen: String, movetime: Int, depth: Int, timeoutMs: Long = 8000): Result? {
        if (!engineReady) return null
        best = null; lastCp = null; lastPv = null
        val l = CountDownLatch(1)
        latch = l
        send("position fen $fen")
        send("go movetime $movetime depth $depth")
        l.await(timeoutMs, TimeUnit.MILLISECONDS)
        latch = null
        val b = best ?: run {
            if (firstError == null) firstError = "timeout movetime=$movetime"
            return null
        }
        return Result(b, lastCp, lastPv)
    }

    fun analyzeWithSkill(fen: String, skill: Int, movetime: Int, depth: Int, timeoutMs: Long = 8000): Result? {
        setSkill(skill)
        return analyze(fen, movetime, depth, timeoutMs)
    }

    fun close() {
        main.removeCallbacks(watchdog)
        val wv = webView
        webView = null
        pageLoaded = false
        alive = false
        engineReady = false
        if (wv != null) wv.post {
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(wv)
            }
            runCatching { wv.destroy() }
        }
        attached = false
    }
}
