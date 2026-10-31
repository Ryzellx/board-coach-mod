package com.ryzellx.boardcoach.mod

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Service aksesibilitas: injeksi gesture tap pada koordinat layar untuk auto-move.
 * Juga dipakai untuk tahu kapan window chess.com terbuka.
 */
class BoardReaderService : AccessibilityService() {

    companion object {
        var lastTap: Pair<Int, Int>? = null
        var enabled = false
        var service: BoardReaderService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
        service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // dipakai nanti untuk deteksi window chess.com
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

    override fun onInterrupt() {
        enabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        enabled = false
        service = null
    }
}
