package com.ryzellx.boardcoach.mod

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Klien UCI untuk Stockfish asm.js (assets/stockfish.js) di WebView tersembunyi
 * via Blob Worker. Tanpa binary native / NDK.
 *
 * FIX GUI FREEZE:
 *  - WebView dibuat di MAIN thread.
 *  - analyze() dipanggil dari WORKER thread; menunggu latch di thread pemanggil
 *    sehingga main thread tetap bebas (UI tidak beku).
 */
class EngineClient(private val context: Context) {

    data class Result(val best: String, val cp: Double?, val pv: String?)

    private var webView: WebView? = null

    @Volatile private var ready = false
    @Volatile private var lastCp: Double? = null
    @Volatile private var lastPv: String? = null
    @Volatile private var best: String? = null
    private var latch: CountDownLatch? = null

    val isReady: Boolean get() = ready

    /** WAJIB dipanggil di main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val sfJs = try {
            context.assets.open("stockfish.js").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) { null } ?: return

        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onUci(line: String) { handleLine(line) }
        }, "BCAndroid")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                send("uci")
                send("setoption name Threads value 1")
                send("setoption name Hash value 16")
            }
        }

        val escaped = sfJs.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val html = "<html><body><script>" +
            "var S=\"$escaped\";" +
            "var b=new Blob([S],{type:'application/javascript'});" +
            "var w=new Worker(URL.createObjectURL(b));" +
            "w.onmessage=function(e){BCAndroid.onUci(e.data)};" +
            "window.send=function(c){w.postMessage(c)};" +
            "</script></body></html>"
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        webView = wv
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
        when {
            line.startsWith("uciok") -> send("isready")
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

    /** Blocking di thread pemanggil — pakai dari WORKER thread. cp = sudut pandang side-to-move. */
    fun analyze(fen: String, movetime: Int, depth: Int, timeoutMs: Long = 8000): Result? {
        if (!ready) return null
        best = null; lastCp = null; lastPv = null
        val l = CountDownLatch(1)
        latch = l
        send("position fen $fen")
        send("go movetime $movetime depth $depth")
        l.await(timeoutMs, TimeUnit.MILLISECONDS)
        latch = null
        val b = best ?: return null
        return Result(b, lastCp, lastPv)
    }

    fun analyzeWithSkill(fen: String, skill: Int, movetime: Int, depth: Int, timeoutMs: Long = 8000): Result? {
        setSkill(skill)
        return analyze(fen, movetime, depth, timeoutMs)
    }

    fun close() {
        val wv = webView
        webView = null
        ready = false
        if (wv != null) wv.post { runCatching { wv.destroy() } }
    }
}
