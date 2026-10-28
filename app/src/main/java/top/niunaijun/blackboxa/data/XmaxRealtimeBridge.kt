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
        VirtualCameraBridge.startSession(0, token, 640, 360, VirtualCameraPreset.LOW)
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
                    activity.runOnUiThread { if (revision == currentRevision) {
                        onStatus("XMax: ${state.connectionState.value}")
                        if (state.reason is RealtimeReason.Failure) {
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
                val format = RealtimeVideoFormat(1920, 1080, fps.coerceIn(10, 24),
                    minimumBitrate = 300, maximumBitrate = 1500)
                val local = manager.createLocalCameraStream(format,
                    if (backCamera) CameraPosition.BACK else CameraPosition.FRONT, false)
                video.localTrack = local.videoTrack
                DecartStreamingService.start(activity.applicationContext)
                val remote = manager.startGeneration(local, RealtimeContext(prompt, reference))
                ensureActive()
                video.remoteTrack = remote.videoTrack
            } catch (_: CancellationException) {
                // disconnect owns cleanup; no late error or publication is delivered.
            } catch (_: Exception) {
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
                    val bytes = ByteArray(ySize + chroma * 2)
                    frame.yData.get(bytes, 0, ySize)
                    val u = frame.uData
                    val v = frame.vData
                    for (i in 0 until chroma) {
                        bytes[ySize + 2 * i] = v.get(i)
                        bytes[ySize + 2 * i + 1] = u.get(i)
                    }
                    var output = Nv21Converter.rotateNv21(bytes, width, height, frame.rotationDegrees)
                    if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
                        val old = width; width = height; height = old
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

    @Volatile private var lastPreviewAtMs = 0L
    /** Feeds the floating mini window (StreamPreviewBridge) with ~5 fps snapshots of the AI output. */
    private fun publishPreview(nv21: ByteArray, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAtMs < 200L || width <= 0 || height <= 0) return
        lastPreviewAtMs = now
        runCatching {
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 70, out)
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { StreamPreviewBridge.publish(it) }
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
        view?.localTrack = null
        view?.remoteTrack = null
        view = null
        host?.let {
            DecartStreamingService.stop(it.applicationContext)
        }
        host = null
    }
}
