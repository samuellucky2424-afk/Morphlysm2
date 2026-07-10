package top.niunaijun.blackboxa.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.vcam.Nv21Converter
import com.example.vcam.VirtualCameraBridge
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.MainActivity
import java.util.Locale

object StreamSessionController {
    data class Session(
        val sessionId: String,
        val model: String,
        val startedAtMs: Long,
        val maxSessionDurationSeconds: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var windowManager: WindowManager? = null
    private var windowView: View? = null
    private var expanded = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var previewListener: ((Bitmap) -> Unit)? = null
    private var stopCallback: (() -> Unit)? = null
    private var appContext: Context? = null
    private var lastSharedPreviewCounter = 0L
    private const val MINI_PREVIEW_POLL_MS = 333L

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateWindow()
            if (session != null) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val previewPollRunnable = object : Runnable {
        override fun run() {
            refreshSharedPreviewFromStore(force = false)
            if (session != null && windowView != null) {
                mainHandler.postDelayed(this, MINI_PREVIEW_POLL_MS)
            }
        }
    }

    fun isActive(): Boolean = session != null

    fun currentSessionId(): String? = session?.sessionId

    fun isMiniWindowVisible(): Boolean = windowView != null

    fun refreshWindow(context: Context) {
        if (session != null && windowView != null) {
            updateWindow()
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun overlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    fun start(
        context: Context,
        sessionId: String,
        model: String,
        maxSessionDurationSeconds: Int,
        onStopRequested: (() -> Unit)? = null
    ) {
        hideWindow()
        appContext = context.applicationContext
        session = Session(
            sessionId = sessionId,
            model = model,
            startedAtMs = System.currentTimeMillis(),
            maxSessionDurationSeconds = maxSessionDurationSeconds
        )
        stopCallback = onStopRequested
        expanded = false
        lastSharedPreviewCounter = 0L
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(previewPollRunnable)
    }

    fun showMiniWindow(context: Context): Boolean {
        if (session == null || !hasOverlayPermission(context)) {
            return false
        }
        showWindow(context.applicationContext)
        updateWindow()
        refreshSharedPreviewFromStore(force = true)
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
        mainHandler.removeCallbacks(previewPollRunnable)
        mainHandler.post(previewPollRunnable)
        return windowView != null
    }

    fun stop() {
        session = null
        stopCallback = null
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(previewPollRunnable)
        hideWindow()
    }

    fun openClonedApps(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    private fun showWindow(context: Context) {
        if (!hasOverlayPermission(context)) {
            return
        }
        if (windowView != null) {
            return
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_stream_session_window, null)
        appContext = context.applicationContext
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 160
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragStartX - (event.rawX - touchStartX).toInt()
                    params.y = dragStartY + (event.rawY - touchStartY).toInt()
                    runCatching { manager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - touchStartX) + kotlin.math.abs(event.rawY - touchStartY)
                    if (moved < 12f) {
                        setExpanded(!expanded)
                    }
                    true
                }
                else -> false
            }
        }

        view.findViewById<Button>(R.id.btnStreamMiniStop).setOnClickListener {
            stopCallback?.invoke() ?: stop()
        }
        view.findViewById<Button>(R.id.btnStreamMiniOpenApps).setOnClickListener {
            setExpanded(false)
            openClonedApps(context)
        }

        windowManager = manager
        windowView = view
        runCatching { manager.addView(view, params) }
            .onFailure {
                windowManager = null
                windowView = null
                return
            }

        val listener: (Bitmap) -> Unit = { bitmap ->
            val currentView = windowView
            if (currentView === view && !bitmap.isRecycled) {
                view.findViewById<ImageView>(R.id.ivStreamMiniPreviewCollapsed).setImageBitmap(bitmap)
                view.findViewById<ImageView>(R.id.ivStreamMiniPreviewExpanded).setImageBitmap(bitmap)
            }
        }
        previewListener = listener
        StreamPreviewBridge.addListener(listener)
        refreshSharedPreviewFromStore(force = true)
    }

    private fun hideWindow() {
        val view = windowView
        val manager = windowManager
        if (view != null && manager != null) {
            runCatching { manager.removeView(view) }
        }
        previewListener?.let { StreamPreviewBridge.removeListener(it) }
        previewListener = null
        windowView = null
        windowManager = null
    }

    private fun refreshSharedPreviewFromStore(force: Boolean) {
        val context = appContext ?: return
        if (windowView == null) {
            return
        }
        val frame = VirtualCameraBridge.latestOrPlaceholder(
            VirtualCameraBridge.DEFAULT_SLOT,
            320,
            568
        )
        val marker = if (frame.counter != 0L) frame.counter else frame.timestampNs
        if (!force && marker == lastSharedPreviewCounter) {
            return
        }
        lastSharedPreviewCounter = marker
        runCatching {
            StreamPreviewBridge.publish(Nv21Converter.nv21ToBitmap(frame.nv21, frame.width, frame.height))
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        val view = windowView ?: return
        view.findViewById<View>(R.id.streamMiniCollapsed).visibility =
            if (expanded) View.GONE else View.VISIBLE
        view.findViewById<LinearLayout>(R.id.streamMiniExpanded).visibility =
            if (expanded) View.VISIBLE else View.GONE
        updateWindow()
    }

    private fun updateWindow() {
        val activeSession = session ?: return
        val view = windowView ?: return
        val elapsedSeconds = ((System.currentTimeMillis() - activeSession.startedAtMs) / 1000).coerceAtLeast(0)
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val elapsed = String.format(Locale.US, "%02d:%02d", minutes, seconds)
        view.findViewById<TextView>(R.id.tvStreamMiniElapsed).text = elapsed
        view.findViewById<TextView>(R.id.tvStreamMiniStatus).text =
            "Live output frame bridge active - $elapsed"
        view.findViewById<TextView>(R.id.tvStreamMiniSession).text =
            "Session: ${activeSession.sessionId.takeLast(8)}  Model: ${activeSession.model}"
    }
}
