package top.niunaijun.blackboxa.vcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.example.vcam.Nv21Converter;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VirtualCameraSurfaceWorker {
    private static final String TAG = "VCAM_SURFACE_WORKER";
    private static final int MAX_RENDER_WIDTH = 320;
    private static final int FORMAT_UNKNOWN = Integer.MIN_VALUE;

    private final Surface surface;
    private final String label;
    private final int requestedWidth;
    private final int requestedHeight;
    private final int targetWidth;
    private final int targetHeight;
    private final int surfaceFormat;
    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private final Object frameLock = new Object();

    private volatile boolean released;
    private byte[] pendingFrameBuffer;
    private byte[] activeFrameBuffer;
    private boolean hasPendingFrame;
    private long pendingFrameCounter;
    private long droppedFrameCounter;
    private int pendingWidth;
    private int pendingHeight;
    private int pendingLength;
    private ImageWriter imageWriter;
    private boolean imageWriterFailed;
    private int imageWriterFailedCount;
    private int renderedCount;

    public VirtualCameraSurfaceWorker(Surface surface, String label, int requestedWidth, int requestedHeight, int surfaceFormat) {
        this.surface = surface;
        this.label = label == null ? "unknown" : label;
        this.requestedWidth = Math.max(2, requestedWidth);
        this.requestedHeight = Math.max(2, requestedHeight);
        this.surfaceFormat = surfaceFormat;
        int safeWidth = Math.max(2, requestedWidth);
        int safeHeight = Math.max(2, requestedHeight);
        int renderWidth = Math.min(MAX_RENDER_WIDTH, safeWidth);
        if ((renderWidth & 1) == 1) {
            renderWidth--;
        }
        int renderHeight = Math.max(2, Math.round(safeHeight * (renderWidth / (float) safeWidth)));
        if ((renderHeight & 1) == 1) {
            renderHeight--;
        }
        this.targetWidth = Math.max(2, renderWidth);
        this.targetHeight = Math.max(2, renderHeight);
        this.workerThread = new HandlerThread("VirtualCameraSurface-" + this.label, Process.THREAD_PRIORITY_DISPLAY);
        this.workerThread.start();
        this.workerHandler = new Handler(workerThread.getLooper());
        Log.i(TAG, "worker_start label=" + this.label
                + " requested=" + this.requestedWidth + "x" + this.requestedHeight
                + " target=" + targetWidth + "x" + targetHeight
                + " format=" + surfaceFormat);
    }

    public void queueFrameProcessing(byte[] rawFrame, int width, int height) {
        if (released || rawFrame == null || width <= 0 || height <= 0) {
            return;
        }
        int length = Math.min(rawFrame.length, width * height * 3 / 2);
        if (length <= 0) {
            return;
        }
        synchronized (frameLock) {
            if (pendingFrameBuffer == null || pendingFrameBuffer.length < length) {
                pendingFrameBuffer = new byte[length];
            }
            if (hasPendingFrame) {
                droppedFrameCounter++;
            }
            System.arraycopy(rawFrame, 0, pendingFrameBuffer, 0, length);
            pendingWidth = width;
            pendingHeight = height;
            pendingLength = length;
            pendingFrameCounter++;
            hasPendingFrame = true;
        }
        if (drainScheduled.compareAndSet(false, true)) {
            workerHandler.post(this::drainLatestFrameSilently);
        }
    }

    public void release() {
        released = true;
        synchronized (frameLock) {
            pendingFrameBuffer = null;
            activeFrameBuffer = null;
            hasPendingFrame = false;
        }
        workerHandler.post(() -> {
            closeImageWriter();
            try {
                if (surface != null) {
                    surface.release();
                }
            } catch (Throwable ignored) {
            }
        });
        workerThread.quitSafely();
    }

    private void drainLatestFrameSilently() {
        try {
            drainLatestFrame();
        } catch (Throwable ignored) {
        } finally {
            drainScheduled.set(false);
            boolean hasMore;
            synchronized (frameLock) {
                hasMore = hasPendingFrame;
            }
            if (!released && hasMore && drainScheduled.compareAndSet(false, true)) {
                workerHandler.post(this::drainLatestFrameSilently);
            }
        }
    }

    private void drainLatestFrame() {
        if (released || surface == null || !surface.isValid()) {
            return;
        }
        byte[] source;
        int width;
        int height;
        int length;
        long frameCounter;
        long droppedCounter;
        synchronized (frameLock) {
            if (!hasPendingFrame || pendingFrameBuffer == null) {
                return;
            }
            source = pendingFrameBuffer;
            width = pendingWidth;
            height = pendingHeight;
            length = pendingLength;
            frameCounter = pendingFrameCounter;
            droppedCounter = droppedFrameCounter;
            pendingFrameBuffer = activeFrameBuffer;
            activeFrameBuffer = source;
            hasPendingFrame = false;
        }
        if (source == null || width <= 0 || height <= 0) {
            return;
        }
        PreparedFrame prepared = prepareFrameForTarget(source, length, width, height, targetWidth, targetHeight);
        if ((isYuvSurface() || surfaceFormat == FORMAT_UNKNOWN) && writeImage(prepared.nv21, prepared.width, prepared.height)) {
            logDroppedFrames(frameCounter, droppedCounter);
            return;
        }
        if (isYuvSurface()) {
            return;
        }
        drawCanvas(prepared.nv21, prepared.width, prepared.height);
        logDroppedFrames(frameCounter, droppedCounter);
    }

    private void drawCanvas(byte[] nv21, int width, int height) {
        Bitmap bitmap = null;
        Canvas canvas = null;
        try {
            bitmap = Nv21Converter.nv21ToBitmap(nv21, width, height);
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
            renderedCount++;
            if (renderedCount == 1 || renderedCount % 30 == 0) {
                Log.d(TAG, "worker_canvas_frame label=" + label
                        + " count=" + renderedCount
                        + " frame=" + width + "x" + height
                        + " canvas=" + canvas.getWidth() + "x" + canvas.getHeight());
            }
        } catch (Throwable ignored) {
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private boolean writeImage(byte[] nv21, int width, int height) {
        if (imageWriterFailed) {
            imageWriterFailedCount++;
            if (imageWriterFailedCount < 60) {
                return false;
            }
            imageWriterFailed = false;
            imageWriterFailedCount = 0;
            closeImageWriter();
        }
        Image image = null;
        try {
            ImageWriter writer = imageWriter;
            if (writer == null) {
                writer = ImageWriter.newInstance(surface, 2);
                imageWriter = writer;
            }
            image = writer.dequeueInputImage();
            int imageWidth = Math.max(2, image.getWidth() & ~1);
            int imageHeight = Math.max(2, image.getHeight() & ~1);
            PreparedFrame prepared = prepareFrameForTarget(nv21, width, height, imageWidth, imageHeight);
            writeNv21ToImage(prepared.nv21, prepared.width, prepared.height, image);
            image.setTimestamp(System.nanoTime());
            writer.queueInputImage(image);
            image = null;
            renderedCount++;
            if (renderedCount == 1 || renderedCount % 30 == 0) {
                Log.d(TAG, "worker_image_frame label=" + label
                        + " count=" + renderedCount
                        + " image=" + imageWidth + "x" + imageHeight);
            }
            return true;
        } catch (Throwable ignored) {
            imageWriterFailed = true;
            imageWriterFailedCount = 0;
            closeImageWriter();
            return false;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private PreparedFrame prepareFrameForTarget(byte[] nv21, int inputLength, int width, int height, int outputWidth, int outputHeight) {
        int safeOutputWidth = Math.max(2, outputWidth & ~1);
        int safeOutputHeight = Math.max(2, outputHeight & ~1);
        int safeWidth = Math.max(2, width & ~1);
        int safeHeight = Math.max(2, height & ~1);
        int safeLength = Math.min(inputLength, Math.min(nv21.length, safeWidth * safeHeight * 3 / 2));
        byte[] source = nv21;
        if (safeLength < Math.min(nv21.length, safeWidth * safeHeight * 3 / 2)) {
            source = new byte[safeLength];
            System.arraycopy(nv21, 0, source, 0, safeLength);
        }
        int rotationDegrees = rotationForTarget(safeWidth, safeHeight, safeOutputWidth, safeOutputHeight);
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            source = Nv21Converter.rotateNv21(source, safeWidth, safeHeight, rotationDegrees);
            int rotatedWidth = safeHeight;
            safeHeight = safeWidth;
            safeWidth = rotatedWidth;
        }
        if (safeWidth != safeOutputWidth || safeHeight != safeOutputHeight) {
            source = Nv21Converter.resizeNv21(source, safeWidth, safeHeight, safeOutputWidth, safeOutputHeight);
            safeWidth = safeOutputWidth;
            safeHeight = safeOutputHeight;
        }
        return new PreparedFrame(source, safeWidth, safeHeight);
    }

    private PreparedFrame prepareFrameForTarget(byte[] nv21, int width, int height, int outputWidth, int outputHeight) {
        return prepareFrameForTarget(nv21, nv21 == null ? 0 : nv21.length, width, height, outputWidth, outputHeight);
    }

    private void logDroppedFrames(long frameCounter, long droppedCounter) {
        if (droppedCounter > 0 && (frameCounter <= 5 || frameCounter % 60 == 0)) {
            Log.d(TAG, "worker_backpressure_drop label=" + label
                    + " dropped=" + droppedCounter
                    + " latestCounter=" + frameCounter);
        }
    }

    private int rotationForTarget(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
        boolean sourcePortrait = sourceHeight > sourceWidth;
        boolean outputPortrait = outputHeight > outputWidth;
        return sourcePortrait == outputPortrait ? 0 : (sourcePortrait ? 90 : 270);
    }

    private boolean isYuvSurface() {
        return surfaceFormat == ImageFormat.YUV_420_888
                || surfaceFormat == ImageFormat.NV21
                || surfaceFormat == ImageFormat.YV12;
    }

    private void closeImageWriter() {
        try {
            if (imageWriter != null) {
                imageWriter.close();
            }
        } catch (Throwable ignored) {
        }
        imageWriter = null;
    }

    private static void writeNv21ToImage(byte[] nv21, int width, int height, Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IllegalArgumentException("Target image does not expose YUV planes");
        }
        writePlaneY(nv21, width, height, planes[0]);
        writePlaneChroma(nv21, width, height, planes[1], true);
        writePlaneChroma(nv21, width, height, planes[2], false);
    }

    private static void writePlaneY(byte[] nv21, int width, int height, Image.Plane plane) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = Math.max(1, plane.getPixelStride());
        int limit = buffer.limit();
        for (int row = 0; row < height; row++) {
            int src = row * width;
            int dst = row * rowStride;
            for (int col = 0; col < width; col++) {
                putSafe(buffer, dst + col * pixelStride, limit, nv21[src + col]);
            }
        }
    }

    private static void writePlaneChroma(byte[] nv21, int width, int height, Image.Plane plane, boolean uPlane) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = Math.max(1, plane.getPixelStride());
        int limit = buffer.limit();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int uvStart = width * height;
        for (int row = 0; row < chromaHeight; row++) {
            int srcRow = uvStart + row * width;
            int dstRow = row * rowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int nvIndex = srcRow + col * 2 + (uPlane ? 1 : 0);
                putSafe(buffer, dstRow + col * pixelStride, limit, nv21[nvIndex]);
            }
        }
    }

    private static void putSafe(ByteBuffer buffer, int index, int limit, byte value) {
        if (index >= 0 && index < limit) {
            buffer.put(index, value);
        }
    }

    private static final class PreparedFrame {
        final byte[] nv21;
        final int width;
        final int height;

        PreparedFrame(byte[] nv21, int width, int height) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
        }
    }
}
