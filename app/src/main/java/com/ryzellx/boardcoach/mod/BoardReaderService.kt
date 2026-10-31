package com.ryzellx.boardcoach.mod

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

/**
 * Service aksesibilitas:
 *  - injeksi gesture tap (auto-move)
 *  - AMBIL SCREENSHOT lewat takeScreenshot() (API 30+)
 *
 * takeScreenshot() MENGGANTIKAN MediaProjection: tidak perlu dialog izin capture,
 * tidak perlu foreground service bertipe mediaProjection, dan tidak kena batasan
 * Android 14/15/16 soal MediaProjection. Cukup 1 izin Aksesibilitas.
 */
class BoardReaderService : AccessibilityService() {

    companion object {
        var lastTap: Pair<Int, Int>? = null
        var enabled = false
        var service: BoardReaderService? = null
        var lastShotError: String? = null
    }

    private val exec: Executor = Executor { it.run() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
        service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // hook kalau perlu deteksi window
    }

    /** Tap di koordinat layar (absolute). */
    fun tap(x: Int, y: Int): Boolean {
        return try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
            lastTap = x to y
            true
        } catch (e: Exception) {
            false
        }
    }

    val canShot: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Ambil screenshot layar (async). cb(null) kalau gagal. */
    fun screenshot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { lastShotError = "butuh Android 11+"; cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        sw
                    } catch (e: Exception) {
                        lastShotError = "wrap: ${e.javaClass.simpleName}"
                        null
                    }
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    lastShotError = "err $errorCode"
                    cb(null)
                }
            })
        } catch (e: Throwable) {
            lastShotError = e.javaClass.simpleName
            cb(null)
        }
    }

    override fun onInterrupt() {
        enabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        enabled = false
        service = null
    }
}
