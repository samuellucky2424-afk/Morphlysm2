package top.niunaijun.blackboxa.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.example.vcam.Nv21Converter;
import com.example.vcam.DecartFrameStore;
import com.example.vcam.SharedFrameMemory;
import com.example.vcam.SharedFrameStore;
import com.example.vcam.VirtualCameraBridge;
import com.example.vcam.VirtualCameraPreset;

import top.niunaijun.blackboxa.vcam.VirtualCameraSurfaceService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;

public final class DecartRealtimeBridge {
    private static final String TAG_STATE = "DECART_STATE";
    private static final String TAG_ERROR = "DECART_ERROR";

    public interface StatusCallback {
        void onStatus(String status);
    }

    public interface ConnectedCallback {
        void onConnected();
    }

    public interface RemoteReadyCallback {
        void onRemoteReady();
    }

    public interface ErrorCallback {
        void onError(Throwable error);
    }

    public interface SuccessCallback {
        void onSuccess();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Object client;
    private static volatile Object realtime;
    private static volatile Object boundTrack;
    private static volatile Object frameSinkObject;
    private static volatile Object frameSinkTrack;
    private static volatile Object rendererObject;
    private static volatile View rendererView;
    private static volatile Context appContext;
    private static volatile boolean connectedCallbackSent;
    private static volatile boolean remoteCallbackSent;
    private static volatile RemoteReadyCallback pendingRemoteReadyCallback;
    private static final int PREVIEW_SAMPLE_INTERVAL_MS = 333;
    private static final int DIRECT_FRAME_INTERVAL_MS = 66;
    private static final int MINI_PREVIEW_INTERVAL_MS = 333;
    private static final String VIRTUAL_CAMERA_SESSION_TOKEN = "decart-live";
    private static final AtomicBoolean PREVIEW_CAPTURE_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean DIRECT_FRAME_IN_FLIGHT = new AtomicBoolean(false);
    private static final ExecutorService FRAME_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DecartFrameSampler");
        thread.setDaemon(true);
        return thread;
    });
    private static final Runnable PREVIEW_SAMPLER = new Runnable() {
        @Override
        public void run() {
            if (!previewSamplerRunning) {
                return;
            }
            captureRemotePreviewFrame();
            if (previewSamplerRunning) {
                MAIN.postDelayed(this, PREVIEW_SAMPLE_INTERVAL_MS);
            }
        }
    };
    private static volatile boolean previewSamplerRunning;
    private static volatile boolean virtualCameraSessionStarted;
    private static volatile long lastDirectFrameAtMs;
    private static volatile long lastMiniPreviewAtMs;
    private static volatile long publishedFrameCounter;
    private static volatile int configuredVideoBitrate = 850_000;
    private static volatile int configuredVideoFps = 20;
    private DecartRealtimeBridge() {
    }

    public static boolean isConnected() {
        try {
            Object rt = realtime;
            return rt != null && Boolean.TRUE.equals(invokeNoArgs(rt, "isConnected"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void connect(
            Context context,
            FrameLayout outputContainer,
            String apiKey,
            String prompt,
            boolean enhance,
            String referenceImageBase64,
            boolean useBackCamera,
            int targetFps,
            int targetBitrate,
            StatusCallback onStatus,
            ConnectedCallback onConnected,
            RemoteReadyCallback onRemoteReady,
            ErrorCallback onError
    ) {
        disconnect();
        appContext = context.getApplicationContext();
        DecartStreamingService.Companion.start(appContext);
        VirtualCameraBridge.setApplicationContext(appContext);
        VirtualCameraBridge.setActiveSource(appContext, VirtualCameraBridge.SOURCE_DECART);
        connectedCallbackSent = false;
        remoteCallbackSent = false;
        pendingRemoteReadyCallback = onRemoteReady;
        virtualCameraSessionStarted = false;
        publishedFrameCounter = 0;
        configuredVideoFps = Math.max(15, Math.min(30, targetFps));
        configuredVideoBitrate = Math.max(450_000, Math.min(1_200_000, targetBitrate));
        final String referenceImageData = referenceImageBase64 == null ? "" : referenceImageBase64.trim();
        final AtomicBoolean referenceImageApplied = new AtomicBoolean(false);
        MAIN.post(() -> {
            outputContainer.setVisibility(View.VISIBLE);
            Log.d(TAG_STATE, "connecting");
            onStatus.onStatus("Connecting to live video...");
        });

        Thread worker = new Thread(() -> {
            try {
                Class<?> logLevelClass = Class.forName("ai.decart.sdk.LogLevel");
                Object warnLogLevel = enumValue(logLevelClass, "WARN");

                Class<?> configClass = Class.forName("ai.decart.sdk.DecartClientConfig");
                Object config = configClass
                        .getConstructor(String.class, String.class, String.class, logLevelClass)
                        .newInstance(apiKey, "wss://api.decart.ai", "https://api.decart.ai", warnLogLevel);

                Class<?> clientClass = Class.forName("ai.decart.sdk.DecartClient");
                Object decartClient = clientClass.getConstructor(Context.class, configClass)
                        .newInstance(context.getApplicationContext(), config);
                client = decartClient;
                Object rt = invokeNoArgs(decartClient, "getRealtime");
                realtime = rt;
                Log.d(TAG_STATE, "client_created");
                publishPlaceholderFrame("client_created");

                Object model = invokeNoArgs(
                        Class.forName("ai.decart.sdk.RealtimeModels").getField("INSTANCE").get(null),
                        "getLUCY_2_1"
                );

                Class<?> initialPromptClass = Class.forName("ai.decart.sdk.realtime.InitialPrompt");
                Object initialPrompt = initialPromptClass
                        .getConstructor(String.class, boolean.class)
                        .newInstance(prompt, enhance);

                Class<?> facingModeClass = Class.forName("ai.decart.sdk.realtime.FacingMode");
                Object facingMode = enumValue(facingModeClass, useBackCamera ? "BACK" : "FRONT");
                Class<?> mirrorModeClass = Class.forName("ai.decart.sdk.realtime.MirrorMode");
                Object mirrorMode = enumValue(mirrorModeClass, "AUTO");
                Class<?> realtimeModelClass = Class.forName("ai.decart.sdk.RealtimeModel");
                Class<?> resolutionClass = Class.forName("ai.decart.sdk.realtime.Resolution");
                Class<?> realtimeConfigClass = Class.forName("ai.decart.sdk.realtime.RealtimeConfiguration");
                Class<?> streamClass = Class.forName("ai.decart.sdk.realtime.RealtimeMediaStream");
                Class<?> connectOptionsClass = Class.forName("ai.decart.sdk.realtime.ConnectOptions");
                Object realtimeConfiguration = createRealtimeConfiguration(realtimeConfigClass);

                Function1<Object, Unit> remoteStreamCallback = stream -> {
                    MAIN.post(() -> {
                        try {
                            bindRemoteStream(outputContainer, stream);
                            fireConnected(onConnected);
                            if (referenceImageApplied.compareAndSet(false, true)) {
                                applyReferenceImageIfNeeded(referenceImageData, prompt, enhance, onStatus, onError);
                            }
                        } catch (Throwable error) {
                            onError.onError(error);
                        }
                    });
                    return Unit.INSTANCE;
                };

                Constructor<?> optionsCtor = connectOptionsClass.getConstructor(
                        realtimeModelClass,
                        initialPromptClass,
                        String.class,
                        resolutionClass,
                        realtimeConfigClass,
                        boolean.class,
                        boolean.class,
                        facingModeClass,
                        mirrorModeClass,
                        Function1.class
                );
                Object options = optionsCtor.newInstance(
                        model,
                        initialPrompt,
                        null,
                        null,
                        realtimeConfiguration,
                        true,
                        false,
                        facingMode,
                        mirrorMode,
                        remoteStreamCallback
                );

                Method connectMethod = rt.getClass().getMethod(
                        "connect",
                        connectOptionsClass,
                        Continuation.class
                );
                connectMethod.invoke(rt, options, new BridgeContinuation(
                        () -> MAIN.post(() -> {
                            Log.d(TAG_STATE, "connect_ack");
                            onStatus.onStatus("Live session opened.");
                            fireConnected(onConnected);
                            if (referenceImageApplied.compareAndSet(false, true)) {
                                applyReferenceImageIfNeeded(referenceImageData, prompt, enhance, onStatus, onError);
                            }
                        }),
                        error -> MAIN.post(() -> onError.onError(error))
                ));
            } catch (Throwable error) {
                Throwable root = readableSdkError(unwrap(error));
                VirtualCameraBridge.setActiveSource(appContext, VirtualCameraBridge.SOURCE_DECART);
                MAIN.post(() -> { Log.e(TAG_ERROR, root.getMessage() != null ? root.getMessage() : root.toString()); onError.onError(root); });
            }
        }, "DecartRealtimeBridge");
        worker.start();
    }

    public static void setPrompt(
            String prompt,
            boolean enhance,
            SuccessCallback onSuccess,
            ErrorCallback onError
    ) {
        Object rt = realtime;
        if (rt == null || !isConnected()) {
            onError.onError(new IllegalStateException("Live video is not connected yet."));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                Method setPrompt = rt.getClass().getMethod(
                        "setPrompt",
                        String.class,
                        boolean.class,
                        long.class,
                        Continuation.class
                );
                setPrompt.invoke(rt, prompt, enhance, 15000L, new BridgeContinuation(
                        () -> MAIN.post(onSuccess::onSuccess),
                        error -> MAIN.post(() -> onError.onError(error))
                ));
            } catch (Throwable error) {
                Throwable root = readableSdkError(unwrap(error));
                MAIN.post(() -> { Log.e(TAG_ERROR, root.getMessage() != null ? root.getMessage() : root.toString()); onError.onError(root); });
            }
        }, "DecartPromptUpdate");
        worker.start();
    }

    public static void setImage(
            String imageData,
            String prompt,
            boolean enhance,
            SuccessCallback onSuccess,
            ErrorCallback onError
    ) {
        Object rt = realtime;
        if (rt == null || !isConnected()) {
            onError.onError(new IllegalStateException("Live video is not connected yet."));
            return;
        }
        String cleanImageData = imageData == null ? "" : imageData.trim();
        if (cleanImageData.isEmpty()) {
            onError.onError(new IllegalStateException("Select a face image before starting Face Swap."));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                Method setImage = rt.getClass().getMethod(
                        "setImage",
                        String.class,
                        String.class,
                        Boolean.class,
                        long.class,
                        Continuation.class
                );
                setImage.invoke(rt, cleanImageData, prompt, Boolean.valueOf(enhance), 30000L, new BridgeContinuation(
                        () -> MAIN.post(onSuccess::onSuccess),
                        error -> MAIN.post(() -> onError.onError(error))
                ));
            } catch (Throwable error) {
                Throwable root = readableSdkError(unwrap(error));
                MAIN.post(() -> { Log.e(TAG_ERROR, root.getMessage() != null ? root.getMessage() : root.toString()); onError.onError(root); });
            }
        }, "DecartImageUpdate");
        worker.start();
    }

    private static void applyReferenceImageIfNeeded(
            String imageData,
            String prompt,
            boolean enhance,
            StatusCallback onStatus,
            ErrorCallback onError
    ) {
        if (imageData == null || imageData.trim().isEmpty()) {
            return;
        }
        Log.d(TAG_STATE, "set_image_requested bytes=" + imageData.length());
        onStatus.onStatus("Submitting reference image to the live engine...");
        setImage(
                imageData,
                prompt,
                enhance,
                () -> {
                    Log.d(TAG_STATE, "set_image_ack");
                    onStatus.onStatus("Reference image submitted. Waiting for transformed video...");
                },
                error -> {
                    Log.e(TAG_ERROR, "set image failed: " + readableMessage(error));
                    onError.onError(error);
                }
        );
    }
    public static void disconnect() {
        Log.d(TAG_STATE, "disconnect_requested");
        Object oldRealtime = realtime;
        Object oldClient = client;
        realtime = null;
        client = null;
        connectedCallbackSent = false;
        remoteCallbackSent = false;
        pendingRemoteReadyCallback = null;
        previewSamplerRunning = false;
        PREVIEW_CAPTURE_IN_FLIGHT.set(false);
        DIRECT_FRAME_IN_FLIGHT.set(false);
        MAIN.removeCallbacks(PREVIEW_SAMPLER);
        virtualCameraSessionStarted = false;
        VirtualCameraBridge.stopSession(VirtualCameraBridge.DEFAULT_SLOT, VIRTUAL_CAMERA_SESSION_TOKEN);
        SharedFrameMemory.clear(appContext, VirtualCameraBridge.DEFAULT_SLOT);
        SharedFrameStore.clear(appContext, VirtualCameraBridge.DEFAULT_SLOT);
        VirtualCameraBridge.clearDirectDecartFrame();
        DecartFrameStore.clear();
        DecartStreamingService.Companion.stop(appContext);
        VirtualCameraBridge.setActiveSource(appContext, VirtualCameraBridge.SOURCE_DECART);

        MAIN.post(() -> {
            try {
                Object renderer = rendererObject;
                Object sink = frameSinkObject;
                Object track = boundTrack;
                Object sinkTrack = frameSinkTrack;
                if (sink != null && sinkTrack != null) {
                    invokeSingleArg(sinkTrack, "removeRenderer", sink);
                }
                if (renderer != null && track != null) {
                    invokeSingleArg(track, "removeRenderer", renderer);
                }
            } catch (Throwable ignored) {
            }
            frameSinkObject = null;
            frameSinkTrack = null;
            boundTrack = null;
            try {
                if (rendererView != null) {
                    if (rendererView.getParent() instanceof FrameLayout) {
                        ((FrameLayout) rendererView.getParent()).removeView(rendererView);
                    }
                    invokeNoArgs(rendererObject, "release");
                }
            } catch (Throwable ignored) {
            }
            rendererObject = null;
            rendererView = null;
        });

        Thread worker = new Thread(() -> {
            try {
                if (oldRealtime != null) {
                    invokeNoArgs(oldRealtime, "disconnect");
                }
                if (oldClient != null) {
                    invokeNoArgs(oldClient, "release");
                }
            } catch (Throwable ignored) {
            }
        }, "DecartDisconnect");
        worker.start();
    }

    private static void bindRemoteStream(FrameLayout container, Object stream) throws Exception {
        Object track = invokeNoArgs(stream, "getVideoTrack");
        Object room = invokeNoArgs(stream, "getRoom");
        if (track == null || room == null) {
            Log.d(TAG_STATE, "remote_stream_missing_track");
            return;
        }
        Log.d(TAG_STATE, "remote_stream_ready");
        publishPlaceholderFrame("remote_stream_ready");

        Object renderer = rendererObject;
        if (renderer == null) {
            Class<?> rendererClass = Class.forName("io.livekit.android.renderer.TextureViewRenderer");
            renderer = rendererClass.getConstructor(Context.class).newInstance(container.getContext());

            Object lkObjects = invokeNoArgs(room, "getLkObjects");
            Object eglBase = invokeNoArgs(lkObjects, "getEglBase");
            Object eglContext = invokeNoArgs(eglBase, "getEglBaseContext");
            Method init = findMethod(rendererClass, "init", 2);
            init.invoke(renderer, eglContext, null);

            try {
                Class<?> scalingClass = Class.forName("livekit.org.webrtc.RendererCommon$ScalingType");
                Method setScaling = rendererClass.getMethod("setScalingType", scalingClass);
                setScaling.invoke(renderer, enumValue(scalingClass, "SCALE_ASPECT_FILL"));
            } catch (Throwable ignored) {
            }

            rendererObject = renderer;
            rendererView = (View) renderer;
            container.removeAllViews();
            container.addView(
                    rendererView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (boundTrack != track) {
            Object previousTrack = boundTrack;
            if (previousTrack != null) {
                invokeSingleArg(previousTrack, "removeRenderer", renderer);
                detachFrameSink(previousTrack);
            }
            invokeSingleArg(track, "addRenderer", renderer);
            attachFrameSink(track);
            boundTrack = track;
        }
        startFrameBridge();
    }

    private static void attachFrameSink(Object track) {
        try {
            Class<?> sinkClass = Class.forName("livekit.org.webrtc.VideoSink");
            Object sink = Proxy.newProxyInstance(
                    sinkClass.getClassLoader(),
                    new Class<?>[]{sinkClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onFrame".equals(name) && args != null && args.length == 1 && args[0] != null) {
                            handleDirectVideoFrame(args[0]);
                            return null;
                        }
                        if ("toString".equals(name)) {
                            return "SmokescreenDecartFrameSink";
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        return null;
                    }
            );
            invokeSingleArg(track, "addRenderer", sink);
            frameSinkObject = sink;
            frameSinkTrack = track;
            Log.d(TAG_STATE, "direct_frame_sink_attached");
        } catch (Throwable error) {
            Log.e(TAG_ERROR, "direct frame sink attach failed: " + readableMessage(error));
        }
    }

    private static void detachFrameSink(Object track) {
        try {
            Object sink = frameSinkObject;
            if (sink != null && track != null) {
                invokeSingleArg(track, "removeRenderer", sink);
            }
        } catch (Throwable ignored) {
        }
        frameSinkObject = null;
        frameSinkTrack = null;
    }

    private static void handleDirectVideoFrame(Object frame) {
        long now = System.currentTimeMillis();
        if (now - lastDirectFrameAtMs < DIRECT_FRAME_INTERVAL_MS) {
            return;
        }
        if (!DIRECT_FRAME_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        lastDirectFrameAtMs = now;
        boolean retained = false;
        try {
            invokeNoArgs(frame, "retain");
            retained = true;
        } catch (Throwable ignored) {
        }
        final boolean frameRetained = retained;
        FRAME_EXECUTOR.execute(() -> {
            try {
                publishDirectVideoFrame(frame);
            } catch (Throwable error) {
                Log.e(TAG_ERROR, "direct frame bridge failed: " + readableMessage(error));
            } finally {
                if (frameRetained) {
                    try {
                        invokeNoArgs(frame, "release");
                    } catch (Throwable ignored) {
                    }
                }
                DIRECT_FRAME_IN_FLIGHT.set(false);
            }
        });
    }

    private static void publishDirectVideoFrame(Object frame) throws Exception {
        Object buffer = invokeNoArgs(frame, "getBuffer");
        if (buffer == null) {
            return;
        }
        Object i420 = invokeNoArgs(buffer, "toI420");
        if (i420 == null) {
            return;
        }
        boolean releaseI420 = i420 != buffer;
        try {
            int width = Math.max(2, ((Number) invokeNoArgs(i420, "getWidth")).intValue() & ~1);
            int height = Math.max(2, ((Number) invokeNoArgs(i420, "getHeight")).intValue() & ~1);
            byte[] nv21 = i420BufferToNv21(i420, width, height);
            int maxPixels = VirtualCameraPreset.LOW.width * VirtualCameraPreset.LOW.height;
            if (width * height > maxPixels) {
                nv21 = Nv21Converter.resizeNv21(nv21, width, height, VirtualCameraPreset.LOW.width, VirtualCameraPreset.LOW.height);
                width = VirtualCameraPreset.LOW.width;
                height = VirtualCameraPreset.LOW.height;
            }
            publishNv21Frame(nv21, width, height, true);
        } finally {
            if (releaseI420) {
                try {
                    invokeNoArgs(i420, "release");
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] i420BufferToNv21(Object i420Buffer, int width, int height) throws Exception {
        ByteBuffer yBuffer = ((ByteBuffer) invokeNoArgs(i420Buffer, "getDataY")).duplicate();
        ByteBuffer uBuffer = ((ByteBuffer) invokeNoArgs(i420Buffer, "getDataU")).duplicate();
        ByteBuffer vBuffer = ((ByteBuffer) invokeNoArgs(i420Buffer, "getDataV")).duplicate();
        int strideY = ((Number) invokeNoArgs(i420Buffer, "getStrideY")).intValue();
        int strideU = ((Number) invokeNoArgs(i420Buffer, "getStrideU")).intValue();
        int strideV = ((Number) invokeNoArgs(i420Buffer, "getStrideV")).intValue();
        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize + frameSize / 2];
        int yBase = yBuffer.position();
        int uBase = uBuffer.position();
        int vBase = vBuffer.position();
        for (int row = 0; row < height; row++) {
            int src = yBase + row * strideY;
            int dst = row * width;
            for (int col = 0; col < width; col++) {
                nv21[dst + col] = readBuffer(yBuffer, src + col, (byte) 16);
            }
        }
        int output = frameSize;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uRow = uBase + row * strideU;
            int vRow = vBase + row * strideV;
            for (int col = 0; col < chromaWidth && output + 1 < nv21.length; col++) {
                nv21[output++] = readBuffer(vBuffer, vRow + col, (byte) 128);
                nv21[output++] = readBuffer(uBuffer, uRow + col, (byte) 128);
            }
        }
        return nv21;
    }

    private static byte readBuffer(ByteBuffer buffer, int index, byte fallback) {
        try {
            return index >= 0 && index < buffer.limit() ? buffer.get(index) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void startFrameBridge() {
        previewSamplerRunning = true;
        MAIN.removeCallbacks(PREVIEW_SAMPLER);
        MAIN.post(PREVIEW_SAMPLER);
    }

    private static void captureRemotePreviewFrame() {
        View view = rendererView;
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (view instanceof TextureView) {
            captureTexturePreviewFrame((TextureView) view);
            return;
        }
        try {
            int width = VirtualCameraPreset.LOW.width;
            int height = VirtualCameraPreset.LOW.height;
            Bitmap bitmap = captureBitmap(view, width, height);
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            byte[] nv21 = Nv21Converter.bitmapToNv21(bitmap, width, height);
            if (publishNv21Frame(nv21, width, height, false)) {
                StreamPreviewBridge.INSTANCE.publish(bitmap);
            }
        } catch (Throwable error) {
            Log.e(TAG_ERROR, "remote frame bridge failed: " + (error.getMessage() != null ? error.getMessage() : error.toString()));
        }
    }

    private static void captureTexturePreviewFrame(TextureView textureView) {
        if (!textureView.isAvailable() || !PREVIEW_CAPTURE_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        FRAME_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!previewSamplerRunning || !textureView.isAvailable()) {
                    return;
                }
                int width = VirtualCameraPreset.LOW.width;
                int height = VirtualCameraPreset.LOW.height;
                bitmap = textureView.getBitmap(width, height);
                if (bitmap == null || bitmap.isRecycled()) {
                    return;
                }
                byte[] nv21 = Nv21Converter.bitmapToNv21(bitmap, width, height);
                if (!previewSamplerRunning) {
                    return;
                }
                if (publishNv21Frame(nv21, width, height, false)) {
                    StreamPreviewBridge.INSTANCE.publish(bitmap);
                    bitmap = null;
                }
            } catch (Throwable error) {
                Log.e(TAG_ERROR, "remote texture frame bridge failed: " + (error.getMessage() != null ? error.getMessage() : error.toString()));
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                PREVIEW_CAPTURE_IN_FLIGHT.set(false);
            }
        });
    }

    private static Bitmap captureBitmap(View view, int width, int height) {
        if (view instanceof TextureView) {
            return ((TextureView) view).getBitmap(width, height);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float scaleX = width / (float) Math.max(1, view.getWidth());
        float scaleY = height / (float) Math.max(1, view.getHeight());
        canvas.scale(scaleX, scaleY);
        view.draw(canvas);
        return bitmap;
    }

    private static void publishPlaceholderFrame(String reason) {
        try {
            int width = VirtualCameraPreset.LOW.width;
            int height = VirtualCameraPreset.LOW.height;
            VirtualCameraBridge.setActiveSource(appContext, VirtualCameraBridge.SOURCE_DECART);
            byte[] nv21 = Nv21Converter.waitingForDecart(width, height);
            long timestampNs = System.nanoTime();
            if (!virtualCameraSessionStarted) {
                VirtualCameraBridge.startSession(
                        VirtualCameraBridge.DEFAULT_SLOT,
                        VIRTUAL_CAMERA_SESSION_TOKEN,
                        width,
                        height,
                        VirtualCameraPreset.LOW
                );
                virtualCameraSessionStarted = true;
            }
            VirtualCameraBridge.publishNv21(
                    VirtualCameraBridge.DEFAULT_SLOT,
                    VIRTUAL_CAMERA_SESSION_TOKEN,
                    nv21,
                    width,
                    height,
                    timestampNs
            );
            VirtualCameraSurfaceService.queueFrameProcessing(nv21, width, height);
            SharedFrameStore.publish(appContext, VirtualCameraBridge.DEFAULT_SLOT, nv21, width, height, timestampNs);
            maybePublishMiniBitmap(nv21, width, height);
            Log.d(TAG_STATE, "waiting_decart_placeholder:" + reason);
        } catch (Throwable error) {
            Log.e(TAG_ERROR, "placeholder frame failed: " + readableMessage(error));
        }
    }

    private static boolean publishNv21Frame(byte[] nv21, int width, int height, boolean allowMiniPreview) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return false;
        }
        if (realtime == null) {
            return false;
        }
        if (isBlankNv21(nv21, width, height)) {
            Log.d(TAG_STATE, "blank_frame_dropped:" + width + "x" + height);
            return false;
        }
        VirtualCameraBridge.setActiveSource(appContext, VirtualCameraBridge.SOURCE_DECART);
        long timestampNs = System.nanoTime();
        if (!virtualCameraSessionStarted) {
            VirtualCameraBridge.startSession(
                    VirtualCameraBridge.DEFAULT_SLOT,
                    VIRTUAL_CAMERA_SESSION_TOKEN,
                    width,
                    height,
                    VirtualCameraPreset.LOW
            );
            virtualCameraSessionStarted = true;
        }
        VirtualCameraBridge.publishNv21(
                VirtualCameraBridge.DEFAULT_SLOT,
                VIRTUAL_CAMERA_SESSION_TOKEN,
                nv21,
                width,
                height,
                timestampNs
        );
        VirtualCameraSurfaceService.queueFrameProcessing(nv21, width, height);
        SharedFrameStore.publish(appContext, VirtualCameraBridge.DEFAULT_SLOT, nv21, width, height, timestampNs);
        // Also publish directly to the in-memory volatile store so the
        // Nv21SurfaceRenderer (same loop that rendered green-test) can
        // read Decart frames without file-based IPC.
        VirtualCameraBridge.publishDecartDirect(nv21, width, height);
        // Explicitly update the persistent DecartFrameStore as well
        DecartFrameStore.update(nv21, width, height);
        long count = ++publishedFrameCounter;
        if (count == 1) {
            Log.d(TAG_STATE, "first_decart_frame_received:" + width + "x" + height);
        }
        if (count == 1 || count % 30 == 0) {
            Log.d(TAG_STATE, "frame_published:" + width + "x" + height + ":" + count);
        }
        if (allowMiniPreview) {
            maybePublishMiniBitmap(nv21, width, height);
        }
        RemoteReadyCallback callback = pendingRemoteReadyCallback;
        if (callback != null) {
            MAIN.post(() -> fireRemoteReady(callback));
        }
        return true;
    }

    private static boolean isBlankNv21(byte[] nv21, int width, int height) {
        int yPlane = Math.min(nv21.length, Math.max(0, width * height));
        if (yPlane <= 0) {
            return true;
        }
        int step = Math.max(1, yPlane / 2048);
        int samples = 0;
        long sum = 0;
        int min = 255;
        int max = 0;
        for (int i = 0; i < yPlane; i += step) {
            int y = nv21[i] & 0xFF;
            sum += y;
            samples++;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        if (samples == 0) {
            return true;
        }
        int average = (int) (sum / samples);
        return average <= 24 && max <= 32 && (max - min) <= 10;
    }

    private static void maybePublishMiniBitmap(byte[] nv21, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - lastMiniPreviewAtMs < MINI_PREVIEW_INTERVAL_MS) {
            return;
        }
        lastMiniPreviewAtMs = now;
        try {
            StreamPreviewBridge.INSTANCE.publish(Nv21Converter.nv21ToBitmap(nv21, width, height));
        } catch (Throwable error) {
            Log.e(TAG_ERROR, "mini preview bitmap failed: " + readableMessage(error));
        }
    }

    private static Object createRealtimeConfiguration(Class<?> realtimeConfigClass) throws Exception {
        try {
            Class<?> connectionConfigClass = Class.forName("ai.decart.sdk.realtime.RealtimeConfiguration$ConnectionConfig");
            Class<?> mediaConfigClass = Class.forName("ai.decart.sdk.realtime.RealtimeConfiguration$MediaConfig");
            Class<?> videoConfigClass = Class.forName("ai.decart.sdk.realtime.RealtimeConfiguration$VideoConfig");
            Class<?> degradationClass = Class.forName("livekit.org.webrtc.RtpParameters$DegradationPreference");

            Object connectionConfig = connectionConfigClass
                    .getConstructor(long.class)
                    .newInstance(20000L);
            Object degradationPreference = enumValue(degradationClass, "MAINTAIN_FRAMERATE");
            Object videoConfig = videoConfigClass
                    .getConstructor(
                            int.class,
                            int.class,
                            String.class,
                            String.class,
                            boolean.class,
                            degradationClass,
                            java.util.List.class
                    )
                    .newInstance(
                            configuredVideoBitrate,
                            configuredVideoFps,
                            "VP8",
                            "H264",
                            false,
                            degradationPreference,
                            Collections.emptyList()
                    );
            Object mediaConfig = mediaConfigClass
                    .getConstructor(videoConfigClass)
                    .newInstance(videoConfig);
            return realtimeConfigClass
                    .getConstructor(connectionConfigClass, mediaConfigClass)
                    .newInstance(connectionConfig, mediaConfig);
        } catch (Throwable tuningError) {
            Log.d(TAG_STATE, "using_default_realtime_configuration: " + tuningError.getClass().getSimpleName());
            return realtimeConfigClass.getConstructor().newInstance();
        }
    }

    private static void fireConnected(ConnectedCallback callback) {
        if (!connectedCallbackSent) {
            connectedCallbackSent = true;
            callback.onConnected();
        }
    }

    private static void fireRemoteReady(RemoteReadyCallback callback) {
        if (callback != null && !remoteCallbackSent) {
            remoteCallbackSent = true;
            pendingRemoteReadyCallback = null;
            callback.onRemoteReady();
        }
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static void invokeSingleArg(Object target, String name, Object arg) throws Exception {
        Method method = findMethod(target.getClass(), name, 1);
        method.invoke(target, arg);
    }

    private static Method findMethod(Class<?> owner, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException && ((InvocationTargetException) error).getTargetException() != null) {
            return ((InvocationTargetException) error).getTargetException();
        }
        return error;
    }

    private static Throwable readableSdkError(Throwable error) {
        if (error instanceof ClassNotFoundException) {
            return new IllegalStateException(
                    "Native live engine SDK is not packaged in this APK build yet. Backend token works, but the APK cannot open the realtime session."
            );
        }
        return error;
    }

    private static String readableMessage(Throwable error) {
        Throwable root = readableSdkError(unwrap(error));
        String message = root.getMessage();
        return message != null && !message.trim().isEmpty() ? message : root.toString();
    }

    private static final class BridgeContinuation implements Continuation<Object> {
        private final Runnable onSuccess;
        private final ErrorCallback onError;

        private BridgeContinuation(Runnable onSuccess, ErrorCallback onError) {
            this.onSuccess = onSuccess;
            this.onError = onError;
        }

        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object result) {
            try {
                Class.forName("kotlin.ResultKt")
                        .getMethod("throwOnFailure", Object.class)
                        .invoke(null, result);
                onSuccess.run();
            } catch (Throwable error) {
                onError.onError(unwrap(error));
            }
        }
    }
}
