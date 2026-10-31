package com.ryzellx.boardcoach.mod

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.ComponentName

class MainActivity : Activity() {

    private val REQ_OVERLAY = 101
    private val REQ_PROJECTION = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 40)
        }
        root.addView(TextView(this).apply {
            text = "♞ Board Coach Mod"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Mod menu untuk aplikasi chess.com (Android).\n" +
                    "Baca papan dari layar, jalankan engine lokal, gambar panah, auto-move.\n" +
                    "Nol root. Butuh 3 izin: Overlay, Aksesibilitas, Capture Layar."
            textSize = 14f
            setPadding(0, 10, 0, 24)
        })

        fun btn(label: String, action: () -> Unit): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener { action() }
            root.addView(b)
            return b
        }

        btn("1. Izin overlay") {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQ_OVERLAY,
                )
            } else Toast.makeText(this, "overlay sudah diizinkan", Toast.LENGTH_SHORT).show()
        }
        btn("2. Aktifkan Aksesibilitas") {
            // Android 13+ memblokir izin Aksesibilitas untuk app sideload:
            // harus buka App Info → ⋮ → "Izinkan setelan terbatas" dulu.
            android.app.AlertDialog.Builder(this)
                .setTitle("Android 13+ — setelan terbatas")
                .setMessage(
                    "Kalau muncul \"Aplikasi tidak diberi akses\" / " +
                    "\"Dikontrol oleh Setelan Terbatas\", lakukan ini:\n\n" +
                    "1. Tap tombol di bawah → halaman info app\n" +
                    "2. Tap ⋮ (titik tiga, kanan atas)\n" +
                    "3. Pilih \"Izinkan setelan terbatas\"\n" +
                    "4. Baru balik ke Aksesibilitas → aktifkan Board Coach\n\n" +
                    "Ini proteksi OS untuk app dari luar Play Store, bukan bug."
                )
                .setPositiveButton("Info app") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
                .setNeutralButton("Aksesibilitas") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
        btn("3. Aktifkan mod menu") {
            val intent = Intent(this, OverlayPanelService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "panel aktif — beri izin capture", Toast.LENGTH_SHORT).show()
            // langsung minta izin capture, biar user nggak perlu tap tombol lain
            root.postDelayed({ requestProjection() }, 600)
        }
        btn("4. Beri izin Capture Layar") {
            requestProjection()
        }
        btn("5. Buka chess.com") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.chess.com/play/computer")))
            } catch (e: Exception) {
                Toast.makeText(this, "gagal buka chess.com", Toast.LENGTH_SHORT).show()
            }
        }
        btn("Stop mod") {
            startService(Intent(this, OverlayPanelService::class.java).setAction("stop"))
        }
        btn("Kirim log crash") {
            val f = java.io.File(CrashLogger.path(this))
            if (!f.exists()) {
                Toast.makeText(this, "belum ada log crash", Toast.LENGTH_SHORT).show()
            } else {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", f)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Kirim log crash"))
            }
        }

        setContentView(root)

        if (intent?.action == "request_capture") {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            // teruskan ke service lewat companion
            OverlayPanelService.pendingProjection = resultCode to data
            startForegroundService(Intent(this, OverlayPanelService::class.java))
            Toast.makeText(this, "capture siap", Toast.LENGTH_SHORT).show()
        }
    }
}
