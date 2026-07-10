package top.niunaijun.blackboxa.data

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object StreamPreviewBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Bitmap) -> Unit>()
    @Volatile private var latestBitmap: Bitmap? = null

    fun publish(bitmap: Bitmap) {
        val previous = latestBitmap
        latestBitmap = bitmap
        mainHandler.post {
            listeners.forEach { listener ->
                if (!bitmap.isRecycled) {
                    listener(bitmap)
                }
            }
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                mainHandler.postDelayed({
                    if (latestBitmap !== previous && !previous.isRecycled) {
                        previous.recycle()
                    }
                }, 2000L)
            }
        }
    }

    fun addListener(listener: (Bitmap) -> Unit) {
        listeners.add(listener)
        latestBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                mainHandler.post { listener(bitmap) }
            }
        }
    }

    fun removeListener(listener: (Bitmap) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        val previous = latestBitmap
        latestBitmap = null
        if (previous != null && !previous.isRecycled) {
            mainHandler.postDelayed({
                if (!previous.isRecycled) {
                    previous.recycle()
                }
            }, 2000L)
        }
    }
}
