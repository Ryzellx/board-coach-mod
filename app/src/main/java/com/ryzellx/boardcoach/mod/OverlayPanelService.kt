package com.ryzellx.boardcoach.mod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.Bitmap

/** Service panel overlay + capture + engine. */
class OverlayPanelService : android.app.Service() {

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var chevron: FrameLayout? = null
    private var arrowOverlay: FrameLayout? = null
    private var arrowView: ArrowOverlayView? = null
    private var capture: ScreenCapture? = null
    private var engine: EngineClient? = null
    private var calibrator = BoardCalibrator()
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunning = false
    private var engineOn = false
    private var autoMove = false
    private var arrowsOn = true
    private var elo = 1600
    private var moveDelay = 600L
    private var lastFen: String? = null
    private var lastBest: String? = null
    private var pollToken = 0
    private val stats = Classifier.emptyStats()
    private var lossSum = 0.0
    private var bestMoveShown = false

    companion object {
        var pendingProjection: Pair<Int, Intent>? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf(); return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel("bc", "Board Coach", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
            startForeground(1, android.app.Notification.Builder(this, "bc")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Board Coach")
                .setContentText("Mod menu aktif")
                .build())
        }

        if (panel == null) buildPanel()
        ensureArrowOverlay()
        // ambil projection kalau user baru saja memberi izin
        pendingProjection?.let { (rc, data) ->
            pendingProjection = null
            attachProjection(rc, data)
        }
        if (capture == null) requestCapture()
        scanRunning = true
        startScan()
        return START_STICKY
    }

    private fun captureStarted(): Boolean = capture != null

    private fun requestCapture() {
        // permission result dikembalikan lewat MainActivity → pendingProjection
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "request_capture"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** Dipanggil dari MainActivity setelah user memberi izin. */
    fun attachProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
        capture?.stop()
        capture = ScreenCapture(this, projection)
        capture?.start()
    }

    /* ---------------- UI ---------------- */

    private fun buildPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(com.ryzellx.boardcoach.mod.R.drawable.bg_panel)
            setPadding(14, 10, 14, 14)
        }

        val title = TextView(this).apply { text = "♞ Board Coach" }
        panel.addView(title)

        // status
        val status = TextView(this).apply { text = "status: idle" }
        panel.addView(status)

        // ELO row
        panel.addView(TextView(this).apply { text = "ELO target" })
        val eloVal = TextView(this).apply { text = "1600"; textSize = 14f }
        panel.addView(eloVal)
        val eloSb = SeekBar(this).apply {
            max = 2200; progress = elo - 600
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    elo = p + 600; eloVal.text = elo.toString()
                    updateEngineProfile()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(eloSb)

        // toggles
        val tEngine = makeToggle("Engine ON")
        val tAuto = makeToggle("Auto-move")
        val tArrows = makeToggle("Panah")
        tEngine.setOnClickListener { engineOn = !engineOn; tEngine.isSelected = engineOn; status.text = "status: ${if (engineOn) "engine ON" else "idle"}" }
        tAuto.setOnClickListener { autoMove = !autoMove; tAuto.isSelected = autoMove }
        tArrows.setOnClickListener { arrowsOn = !arrowsOn; tArrows.isSelected = arrowsOn; if (!arrowsOn) arrowView?.clear() }
        panel.addView(tEngine)
        panel.addView(tAuto)
        panel.addView(tArrows)

        // log
        val log = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            maxLines = 8
            isVerticalScrollBarEnabled = true
        }
        panel.addView(log, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140))

        val scroll = ScrollView(this)
        scroll.addView(panel)

        val params = WindowManager.LayoutParams(
            300, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = -20; y = 120
        }

        // drag handle
        val drag = TextView(this).apply { text = "≡"; textSize = 20f }
        panel.addView(drag, 0)

        val container = FrameLayout(this)
        container.addView(scroll)
        val close = Button(this).apply { text = "—" }
        container.addView(close, FrameLayout.LayoutParams(60, 60, Gravity.TOP or Gravity.END))

        wm.addView(container, params)
        this.panel = container

        // chevron (minimized)
        val ch = FrameLayout(this).apply {
            background = getDrawable(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
        }
        val chTxt = TextView(this).apply { text = "♞"; textSize = 24f }
        ch.addView(chTxt, FrameLayout.LayoutParams(70, 70, Gravity.CENTER))
        ch.setOnClickListener { container.visibility = View.VISIBLE; ch.visibility = View.GONE }
        wm.addView(ch, WindowManager.LayoutParams(
            70, 70, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = -16; y = 140 })
        chevron = ch

        close.setOnClickListener {
            container.visibility = View.GONE
            chevron?.visibility = View.VISIBLE
        }

        // drag
        container.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE) {
                params.x = e.rawX.toInt() - container.width / 2
                params.y = e.rawY.toInt() - 40
                wm.updateViewLayout(container, params)
            }
            true
        }

        println("BC panel built")
    }

    private fun makeToggle(s: String): TextView = TextView(this).apply {
        text = "• $s"
        setBackgroundResource(com.ryzellx.boardcoach.mod.R.drawable.bg_button)
        setPadding(14, 10, 14, 10)
        setTextColor(0xFFE2E8F0.toInt())
        isSelected = false
    }

    private fun ensureArrowOverlay() {
        if (arrowOverlay != null) return
        val view = ArrowOverlayView(this)
        val fl = FrameLayout(this)
        fl.addView(view)
        fl.isClickable = false
        fl.isFocusable = false
        wm.addView(fl, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ))
        arrowOverlay = fl
        arrowView = view
    }

    /* ---------------- scan loop ---------------- */

    private fun startScan() {
        val token = ++pollToken
        val runnable = object : Runnable {
            override fun run() {
                if (pollToken != token || !scanRunning) return
                try { tick() } catch (e: Exception) { /* noop */ }
                handler.postDelayed(this, 450)
            }
        }
        handler.post(runnable)
    }

    private fun tick() {
        val cap = capture ?: return
        val bmp = cap.grab() ?: return
        val w = bmp.width; val h = bmp.height
        calibrator.setScreen(w, h)
        val rect = BoardReader.findBoard(bmp)
        if (rect != null) calibrator.calibrate(rect)

        val fen = try { BoardReader.readFen(bmp) } catch (e: Exception) { null } ?: return
        val changed = BoardCalibrator.fenChanged(lastFen ?: "", fen)
        if (changed) {
            lastFen = fen
            if (engineOn) {
                requestMove(fen)
            }
        } else if (engineOn && bestMoveShown) {
            // tidak ada perubahan — jangan gambarkan ulang
        }
    }

    private fun requestMove(fen: String) {
        val prof = EloProfile.forElo(elo)
        if (engine == null && engineOn) {
            engine = EngineClient(this, prof)
            engine?.start()
        }
        val eng = engine ?: return
        val mv = eng.bestMove(fen, prof.movetime, prof.depth) ?: return
        lastBest = mv
        bestMoveShown = true
        if (mv.length >= 4) {
            val from = mv.substring(0, 2)
            val to = mv.substring(2, 4)
            if (arrowsOn) drawArrow(from, to, 0xFF81B64C.toInt())
            if (autoMove) scheduleAutoMove(from, to)
        }
    }

    private fun drawArrow(from: String, to: String, color: Int) {
        val av = arrowView ?: return
        val f = from[0] - 'a'
        val r = from[1] - '1'
        val t = to[0] - 'a'
        val tr = to[1] - '1'
        val s1 = calibrator.squareCenter(f, r) ?: return
        val s2 = calibrator.squareCenter(t, tr) ?: return
        av.setArrows(listOf(ArrowOverlayView.Arrow(s1.first.toFloat(), s1.second.toFloat(), s2.first.toFloat(), s2.second.toFloat(), color)))
    }

    private fun scheduleAutoMove(from: String, to: String) {
        handler.postDelayed({
            val f = from[0] - 'a'
            val r = from[1] - '1'
            val t = to[0] - 'a'
            val tr = to[1] - '1'
            val s1 = calibrator.squareCenter(f, r)
            val s2 = calibrator.squareCenter(t, tr)
            if (s1 != null && s2 != null) {
                val svc = BoardReaderService.service
                if (svc != null) {
                    svc.tap(s1.first, s1.second)
                    handler.postDelayed({ svc.tap(s2.first, s2.second) }, 120)
                }
            }
        }, moveDelay)
    }

    private fun updateEngineProfile() {
        // restart engine dengan profil baru kalau perlu
        val old = engine
        if (old != null && engineOn) {
            old.close()
            engine = null
        }
    }

    override fun onDestroy() {
        scanRunning = false
        pollToken++
        capture?.stop()
        engine?.close()
        runCatching { panel?.let { wm.removeView(it) } }
        runCatching { chevron?.let { wm.removeView(it) } }
        runCatching { arrowOverlay?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
