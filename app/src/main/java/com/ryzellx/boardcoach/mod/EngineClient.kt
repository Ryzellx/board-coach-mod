package com.ryzellx.boardcoach.mod

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Klien UCI untuk Stockfish asm.js (stockfish.js) yang dijalankan di WebView
 * tersembunyi lewat Web Worker (blob). Tanpa binary native, tanpa NDK.
 *
 * Komunikasi:
 *  - WebView memuat halaman kecil yang membuat blob worker dari sumber stockfish.js
 *  - Kotlin mengirim perintah UCI via evaluateJavascript("send('...')")
 *  - Worker mengirim balasan via @JavascriptInterface(BCAndroid.onUci)
 */
class EngineClient(private val context: Context, private val profile: EloProfile) {

    private var webView: WebView? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private var bestLatch: CountDownLatch? = null
    private var bestResult: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        val sfJs = try {
            context.assets.open("stockfish.js").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } ?: run {
            ready = false
            return
        }

        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onUci(line: String) {
                handleLine(line)
            }
        }, "BCAndroid")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                flushPending()
                send("uci")
                // tunggu uciok lewat handleLine
            }
        }

        val escaped = sfJs.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val html = "<html><body><script>" +
            "var SF_SRC=\"$escaped\";" +
            "var blob=new Blob([SF_SRC],{type:'application/javascript'});" +
            "var worker=new Worker(URL.createObjectURL(blob));" +
            "worker.onmessage=function(e){BCAndroid.onUci(e.data)};" +
            "window.send=function(c){worker.postMessage(c)};" +
            "</script></body></html>"
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        webView = wv
    }

    private fun send(cmd: String) {
        val wv = webView ?: return
        if (!ready) { pending.addLast(cmd); return }
        wv.post { wv.evaluateJavascript("send(${jsonQuote(cmd)});", null) }
    }

    private fun flushPending() {
        while (pending.isNotEmpty()) send(pending.removeFirst())
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
        if (line.startsWith("uciok")) {
            applyProfile(profile)
        } else if (line.startsWith("bestmove")) {
            val parts = line.split(" ")
            bestResult = parts.getOrNull(1)
            bestLatch?.countDown()
        }
    }

    private fun applyProfile(p: EloProfile) {
        send("setoption name Skill Level value ${p.skill}")
        send("setoption name UCI_LimitStrength value false")
    }

    /** Sinkron: kirim posisi + go, tunggu bestmove (timeout). */
    fun bestMove(fen: String, movetime: Int, depth: Int, timeoutMs: Long = 8000): String? {
        if (!ready) return null
        bestResult = null
        bestLatch = CountDownLatch(1)
        send("position fen $fen")
        send("go movetime $movetime depth $depth")
        bestLatch?.await(timeoutMs, TimeUnit.MILLISECONDS)
        bestLatch = null
        return bestResult
    }

    fun setSkill(skill: Int) { send("setoption name Skill Level value $skill") }

    fun close() {
        val wv = webView
        if (wv != null) {
            wv.post { wv.destroy() }
        }
        webView = null
        ready = false
    }
}
