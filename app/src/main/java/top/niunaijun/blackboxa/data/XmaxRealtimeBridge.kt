package top.niunaijun.blackboxa.data

import ai.xmax.sdk.*
import android.app.Activity
import android.os.SystemClock
import android.widget.FrameLayout
import com.example.vcam.Nv21Converter
import com.example.vcam.VirtualCameraBridge
import com.example.vcam.VirtualCameraPreset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import top.niunaijun.blackboxa.vcam.VirtualCameraSurfaceService

/** Native XMax output: latest-frame-only processing, no bitmap polling or per-frame file writes. */
object XmaxRealtimeBridge {
    private var scope: CoroutineScope? = null
    private var realtime: XmaxRealtimeManaging? = null
    private var closing: Job? = null
    private var view: XmaxRealtimeDualVideoView? = null
    private var host: Activity? = null
    private var localTrackRef: RealtimeVideoTrack? = null
    private var remoteTrackRef: RealtimeVideoTrack? = null
    @Volatile private var renderingEnabled = true
    private var convertBuffer: ByteArray? = null
    private var chromaU: ByteArray? = null
    private var chromaV: ByteArray? = null
    private var sessionToken = ""
    private val publicationLock = Any()
    @Volatile var lastOutputAtMs = 0L
        private set
    @Volatile private var revision = 0
    private val outputSeconds = java.util.concurrent.ConcurrentSkipListSet<Long>()
    private var clockOffsetMs = 0L
    fun completedOutputSeconds(): List<Long> {
        val before = (System.currentTimeMillis() + clockOffsetMs) / 1000L
        val completed = outputSeconds.headSet(before).toList()
        outputSeconds.removeAll(completed.toSet())
        return completed
    }
    private val pendingFrame = AtomicReference<RealtimeVideoFrame?>()
    private val draining = AtomicBoolean(false)
    private val frameExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "XmaxCameraFrames") }

    fun connect(activity: Activity, container: FrameLayout, temporaryKey: String, prompt: String,
                image: ByteArray?, backCamera: Boolean, fps: Int, token: String, serverClockOffsetMs: Long,
                onStatus: (String) -> Unit, onFirstFrame: () -> Unit, onError: (Throwable) -> Unit) {
        disconnect()
        val currentRevision = revision
        host = activity
        sessionToken = token
        clockOffsetMs = serverClockOffsetMs
        VirtualCameraBridge.startSession(0, token, 480, 640, VirtualCameraPreset.LOW)
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = currentScope
        currentScope.launch {
            try {
                closing?.join()
                ensureActive()
                val client = XmaxClient(activity.applicationContext,
                    XmaxConfiguration(temporaryKey, XmaxEnvironment.GLOBAL))
                val manager = client.createRealtimeManager(RealtimeConfiguration(model = RealtimeModel.X2_1))
                realtime = manager
                val video = XmaxRealtimeDualVideoView(activity)
                view = video
                container.addView(video, FrameLayout.LayoutParams(-1, -1))
                manager.setStateListener { state ->
                    android.util.Log.i("XMAX_BRIDGE", "state=${state.connectionState.value} reason=${state.reason}")
                    activity.runOnUiThread { if (revision == currentRevision) {
                        onStatus("XMax: ${state.connectionState.value}")
                        if (state.reason is RealtimeReason.Failure) {
                            android.util.Log.e("XMAX_BRIDGE", "engine_failure state=$state")
                            if (usingLowFps && SystemClock.elapsedRealtime() - connectStartedAt < 15_000L) {
                                // 15 fps input rejected early: fall back to the known-working rate next start.
                                activity.getSharedPreferences("xmax_bridge", android.content.Context.MODE_PRIVATE).edit().putBoolean("fps15_failed", true).apply()
                                android.util.Log.w("XMAX_BRIDGE", "fps15_fallback_armed")
                            }
                            onError(IllegalStateException("XMax stopped. Check your connection and start again."))
                        }
                    } }
                }
                var first = true
                var lastFrameAt = 0L
                manager.setRemoteMediaFrameListener { packet ->
                    val frame = (packet.content as? RealtimeMediaFrame.Content.Video)?.frame
                        ?: return@setRemoteMediaFrameListener
                    val now = SystemClock.elapsedRealtime()
                    if (revision != currentRevision || now - lastFrameAt < 66L) return@setRemoteMediaFrameListener
                    lastFrameAt = now
                    pendingFrame.set(frame)
                    drainFrames(activity, currentRevision, token) {
                        if (first && revision == currentRevision) {
                            first = false
                            activity.runOnUiThread { if (revision == currentRevision) onFirstFrame() }
                        }
                    }
                }
                val reference = if (image != null) withContext(Dispatchers.IO) {
                    client.createStorageManager().uploadImageWithSafetyCheck(image, "reference.jpg", "image/jpeg").url
                } else null
                // X2_1 only accepts 1920x1080, 1080x1920, 1408x1408, 1152x1536, 1536x1152 (720p made it stop). 1152x1536 = native portrait 3:4, ~15% fewer pixels than 1080p.
                val prefs = activity.getSharedPreferences("xmax_bridge", android.content.Context.MODE_PRIVATE)
                val lowFps = !prefs.getBoolean("fps15_failed", false)
                connectStartedAt = SystemClock.elapsedRealtime(); usingLowFps = lowFps
                android.util.Log.i("XMAX_BRIDGE", "input_format 1152x1536 fps=" + (if (lowFps) 15 else fps.coerceIn(10, 24)))
                val format = RealtimeVideoFormat(1152, 1536, if (lowFps) 15 else fps.coerceIn(10, 24),
                    minimumBitrate = 300, maximumBitrate = 1500)
                val local = manager.createLocalCameraStream(format,
                    if (backCamera) CameraPosition.BACK else CameraPosition.FRONT, false)
                localTrackRef = local.videoTrack
                if (renderingEnabled) video.localTrack = local.videoTrack
                DecartStreamingService.start(activity.applicationContext)
                val remote = manager.startGeneration(local, RealtimeContext(prompt, reference))
                ensureActive()
                remoteTrackRef = remote.videoTrack
                if (renderingEnabled) video.remoteTrack = remote.videoTrack
            } catch (_: CancellationException) {
                // disconnect owns cleanup; no late error or publication is delivered.
            } catch (e: Exception) {
                android.util.Log.e("XMAX_BRIDGE", "connect_failed", e)
                if (revision == currentRevision) onError(IllegalStateException("XMax could not connect. Check the provider account and try again."))
            }
        }
    }

    private fun drainFrames(activity: Activity, currentRevision: Int, token: String, published: () -> Unit) {
        if (!draining.compareAndSet(false, true)) return
        frameExecutor.execute {
            try {
                while (revision == currentRevision) {
                    val frame = pendingFrame.getAndSet(null) ?: break
                    var width = frame.width
                    var height = frame.height
                    if (width % 2 != 0 || height % 2 != 0) continue
                    val ySize = width * height
                    val chroma = ySize / 4
                    // Reuse buffers + bulk plane copies (old per-byte ByteBuffer.get loop and fresh arrays per frame).
                    val total = ySize + chroma * 2
                    val bytes = convertBuffer?.takeIf { it.size == total } ?: ByteArray(total).also { convertBuffer = it }
                    val uArr = chromaU?.takeIf { it.size == chroma } ?: ByteArray(chroma).also { chromaU = it }
                    val vArr = chromaV?.takeIf { it.size == chroma } ?: ByteArray(chroma).also { chromaV = it }
                    frame.yData.duplicate().get(bytes, 0, ySize)
                    frame.uData.duplicate().get(uArr, 0, chroma)
                    frame.vData.duplicate().get(vArr, 0, chroma)
                    var j = ySize
                    for (i in 0 until chroma) {
                        bytes[j++] = vArr[i]
                        bytes[j++] = uArr[i]
                    }
                    var output = Nv21Converter.rotateNv21(bytes, width, height, frame.rotationDegrees)
                    if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
                        val old = width; width = height; height = old
                    }
                    if (!loggedFrameShape) { loggedFrameShape = true; android.util.Log.i("XMAX_BRIDGE", "remote_frame ${frame.width}x${frame.height} rot=${frame.rotationDegrees}") }
                    // Calls expect a portrait camera: center-crop landscape AI output to 3:4 (9:16 looked too zoomed in).
                    if (width > height) {
                        val cw = (height * 3 / 4) / 2 * 2
                        output = cropNv21(output, width, height, (width - cw) / 2 / 2 * 2, cw)
                        width = cw
                    }
                    val scale = minOf(1.0, 640.0 / maxOf(width, height))
                    val targetWidth = (width * scale).toInt() / 2 * 2
                    val targetHeight = (height * scale).toInt() / 2 * 2
                    if (scale < 1) output = Nv21Converter.resizeNv21(output, width, height, targetWidth, targetHeight)
                    synchronized(publicationLock) {
                    if (revision != currentRevision) return@synchronized
                    VirtualCameraBridge.setActiveSource(activity.applicationContext, VirtualCameraBridge.SOURCE_XMAX)
                    VirtualCameraBridge.publishNv21(0, token, output, targetWidth, targetHeight, System.nanoTime())
                    VirtualCameraSurfaceService.queueFrameProcessing(output, targetWidth, targetHeight)
                    lastOutputAtMs = System.currentTimeMillis()
                    outputSeconds.add((lastOutputAtMs + clockOffsetMs) / 1000L)
                    published()
                    }
                    publishPreview(output, targetWidth, targetHeight)
                }
            } finally {
                draining.set(false)
                if (pendingFrame.get() != null && revision == currentRevision) {
                    drainFrames(activity, currentRevision, token, published)
                }
            }
        }
    }

    @Volatile private var connectStartedAt = 0L
    @Volatile private var usingLowFps = false
    @Volatile private var loggedFrameShape = false
    private var cropBuffer: ByteArray? = null
    /** Horizontal NV21 crop [x0, x0+cw) keeping full height; reuses one buffer. */
    private fun cropNv21(src: ByteArray, w: Int, h: Int, x0: Int, cw: Int): ByteArray {
        val total = cw * h * 3 / 2
        val dst = cropBuffer?.takeIf { it.size == total } ?: ByteArray(total).also { cropBuffer = it }
        for (y in 0 until h) System.arraycopy(src, y * w + x0, dst, y * cw, cw)
        val srcUv = w * h; val dstUv = cw * h
        for (y in 0 until h / 2) System.arraycopy(src, srcUv + y * w + x0, dst, dstUv + y * cw, cw)
        return dst
    }
    @Volatile private var lastPreviewAtMs = 0L
    /** Feeds the floating mini window (StreamPreviewBridge) with ~5 fps snapshots of the AI output. */
    private fun publishPreview(nv21: ByteArray, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAtMs < 1000L || width <= 0 || height <= 0 || !StreamPreviewBridge.hasListeners()) return
        lastPreviewAtMs = now
        runCatching {
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 55, out)
            val bytes = out.toByteArray()
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()?.let { StreamPreviewBridge.publish(it) }
    }

    /** Stop drawing the full-screen local+remote preview while StreamActivity is in the background. */
    fun setRendering(enabled: Boolean) {
        renderingEnabled = enabled
        val v = view ?: return
        runCatching {
            if (enabled) {
                localTrackRef?.let { v.localTrack = it }
                remoteTrackRef?.let { v.remoteTrack = it }
            } else {
                v.localTrack = null
                v.remoteTrack = null
            }
        }
    }

    fun disconnect() {
        synchronized(publicationLock) {
            revision++
            lastOutputAtMs = 0L
            outputSeconds.clear()
            VirtualCameraBridge.stopSession(0, sessionToken)
        }
        scope?.cancel()
        scope = null
        pendingFrame.set(null)
        val previous = realtime
        realtime = null
        if (previous != null) closing = CoroutineScope(Dispatchers.Main).launch { runCatching { previous.close() } }
        localTrackRef = null
        remoteTrackRef = null
        view?.localTrack = null
        view?.remoteTrack = null
        view = null
        host?.let {
            DecartStreamingService.stop(it.applicationContext)
        }
        host = null
    }
}
