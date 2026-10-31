package com.ryzellx.boardcoach.mod

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Service overlay: panel mod menu + panah + capture + engine.
 *
 * FIX v1.1:
 *  - scan + engine jalan di WORKER thread (dulu di main → UI beku sampai 8 detik).
 *  - GUI baru: header bisa di-drag, tombol minimize (bubble), resize (3 ukuran),
 *    tombol stop, indikator best move, statistik, tombol kalibrasi papan.
 */
class OverlayPanelService : android.app.Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var work: Handler

    // views
    private var panelRoot: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var arrowOverlay: FrameLayout? = null
    private var arrowView: ArrowOverlayView? = null
    private var calibrateOverlay: View? = null

    private var statusTv: TextView? = null
    private var bestTv: TextView? = null
    private var statsTv: TextView? = null
    private var logTv: TextView? = null
    private var eloTv: TextView? = null
    private var chipEngine: TextView? = null
    private var chipAuto: TextView? = null
    private var chipArrows: TextView? = null

    // state
    private var capture: ScreenCapture? = null
    private var engine: EngineClient? = null
    private var calibrator = BoardCalibrator()
    private val stats = MoveStats()

    @Volatile private var scanRunning = false
    @Volatile private var engineOn = true
    @Volatile private var autoMove = false
    @Volatile private var arrowsOn = true
    @Volatile private var elo = 1400
    @Volatile private var scanToken = 0

    private var moveDelay = 600L
    private var lastFen: String? = null
    private var lastBest: String? = null
    private var lastCp: Double? = null
    private var lastAutoMove: String? = null
    private var statsVisible = false
    private var widthIdx = 1
    private var locateTries = 0

    companion object {
        var pendingProjection: Pair<Int, Intent>? = null
        private val WIDTHS_DP = intArrayOf(230, 290, 370)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        workerThread = HandlerThread("bc-scan").also { it.start() }
        work = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        try {
            startAsForeground()
            ensureArrowOverlay()
            if (panelRoot == null) buildPanel()
            if (engineOn) ensureEngine()
            pendingProjection?.let { (rc, data) ->
                pendingProjection = null
                attachProjection(rc, data)
            }
            scanRunning = true
            startScan()
            if (capture == null) setStatus("butuh izin capture — tap tombol 4 di app")
        } catch (e: Throwable) {
            CrashLogger.log(this, "onStartCommand", e)
            setStatus("gagal start: ${e.javaClass.simpleName}")
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel("bc", "Board Coach", android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val notif = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, "bc")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Board Coach").setContentText("Mod menu aktif").build()
        else
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Board Coach").setContentText("Mod menu aktif").build()

        if (Build.VERSION.SDK_INT >= 34) {
            androidx.core.app.ServiceCompat.startForeground(
                this, 1, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun attachProjection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            capture?.stop()
            capture = ScreenCapture(this, projection).also { it.start() }
            setStatus("capture aktif — baca papan")
        } catch (e: Throwable) {
            CrashLogger.log(this, "attachProjection", e)
            capture = null
            setStatus("capture gagal: ${e.javaClass.simpleName}")
        }
    }

    /* ==================== UI ==================== */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun headerBtn(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }

    private fun chip(label: String, on: Boolean): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundResource(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
            setTextColor(0xFFE2E8F0.toInt())
            isSelected = on
            updateChip(this, on)
        }

    private fun updateChip(v: TextView, on: Boolean) {
        v.setTextColor(if (on) 0xFF22C55E.toInt() else 0xFF94A3B8.toInt())
        v.text = v.text.toString().trimStart('●', '○', ' ').let { "$it" }
        v.text = (if (on) "● " else "○ ") + v.text
    }

    private fun buildPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(com.ryzellx.boardcoach.mod.R.drawable.bg_panel)
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val params = WindowManager.LayoutParams(
            dp(WIDTHS_DP[widthIdx]), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(10); y = dp(140) }

        /* ---- header (drag handle) ---- */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "♞ Board Coach"
            textSize = 16f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        header.addView(headerBtn("⤢") { cycleWidth() })
        header.addView(headerBtn("—") { minimizeToBubble() })
        header.addView(headerBtn("✕") { stopMod() })

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        val dragListener = View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
        header.setOnTouchListener(dragListener)
        title.setOnTouchListener(dragListener)
        root.addView(header)

        /* ---- body ---- */
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        statusTv = TextView(this).apply {
            text = "status: idle"; textSize = 12f; setTextColor(0xFF94A3B8.toInt())
        }
        body.addView(statusTv)

        bestTv = TextView(this).apply {
            text = "best: —"; textSize = 17f; setTextColor(0xFF22C55E.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        body.addView(bestTv)

        val eloRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eloRow.addView(TextView(this).apply {
            text = "ELO target"; textSize = 12f; setTextColor(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        eloTv = TextView(this).apply { text = elo.toString(); textSize = 13f; setTextColor(0xFFE2E8F0.toInt()) }
        eloRow.addView(eloTv)
        body.addView(eloRow)

        val seek = SeekBar(this).apply {
            max = 2200; progress = elo - 600
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    elo = p + 600
                    eloTv?.text = elo.toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        body.addView(seek)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipEngine = chip("Engine", engineOn).apply {
            setOnClickListener {
                engineOn = !engineOn
                updateChip(this, engineOn)
                if (engineOn) ensureEngine()
                setStatus(if (engineOn) "engine ON" else "engine OFF")
            }
        }
        chipAuto = chip("Auto-move", autoMove).apply {
            setOnClickListener { autoMove = !autoMove; updateChip(this, autoMove) }
        }
        chipArrows = chip("Panah", arrowsOn).apply {
            setOnClickListener {
                arrowsOn = !arrowsOn; updateChip(this, arrowsOn)
                if (!arrowsOn) main.post { arrowView?.clear() }
            }
        }
        listOf(chipEngine!!, chipAuto!!, chipArrows!!).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
            chips.addView(it)
        }
        body.addView(chips)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val calBtn = TextView(this).apply {
            text = "Kalibrasi manual"; textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
            setTextColor(0xFFE2E8F0.toInt())
            setOnClickListener { startCalibration() }
        }
        val statsBtn = TextView(this).apply {
            text = "Statistik"; textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
            setTextColor(0xFFE2E8F0.toInt())
            setOnClickListener {
                statsVisible = !statsVisible
                statsTv?.visibility = if (statsVisible) View.VISIBLE else View.GONE
                updateStatsUi()
            }
        }
        listOf(calBtn, statsBtn).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
            btnRow.addView(it)
        }
        body.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val learnBtn = TextView(this).apply {
            text = "Belajar ulang"; textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
            setTextColor(0xFFE2E8F0.toInt())
            setOnClickListener { learnPieces() }
        }
        val flipChip = chip("Putar manual", BoardReader.flipped).apply {
            setOnClickListener {
                BoardReader.flipped = !BoardReader.flipped
                updateChip(this, BoardReader.flipped)
                setStatus(if (BoardReader.flipped) "papan diputar (main hitam)" else "papan normal")
            }
        }
        listOf(learnBtn, flipChip).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
            btnRow2.addView(it)
        }
        body.addView(btnRow2)

        statsTv = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFCBD5E1.toInt())
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(4))
        }
        body.addView(statsTv)

        logTv = TextView(this).apply {
            textSize = 10f; setTextColor(0xFF64748B.toInt())
            maxLines = 6
            setPadding(0, dp(4), 0, 0)
        }
        body.addView(logTv)

        root.addView(body)

        wm.addView(root, params)
        panelRoot = root
        panelParams = params

        buildBubble()
    }

    private fun buildBubble() {
        val ch = FrameLayout(this).apply {
            background = getDrawable(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
        }
        ch.addView(TextView(this).apply { text = "♞"; textSize = 24f; setTextColor(0xFF22C55E.toInt()) },
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        val bp = WindowManager.LayoutParams(
            dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(10); y = dp(140) }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        ch.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bp.x; sy = bp.y; tx = e.rawX; ty = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - tx) > dp(8) || Math.abs(e.rawY - ty) > dp(8)) moved = true
                    bp.x = sx + (e.rawX - tx).toInt()
                    bp.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(ch, bp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { ch.visibility = View.GONE; panelRoot?.visibility = View.VISIBLE }
                    true
                }
                else -> false
            }
        }
        ch.visibility = View.GONE
        wm.addView(ch, bp)
        bubble = ch
        bubbleParams = bp
    }

    private fun cycleWidth() {
        widthIdx = (widthIdx + 1) % WIDTHS_DP.size
        val p = panelParams ?: return
        p.width = dp(WIDTHS_DP[widthIdx])
        runCatching { panelRoot?.let { wm.updateViewLayout(it, p) } }
    }

    private fun minimizeToBubble() {
        panelRoot?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
    }

    private fun stopMod() {
        Toast.makeText(this, "Board Coach dihentikan", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private fun learnPieces() {
        val cap = capture ?: run { setStatus("butuh izin capture dulu"); return }
        val rect = calibrator.rect ?: run { setStatus("kalibrasi papan dulu"); return }
        setStatus("belajar bidak…")
        work.post {
            try {
                val bmp = cap.grab()
                if (bmp == null) { setStatus("gagal ambil frame"); return@post }
                val n = PieceTemplates.learn(BoardPixels(bmp, rect), BoardReader.flipped)
                setStatus(if (n >= 10) "bidak dipelajari: $n/12 — siap" else "cuma $n/12 — papan harus posisi awal")
                logLine("learn bidak $n/12")
            } catch (e: Throwable) {
                CrashLogger.log(this@OverlayPanelService, "learnPieces", e)
                setStatus("belajar gagal: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun ensureArrowOverlay() {
        if (arrowOverlay != null) return
        val view = ArrowOverlayView(this)
        val fl = FrameLayout(this)
        fl.addView(view)
        fl.isClickable = false; fl.isFocusable = false
        wm.addView(fl, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))
        arrowOverlay = fl
        arrowView = view
    }

    private fun startCalibration() {
        if (calibrateOverlay != null) return
        minimizeToBubbleKeepPanelHidden()
        val v = CalibrateView(this,
            onDone = { rect ->
                calibrator.clear()
                calibrator.calibrate(rect)
                locateTries = 0
                removeCalibration()
                setStatus("papan dikalibrasi manual")
                logLine("kalibrasi manual ok")
            },
            onCancel = { removeCalibration() })
        wm.addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT))
        calibrateOverlay = v
        setStatus("kalibrasi: tap 2 pojok papan")
    }

    private fun minimizeToBubbleKeepPanelHidden() { panelRoot?.visibility = View.GONE }

    private fun removeCalibration() {
        runCatching { calibrateOverlay?.let { wm.removeView(it) } }
        calibrateOverlay = null
        panelRoot?.visibility = View.VISIBLE
    }

    /** Update status (aman dari thread mana pun). */
    private fun setStatus(s: String) { main.post { statusTv?.text = "status: $s" } }

    private fun logLine(s: String) {
        main.post {
            val v = logTv ?: return@post
            val lines = (v.text.toString().split("\n") + s).filter { it.isNotBlank() }
            v.text = lines.takeLast(6).joinToString("\n")
        }
    }

    private fun updateStatsUi() {
        main.post { statsTv?.text = stats.summary() }
    }

    private fun setBestUi(text: String) { main.post { bestTv?.text = text } }

    /* ==================== engine ==================== */

    private fun ensureEngine() {
        if (engine == null) {
            val e = EngineClient(applicationContext)
            engine = e
            e.start() // main thread
            setStatus("engine memuat…")
        }
    }

    /* ==================== scan loop (WORKER) ==================== */

    private fun startScan() {
        scanRunning = true
        val token = ++scanToken
        work.post(object : Runnable {
            override fun run() {
                if (!scanRunning || scanToken != token) return
                try { tick() } catch (e: Throwable) { CrashLogger.log(this@OverlayPanelService, "tick", e) }
                work.postDelayed(this, 400)
            }
        })
    }

    private fun tick() {
        val cap = capture ?: return
        val bmp = cap.grab() ?: return

        var rect = calibrator.rect
        if (rect == null) {
            if (locateTries >= 25) { setStatus("papan tidak ketemu — tap Kalibrasi manual"); return }
            locateTries++
            val hit = BoardLocator.locate(bmp)
            if (hit == null) { setStatus("mencari papan… ($locateTries)"); return }
            calibrator.calibrate(hit.rect)
            rect = hit.rect
            logLine("auto-papan ${(hit.score * 100).toInt()}%")
        }
        val board = rect!!

        val fen = try { BoardReader.readFenFrom(bmp, board) } catch (e: Exception) { null } ?: return

        // AUTO-LEARN: begitu papan terlihat di posisi awal, bidak dipelajari sendiri
        if (!PieceTemplates.isLearned() && PieceTemplates.looksLikeOpening(BoardReader.lastGrid)) {
            val n = PieceTemplates.learn(BoardPixels(bmp, board), BoardReader.flipped)
            if (n >= 10) {
                logLine("auto-learn bidak $n/12")
                setStatus("bidak dipelajari otomatis ($n/12)")
                lastFen = null
                return
            }
        }

        if (fen == lastFen) return

        val prev = lastFen
        val prevBest = lastBest
        val prevCp = lastCp
        lastFen = fen
        val conf = if (PieceTemplates.isLearned()) " · baca ${(PieceTemplates.lastConfidence * 100).toInt()}%"
        else " · tunggu posisi awal utk belajar bidak"
        setStatus("papan ok$conf")

        // klasifikasi langkah yang baru saja dimainkan
        val eng = engine
        if (prev != null && prevBest != null && eng != null && eng.isReady) {
            val played = FenDiff.playedMove(prev, fen)
            if (played != null && played != lastAutoMove) {
                val res = eng.analyze(fen, 250, 8, 3000)
                val cpOpp = res?.cp
                val cpAfterOurs = if (cpOpp != null) -cpOpp else null
                val loss = if (prevCp != null && cpAfterOurs != null)
                    (prevCp - cpAfterOurs).coerceAtLeast(0.0) else 0.0
                val cat = Classifier.classify(
                    cpBefore = prevCp ?: 0.0,
                    cpAfter = cpAfterOurs ?: 0.0,
                    legalCount = 20, isBook = false,
                    playedUci = played, bestUci = prevBest, secondGap = 0.0,
                    matBefore = 0, matAfter = 0)
                stats.add(cat, loss)
                updateStatsUi()
                setBestUi("terakhir ${played}  ${cat.glyph} ${cat.label}")
                logLine("main $played -> ${cat.label}")
            }
        }

        if (engineOn) requestMove(fen)
    }

    private fun requestMove(fen: String) {
        val eng = engine ?: return
        if (!eng.isReady) { setStatus("engine memuat…"); return }
        val prof = EloProfile.forElo(elo)
        val res = eng.analyzeWithSkill(fen, prof.skill, prof.movetime, prof.depth, 8000) ?: return
        lastBest = res.best
        lastCp = res.cp
        val cpTxt = res.cp?.let { " (${it.toInt()})" } ?: ""
        setBestUi("best ${res.best}$cpTxt")
        logLine("best ${res.best}")

        if (res.best.length >= 4) {
            val from = res.best.substring(0, 2)
            val to = res.best.substring(2, 4)
            val color = when {
                res.cp != null && res.cp > 200 -> 0xFF22C55E.toInt()
                res.cp != null && res.cp < -200 -> 0xFFEF4444.toInt()
                else -> 0xFF38BDF8.toInt()
            }
            if (arrowsOn) drawArrow(from, to, color)
            if (autoMove) scheduleAutoMove(from, to)
        }
    }

    private fun drawArrow(from: String, to: String, color: Int) {
        val f = from[0] - 'a'; val r = from[1] - '1'
        val t = to[0] - 'a'; val tr = to[1] - '1'
        val s1 = calibrator.squareCenter(f, r) ?: return
        val s2 = calibrator.squareCenter(t, tr) ?: return
        main.post {
            arrowView?.setArrows(listOf(ArrowOverlayView.Arrow(
                s1.first.toFloat(), s1.second.toFloat(),
                s2.first.toFloat(), s2.second.toFloat(), color)))
        }
    }

    private fun scheduleAutoMove(from: String, to: String) {
        val svc = BoardReaderService.service ?: return
        main.postDelayed({
            val s1 = calibrator.squareCenter(from[0] - 'a', from[1] - '1') ?: return@postDelayed
            val s2 = calibrator.squareCenter(to[0] - 'a', to[1] - '1') ?: return@postDelayed
            lastAutoMove = from + to
            svc.tap(s1.first, s1.second)
            main.postDelayed({ svc.tap(s2.first, s2.second) }, 140)
        }, moveDelay)
    }

    override fun onDestroy() {
        scanRunning = false
        scanToken++
        runCatching { capture?.stop() }
        runCatching { engine?.close() }
        runCatching { panelRoot?.let { wm.removeView(it) } }
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { arrowOverlay?.let { wm.removeView(it) } }
        runCatching { calibrateOverlay?.let { wm.removeView(it) } }
        runCatching { workerThread.quitSafely() }
        super.onDestroy()
    }
}
