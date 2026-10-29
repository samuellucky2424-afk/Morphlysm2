package top.niunaijun.blackboxa.data

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import com.alivc.rtc.AliRtcEngine
import com.alivc.rtc.AliRtcEngineEventListener
import com.alivc.rtc.AliRtcEngineNotify
import com.example.vcam.Nv21Converter
import com.example.vcam.VirtualCameraBridge
import com.example.vcam.VirtualCameraPreset
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import top.niunaijun.blackboxa.vcam.VirtualCameraSurfaceService
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pro engine: Vidu S2-Editing realtime. Same contract as [XmaxRealtimeBridge]: the server mints the live session
 * (only the short-lived client_secret + RTC join token reach the app), AliRTC captures the camera and publishes it,
 * and the renderer's frames go through the same latest-frame-only NV21 path into the virtual camera.
 * Signaling mirrors morphly's web client (vidu-realtime.ts): conn_init(1) -> ack(2), hangup(5), provider hangup(6).
 */
object ViduRealtimeBridge {
    private const val TAG = "VIDU_BRIDGE"
    private val ALLOWED_HOSTS = setOf("api.vidu.com", "api.vidu.cn")
    private val main = Handler(Looper.getMainLooper())
    private var engine: AliRtcEngine? = null
    private var socket: WebSocket? = null
    private var host: Activity? = null
    private var sessionToken = ""
    private var liveId = ""
    private var connId = ""
    private var seq = 1
    private val publicationLock = Any()
    @Volatile private var revision = 0
    @Volatile var lastOutputAtMs = 0L
        private set
    private var clockOffsetMs = 0L
    private val outputSeconds = ConcurrentSkipListSet<Long>()
    private val pending = AtomicReference<Frame?>()
    private val draining = AtomicBoolean(false)
    private val frameExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ViduFrames") }
    private val http by lazy { OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build() }
    private class Frame(val nv21: ByteArray, val width: Int, val height: Int, val rotation: Int)
    private var spare: ByteArray? = null

    fun completedOutputSeconds(): List<Long> {
        val before = (System.currentTimeMillis() + clockOffsetMs) / 1000L
        val completed = outputSeconds.headSet(before).toList()
        outputSeconds.removeAll(completed.toSet())
        return completed
    }

    fun connect(activity: Activity, container: FrameLayout, vidu: JSONObject, clientSecret: String, backCamera: Boolean,
                token: String, serverClockOffsetMs: Long, onStatus: (String) -> Unit, onFirstFrame: () -> Unit,
                onError: (Throwable) -> Unit) {
        disconnect()
        val rev = revision
        host = activity; sessionToken = token; clockOffsetMs = serverClockOffsetMs
        val fail: (String) -> Unit = { msg ->
            main.post { if (revision == rev) { Log.e(TAG, "fail: $msg"); disconnect(); onError(IllegalStateException(msg)) } }
        }
        val rtc = vidu.optJSONObject("rtc")
        liveId = vidu.optString("liveId"); val renderUid = vidu.optString("renderUid")
        val rtcToken = rtc?.optString("token").orEmpty(); val rtcUser = rtc?.optString("userId").orEmpty()
        val base = runCatching { java.net.URL(vidu.optString("apiBase", "https://api.vidu.com")) }.getOrNull()
        if (liveId.isBlank() || renderUid.isBlank() || rtcToken.isBlank() || rtcUser.isBlank() || clientSecret.isBlank()
            || base == null || base.protocol != "https" || base.host !in ALLOWED_HOSTS) {
            fail("Pro session details were incomplete. Start a new session."); return
        }
        VirtualCameraBridge.startSession(0, token, 720, 960, VirtualCameraPreset.LOW)
        val e = try { AliRtcEngine.getInstance(activity.applicationContext) } catch (t: Throwable) {
            Log.e(TAG, "engine_unavailable", t)
            fail(if (t is UnsatisfiedLinkError) "Pro needs a 64-bit phone. Use Plus on this device." else "Pro video engine could not start."); return
        }
        engine = e
        runCatching { AliRtcEngine.setLogLevel(AliRtcEngine.AliRtcLogLevel.AliRtcLogLevelError) }
        var first = true
        e.setRtcEngineEventListener(object : AliRtcEngineEventListener() {
            override fun onJoinChannelResult(result: Int, channel: String?, elapsed: Int) {
                Log.i(TAG, "join result=$result")
                if (result != 0) fail("Pro could not join its video room (code $result). Start a new session.")
            }
            override fun onJoinChannelResult(result: Int, channel: String?, user: String?, elapsed: Int) =
                onJoinChannelResult(result, channel, elapsed)
            override fun onOccurError(error: Int, message: String?) { Log.w(TAG, "rtc_error code=$error") }
            override fun onConnectionLost() { main.post { if (revision == rev) onStatus("Pro: reconnecting…") } }
            override fun onConnectionRecovery() { main.post { if (revision == rev) onStatus("Pro: generating") } }
        })
        e.setRtcEngineNotify(object : AliRtcEngineNotify() {
            override fun onRemoteUserOnLineNotify(uid: String?, elapsed: Int) {
                if (uid != renderUid) return
                Log.i(TAG, "renderer_online")
                engine?.subscribeRemoteMediaStream(renderUid, AliRtcEngine.AliRtcVideoTrack.AliRtcVideoTrackCamera, true, false)
            }
            override fun onRemoteUserOffLineNotify(uid: String?, reason: AliRtcEngine.AliRtcUserOfflineReason?) {
                if (uid == renderUid) fail("Pro rendering ended. Start a new session.")
            }
            override fun onBye(code: Int) { fail("Pro video session ended. Start a new session.") }
            override fun onAuthInfoExpired() { fail("Pro session expired. Start a new session.") }
        })
        e.registerVideoSampleObserver(object : AliRtcEngine.AliRtcVideoObserver() {
            override fun onGetVideoFormatPreference() = AliRtcEngine.AliRtcVideoFormat.AliRtcVideoFormatI420
            override fun onGetObservedFramePosition() = AliRtcEngine.AliRtcVideoObserPosition.AliRtcPositionPreRender.value
            override fun onLocalVideoSample(t: AliRtcEngine.AliRtcVideoSourceType?, s: AliRtcEngine.AliRtcVideoSample?) = false
            override fun onPreEncodeVideoSample(t: AliRtcEngine.AliRtcVideoSourceType?, s: AliRtcEngine.AliRtcVideoSample?) = false
            override fun onRemoteVideoSample(uid: String?, t: AliRtcEngine.AliRtcVideoSourceType?, s: AliRtcEngine.AliRtcVideoSample?): Boolean {
                if (revision != rev || uid != renderUid || s == null) return false
                val data = s.data ?: run { if (!loggedNoData) { loggedNoData = true; Log.w(TAG, "sample_without_java_data") }; return false }
                val w = s.width and 1.inv(); val h = s.height and 1.inv()
                if (w <= 0 || h <= 0) return false
                pending.set(Frame(i420ToNv21(data, w, h, s.strideY.takeIf { it >= w } ?: w,
                    s.strideU.takeIf { it >= w / 2 } ?: (w / 2), s.strideV.takeIf { it >= w / 2 } ?: (w / 2), s.height), w, h, s.rotate))
                perfIn++
                drain(activity, rev, token) {
                    if (first && revision == rev) { first = false; main.post { if (revision == rev) onFirstFrame() } }
                }
                return false
            }
        })
        e.publishLocalAudioStream(false)
        e.setDefaultSubscribeAllRemoteAudioStreams(false)
        e.setDefaultSubscribeAllRemoteVideoStreams(false)
        runCatching {
            e.setCameraCapturerConfiguration(AliRtcEngine.AliEngineCameraCapturerConfiguration().apply {
                cameraDirection = if (backCamera) AliRtcEngine.AliRtcCameraDirection.CAMERA_REAR else AliRtcEngine.AliRtcCameraDirection.CAMERA_FRONT
                fps = 20
            })
        }
        // On-screen preview of the generated feed is drawn by the SDK (GPU), not by our frame path.
        runCatching {
            val view = e.createRenderSurfaceView(activity)
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            e.setRemoteViewConfig(AliRtcEngine.AliRtcVideoCanvas().apply {
                this.view = view; renderMode = AliRtcEngine.AliRtcRenderMode.AliRtcRenderModeAuto
            }, renderUid, AliRtcEngine.AliRtcVideoTrack.AliRtcVideoTrackCamera)
        }
        DecartStreamingService.start(activity.applicationContext)
        onStatus("Pro: connecting")
        connId = UUID.randomUUID().toString().replace("-", "")
        seq = 1
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val url = "wss://${base.host}/live/ws/live/connect?live_id=${enc(liveId)}&conn_id=${enc(connId)}&client_secret=${enc(clientSecret)}"
        var ready = false
        var attempts = 0
        lateinit var open: () -> Unit
        open = {
            attempts++
            socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (revision != rev) { ws.cancel(); return }
                    Log.i(TAG, "signaling_open"); send(ws, 1, JSONObject().put("conn_init", JSONObject().put("version", 1)))
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (revision != rev) return
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val payload = msg.optJSONObject("payload")
                    when (msg.optInt("type")) {
                        2 -> {
                            val ack = payload?.optJSONObject("conn_init_ack")
                            when {
                                ack?.optBoolean("success") == true -> if (!ready) {
                                    ready = true; Log.i(TAG, "initialized")
                                    main.post {
                                        if (revision != rev) return@post
                                        onStatus("Pro: joining video room")
                                        val chan = rtc!!.optString("channel").takeIf { it.isNotBlank() && it != "null" }
                                        val r = engine?.joinChannel(rtcToken, chan, rtcUser, "") ?: -1
                                        if (r != 0) { fail("Pro could not join its video room (code $r)."); return@post }
                                        engine?.startPreview()
                                        engine?.publishLocalVideoStream(true)
                                    }
                                }
                                ack?.optString("error_code") == "NOT_READY" -> main.postDelayed({
                                    if (revision == rev) send(ws, 1, JSONObject().put("conn_init", JSONObject().put("version", 1)))
                                }, 2000)
                                else -> fail("Pro could not initialize this session (${ack?.optString("error_code")}). Start a new session.")
                            }
                        }
                        6 -> {
                            val reason = payload?.optJSONObject("hangup")?.optString("hangup_reason").orEmpty()
                                .takeIf { Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it) } ?: "unknown"
                            fail("Pro ended this session ($reason). Please try again.")
                        }
                        14 -> if (payload?.optJSONObject("switch_prompt_ack")?.optBoolean("success", true) == false)
                            fail("Pro could not apply the reference photo. Start a new session.")
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = retry(ws, response?.code)
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = retry(ws, code)
                private fun retry(ws: WebSocket, code: Int?) {
                    if (revision != rev || socket !== ws) return
                    if (ready) { fail("Pro signaling disconnected. Start a new session."); return }
                    if (attempts >= 3 || code in setOf(401, 403, 1008, 4001, 4003)) { fail("Pro signaling could not connect. Start a new session."); return }
                    main.postDelayed({ if (revision == rev) open() }, attempts * 1000L)
                }
            })
        }
        open()
        main.postDelayed({ if (revision == rev && first) fail("Pro timed out before receiving generated video. Start a new session.") }, 45_000L)
    }

    private fun send(ws: WebSocket, type: Int, payload: JSONObject) {
        ws.send(JSONObject().put("type", type).put("live_id", liveId).put("conn_id", connId).put("seq_id", seq++)
            .put("payload", payload).toString())
    }

    @Volatile private var loggedNoData = false
    private fun i420ToNv21(src: ByteArray, w: Int, h: Int, sy: Int, su: Int, sv: Int, fullH: Int): ByteArray {
        val total = w * h * 3 / 2
        val out = spare?.takeIf { it.size == total }?.also { spare = null } ?: ByteArray(total)
        val yLut = XmaxRealtimeBridge.Y_LUT; val cLut = XmaxRealtimeBridge.C_LUT
        for (r in 0 until h) { val so = r * sy; val o = r * w; for (x in 0 until w) out[o + x] = yLut[src[so + x].toInt() and 0xff] }
        val uOff = sy * fullH; val vOff = uOff + su * ((fullH + 1) / 2)
        var o = w * h
        for (r in 0 until h / 2) {
            val ur = uOff + r * su; val vr = vOff + r * sv
            if (vr + w / 2 > src.size) break
            for (x in 0 until w / 2) { out[o++] = cLut[src[vr + x].toInt() and 0xff]; out[o++] = cLut[src[ur + x].toInt() and 0xff] }
        }
        return out
    }

    private fun drain(activity: Activity, rev: Int, token: String, published: () -> Unit) {
        if (!draining.compareAndSet(false, true)) return
        frameExecutor.execute {
            try {
                while (revision == rev) {
                    val f = pending.getAndSet(null) ?: break
                    val t0 = System.nanoTime()
                    var w = f.width; var h = f.height
                    var out = if (f.rotation % 360 == 0) f.nv21 else Nv21Converter.rotateNv21(f.nv21, w, h, f.rotation)
                    if (f.rotation == 90 || f.rotation == 270) { val t = w; w = h; h = t }
                    if (w > h) { val cw = (h * 3 / 4) / 2 * 2; out = XmaxRealtimeBridge.cropNv21(out, w, h, (w - cw) / 2 / 2 * 2, cw); w = cw }
                    val scale = minOf(1.0, 960.0 / maxOf(w, h))
                    val tw = (w * scale).toInt() / 2 * 2; val th = (h * scale).toInt() / 2 * 2
                    if (scale < 1) out = XmaxRealtimeBridge.resizeNv21Bilinear(out, w, h, tw, th)
                    val t1 = System.nanoTime()
                    synchronized(publicationLock) {
                        if (revision != rev) return@synchronized
                        VirtualCameraBridge.setActiveSource(activity.applicationContext, VirtualCameraBridge.SOURCE_XMAX)
                        VirtualCameraBridge.publishNv21(0, token, out, tw, th, System.nanoTime())
                        VirtualCameraSurfaceService.queueFrameProcessing(out, tw, th)
                        lastOutputAtMs = System.currentTimeMillis()
                        outputSeconds.add((lastOutputAtMs + clockOffsetMs) / 1000L)
                        published()
                    }
                    XmaxRealtimeBridge.publishPreview(out, tw, th)
                    if (out !== f.nv21) spare = f.nv21
                    perfConvertNs += t1 - t0; perfPublishNs += System.nanoTime() - t1; perfFrames++
                    val now = SystemClock.elapsedRealtime()
                    if (perfFrames >= 60 || now - perfSince > 5000L) {
                        val secs = maxOf(1L, now - perfSince) / 1000.0
                        Log.i("VIDU_PERF", "out_fps=" + "%.1f".format(perfFrames / secs) + " in_fps=" + "%.1f".format(perfIn / secs) +
                            " convertMs=" + "%.1f".format(perfConvertNs / 1e6 / maxOf(1, perfFrames)) +
                            " publishMs=" + "%.1f".format(perfPublishNs / 1e6 / maxOf(1, perfFrames)) + " src=${f.width}x${f.height}")
                        perfFrames = 0; perfIn = 0; perfConvertNs = 0; perfPublishNs = 0; perfSince = now
                    }
                }
            } finally {
                draining.set(false)
                if (pending.get() != null && revision == rev) drain(activity, rev, token, published)
            }
        }
    }
    @Volatile private var perfIn = 0
    private var perfFrames = 0
    private var perfConvertNs = 0L
    private var perfPublishNs = 0L
    private var perfSince = 0L

    /** Hang up first (stops Vidu billing on their side), then tear down RTC off the main thread. */
    fun disconnect() {
        synchronized(publicationLock) {
            revision++
            lastOutputAtMs = 0L
            outputSeconds.clear()
            if (sessionToken.isNotEmpty()) VirtualCameraBridge.stopSession(0, sessionToken)
        }
        pending.set(null)
        socket?.let { ws ->
            runCatching { send(ws, 5, JSONObject().put("hangup", JSONObject().put("hangup_reason", "user_hangup"))) }
            runCatching { ws.close(1000, "user_hangup") }
        }
        socket = null
        val e = engine
        engine = null
        if (e != null) Thread({
            runCatching { e.unRegisterVideoSampleObserver() }
            runCatching { e.leaveChannel() }
            runCatching { e.destroy() }
        }, "ViduTeardown").start()
        host?.let { DecartStreamingService.stop(it.applicationContext) }
        host = null
    }
}
