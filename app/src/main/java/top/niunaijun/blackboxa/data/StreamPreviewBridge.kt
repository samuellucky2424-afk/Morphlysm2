package top.niunaijun.blackboxa.data

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object StreamPreviewBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Bitmap) -> Unit>()
    private val deliveryScheduled = AtomicBoolean(false)
    @Volatile private var latestBitmap: Bitmap? = null

    fun publish(bitmap: Bitmap) {
        val previous = latestBitmap
        latestBitmap = bitmap
        recycleWhenSuperseded(previous, bitmap)
        scheduleLatestDelivery()
    }

    private fun scheduleLatestDelivery() {
        if (!deliveryScheduled.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            val bitmap = latestBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                listeners.forEach { listener -> listener(bitmap) }
            }
            deliveryScheduled.set(false)
            if (latestBitmap !== bitmap) {
                scheduleLatestDelivery()
            }
        }
    }

    private fun recycleWhenSuperseded(previous: Bitmap?, replacement: Bitmap?) {
        if (previous == null || previous === replacement || previous.isRecycled) {
            return
        }
        mainHandler.postDelayed({
            if (latestBitmap !== previous && !previous.isRecycled) {
                previous.recycle()
            }
        }, 300L)
    }

    fun addListener(listener: (Bitmap) -> Unit) {
        listeners.add(listener)
        latestBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                mainHandler.post { listener(bitmap) }
            }
        }
    }

    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun removeListener(listener: (Bitmap) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        val previous = latestBitmap
        latestBitmap = null
        recycleWhenSuperseded(previous, null)
    }
}
