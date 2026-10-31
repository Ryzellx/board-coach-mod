package com.ryzellx.boardcoach.mod

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Klien UCI untuk Stockfish asm.js (assets/stockfish.js) di WebView tersembunyi.
 *
 * v1.5.1 — kenapa engine dulu DIAM:
 *  - Halaman dimuat dengan base URL file://android_asset → origin dokumen jadi
 *    "null"/file → `new Worker(blobURL)` DIBLOK browser (SecurityError),
 *    jadi Stockfish tidak pernah hidup, tidak pernah balas bestmove.
 *  - Sekarang base URL https://boardcoach.local/ → origin https yang sah →
 *    Blob Worker diizinkan.
 *  - Semua error JS dikirim balik lewat BCAndroid.onUci("ERR: ...") supaya
 *    kelihatan di panel, bukan diam-diam.
 */
class EngineClient(private val context: Context) {

    data class Result(val best: String, val cp: Double?, val pv: String?)

    private var webView: WebView? = null

    @Volatile private var pageLoaded = false
    @Volatile private var uciOk = false
    @Volatile private var engineReady = false
    @Volatile var lastError: String? = null

    @Volatile private var lastCp: Double? = null
    @Volatile private var lastPv: String? = null
    @Volatile private var best: String? = null
    private var latch: CountDownLatch? = null

    /** true kalau engine sudah menjawab readyok. */
    val isReady: Boolean get() = engineReady

    /** true kalau halaman sudah dimuat (belum tentu engine siap). */
    val isLoaded: Boolean get() = pageLoaded

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
            }
        }

        val escaped = sfJs.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val html = "<html><body><script>" +
            "window.onerror=function(m,s,l,c,e){try{BCAndroid.onUci('ERR: '+m+' @'+l)}catch(x){}};" +
            "try{" +
            "var S=\"$escaped\";" +
            "var b=new Blob([S],{type:'application/javascript'});" +
            "var w=new Worker(URL.createObjectURL(b));" +
            "w.onmessage=function(e){try{BCAndroid.onUci(String(e.data))}catch(x){}};" +
            "w.onerror=function(e){try{BCAndroid.onUci('ERR: worker '+(e.message||e))}catch(x){}};" +
            "window.send=function(c){try{w.postMessage(c)}catch(x){BCAndroid.onUci('ERR: post '+x)}};" +
            "}catch(e){BCAndroid.onUci('ERR: init '+e);}" +
            "</script></body></html>"
        // PENTING: base URL https (bukan file://) → Blob Worker diizinkan.
        wv.loadDataWithBaseURL("https://boardcoach.local/", html, "text/html", "utf-8", null)
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
        if (line.startsWith("ERR:")) { lastError = line.take(120); return }
        when {
            line.startsWith("uciok") -> { uciOk = true; send("isready") }
            line.startsWith("readyok") -> engineReady = true
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
        val wv = webView
        webView = null
        pageLoaded = false
        engineReady = false
        if (wv != null) wv.post { runCatching { wv.destroy() } }
    }
}
