package com.ryzellx.boardcoach.mod

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Capture frame layar via MediaProjection.
 *
 * Android 14 (API 34) mewajibkan MediaProjection.registerCallback() dipanggil
 * SEBELUM createVirtualDisplay() — kalau tidak: IllegalStateException → crash.
 * Token MediaProjection juga single-use per sesi capture.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    private val w: Int
    private val h: Int
    private val dpi: Int

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopped = true
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            virtualDisplay = null
            imageReader = null
        }
    }

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        w = metrics.widthPixels
        h = metrics.heightPixels
        dpi = metrics.densityDpi
    }

    /** @throws IllegalStateException / SecurityException — caller wajib catch. */
    fun start() {
        // WAJIB di Android 14+ sebelum createVirtualDisplay
        projection.registerCallback(callback, handler)

        imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "bc-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler,
        )
    }

    val isRunning: Boolean get() = !stopped && virtualDisplay != null

    /** Ambil bitmap terbaru. Kalau gagal return null. */
    fun grab(): Bitmap? {
        if (stopped) return null
        val ir = imageReader ?: return null
        val image = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, w, h)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { image.close() }
        }
    }

    fun stop() {
        stopped = true
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        virtualDisplay = null
        imageReader = null
    }
}
