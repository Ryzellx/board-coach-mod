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
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Klien UCI untuk Stockfish asm.js (assets/stockfish.js).
 *
 * v1.5.2 — kenapa engine nyangkut di "engine memuat... (stockfish init)":
 *  1. WebView tidak ditempelkan ke window → renderer dianggap background →
 *     Web Worker ditunda/di-throttle → Stockfish tidak pernah selesai compile.
 *     FIX: WebView ditempel ke WindowManager sebagai jendela 1x1 transparan.
 *  2. "uci" dikirim sekali; kalau hilang tidak ada percobaan ulang.
 *     FIX: watchdog kirim ulang "uci" tiap 4 detik sampai ada balasan.
 */
class EngineClient(private val context: Context) {

    data class Result(val best: String, val cp: Double?, val pv: String?)

    private var webView: WebView? = null
    private var attached = false

    @Volatile private var pageLoaded = false
    @Volatile private var alive = false        // ada balasan "id name ..."
    @Volatile private var engineReady = false  // sudah "readyok"
    @Volatile var lastError: String? = null

    @Volatile private var lastCp: Double? = null
    @Volatile private var lastPv: String? = null
    @Volatile private var best: String? = null
    private var latch: CountDownLatch? = null

    private val main = Handler(Looper.getMainLooper())
    private var watchdogTries = 0

    val isReady: Boolean get() = engineReady
    val isLoaded: Boolean get() = pageLoaded
    val isAlive: Boolean get() = alive

    private val watchdog = object : Runnable {
        override fun run() {
            if (engineReady || webView == null) return
            watchdogTries++
            send("uci")
            if (watchdogTries > 8) {
                lastError = "engine diam (${watchdogTries}x uci, attach=$attached)"
                return
            }
            main.postDelayed(this, 4000)
        }
    }

    /** WAJIB dipanggil di main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val sfJs = try {
            context.assets.open("stockfish.js").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            lastError = "assets: ${e.javaClass.simpleName}"
            return
        }

        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onUci(line: String) { handleLine(line) }
        }, "BCAndroid")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                send("uci")
                send("setoption name Threads value 1")
                send("setoption name Hash value 16")
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, 4000)
            }
        }

        val escaped = sfJs.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val html = "<html><body><script>" +
            "window.onerror=function(m,s,l){try{BCAndroid.onUci('ERR: '+m+' @'+l)}catch(x){}};" +
            "try{" +
            "var S=\"$escaped\";" +
            "var b=new Blob([S],{type:'application/javascript'});" +
            "var w=new Worker(URL.createObjectURL(b));" +
            "w.onmessage=function(e){try{BCAndroid.onUci(String(e.data))}catch(x){}};" +
            "w.onerror=function(e){try{BCAndroid.onUci('ERR: worker '+(e.message||e))}catch(x){}};" +
            "window.send=function(c){try{w.postMessage(c)}catch(x){BCAndroid.onUci('ERR: post '+x)}};" +
            "}catch(e){BCAndroid.onUci('ERR: init '+e);}" +
            "</script></body></html>"
        // base URL https (bukan file://) → Blob Worker tidak diblok
        wv.loadDataWithBaseURL("https://boardcoach.local/", html, "text/html", "utf-8", null)
        webView = wv
        attachWindow(wv)
    }

    /**
     * Tempelkan WebView ke WindowManager 1x1 transparan. Tanpa ini, renderer
     * dianggap tidak terlihat → Web Worker tidak jalan (Stockfish nyangkut).
     */
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
            lastError = "attach: ${e.javaClass.simpleName}"
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

    private fun handleLine(line: String) {
        if (line.startsWith("ERR:")) { lastError = line.take(140); return }
        when {
            line.startsWith("id ") -> alive = true
            line.startsWith("uciok") -> { alive = true; send("isready") }
            line.startsWith("readyok") -> { engineReady = true; main.removeCallbacks(watchdog) }
            line.startsWith("info") -> parseInfo(line)
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
        val b = best ?: run { lastError = "timeout movetime=$movetime"; return null }
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
