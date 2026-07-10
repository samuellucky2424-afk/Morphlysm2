package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageWriter;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

public final class Nv21SurfaceRenderer {
    private static final String TAG = "VCAM_RENDER";
    private static final int MAX_RENDER_WIDTH = 320;
    private static final int FRAME_DELAY_MS = 33;
    private static final int FORMAT_UNKNOWN = Integer.MIN_VALUE;
    private static final long SURFACE_INVALID_MAX_WAIT_MS = 10_000;
    private static final int IMAGE_WRITER_RETRY_INTERVAL = 60;

    private final Surface surface;
    private final String label;
    private final int requestedWidth;
    private final int requestedHeight;
    private final int targetWidth;
    private final int targetHeight;
    private final int surfaceFormat;
    private volatile boolean running;
    private Thread thread;
    private ImageWriter imageWriter;
    private boolean imageWriterFailed;
    private int imageWriterFailedCount;
    private int imageWriterFrameCount;
    private int canvasFrameCount;
    private long lastRenderedCounter;

    private Nv21SurfaceRenderer(Surface surface, String label, int requestedWidth, int requestedHeight, int surfaceFormat) {
        this.surface = surface;
        this.label = label;
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
    }

    public static Nv21SurfaceRenderer start(Surface surface, String label, int requestedWidth, int requestedHeight) {
        return start(surface, label, requestedWidth, requestedHeight, FORMAT_UNKNOWN);
    }

    public static Nv21SurfaceRenderer start(Surface surface, String label, int requestedWidth, int requestedHeight, int surfaceFormat) {
        if (surface == null || !surface.isValid()) {
            return null;
        }
        Nv21SurfaceRenderer renderer = new Nv21SurfaceRenderer(surface, label, requestedWidth, requestedHeight, surfaceFormat);
        renderer.start();
        return renderer;
    }

    public void stop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
        closeImageWriter();
    }

    public boolean matches(Surface surface, String label, int requestedWidth, int requestedHeight) {
        return running
                && this.surface == surface
                && this.surface.isValid()
                && this.label.equals(label)
                && this.requestedWidth == Math.max(2, requestedWidth)
                && this.requestedHeight == Math.max(2, requestedHeight);
    }

    private void start() {
        running = true;
        Log.i(TAG, "renderer_start label=" + label
                + " requested=" + requestedWidth + "x" + requestedHeight
                + " target=" + targetWidth + "x" + targetHeight
                + " format=" + surfaceFormat);
        thread = new Thread(this::renderLoop, "VCamSurface-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    private void renderLoop() {
        Log.i(TAG, "render_loop_started thread=" + Thread.currentThread().getName() + " label=" + label);
        long surfaceInvalidSince = 0;
        while (running) {
            try {
                if (!surface.isValid()) {
                    // Surface temporarily invalid — wait and retry instead of killing the loop
                    long now = System.currentTimeMillis();
                    if (surfaceInvalidSince == 0) {
                        surfaceInvalidSince = now;
                        Log.w(TAG, "surface_temporarily_invalid label=" + label + " waiting...");
                    }
                    if (now - surfaceInvalidSince > SURFACE_INVALID_MAX_WAIT_MS) {
                        Log.w(TAG, "surface_invalid_timeout label=" + label
                                + " waited=" + (now - surfaceInvalidSince) + "ms — stopping");
                        running = false;
                        break;
                    }
                    Thread.sleep(100);
                    continue;
                }
                // Surface is valid again — reset the invalid timer
                if (surfaceInvalidSince != 0) {
                    Log.i(TAG, "surface_recovered label=" + label
                            + " downtime=" + (System.currentTimeMillis() - surfaceInvalidSince) + "ms");
                    surfaceInvalidSince = 0;
                    // Reset ImageWriter so it can be re-created for the recovered surface
                    closeImageWriter();
                    imageWriterFailed = false;
                    imageWriterFailedCount = 0;
                }
                drawLatestFrame();
                Thread.sleep(FRAME_DELAY_MS);
            } catch (InterruptedException ignored) {
                running = false;
                Log.i(TAG, "render_loop_interrupted label=" + label);
            } catch (Throwable error) {
                Log.e(TAG, "render_loop_error label=" + label + " error=" + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
                try {
                    Thread.sleep(FRAME_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    running = false;
                }
            }
        }
        Log.i(TAG, "render_loop_stopped label=" + label);
    }

    private void drawLatestFrame() {
        VirtualFrame frame = VirtualCameraBridge.latestOrPlaceholder(
                VirtualCameraBridge.DEFAULT_SLOT,
                targetWidth,
                targetHeight
        );
        PreparedFrame prepared = prepareFrameForTarget(frame.nv21, frame.width, frame.height, targetWidth, targetHeight);
        byte[] nv21 = prepared.nv21;
        int width = prepared.width;
        int height = prepared.height;

        long totalFrameCount = canvasFrameCount + imageWriterFrameCount;
        boolean isNewFrame = frame.counter != lastRenderedCounter;

        // Log every rendered frame for the first 5, then every 30th
        if (totalFrameCount <= 5 || totalFrameCount % 30 == 0 || prepared.rotationDegrees != 0) {
            Log.d(TAG, "VirtualCamera: rendered frameCounter=" + frame.counter
                    + " to WhatsApp surface label=" + label
                    + " totalRendered=" + totalFrameCount
                    + " isNew=" + isNewFrame
                    + " decart=" + frame.width + "x" + frame.height
                    + " requested=" + requestedWidth + "x" + requestedHeight
                    + " target=" + targetWidth + "x" + targetHeight
                    + " prepared=" + width + "x" + height
                    + " rotation=" + prepared.rotationDegrees
                    + " storeCounter=" + DecartFrameStore.frameCounter
                    + " surfaceValid=" + surface.isValid()
                    + " format=" + surfaceFormat);
        }

        lastRenderedCounter = frame.counter;
        boolean mustUseImageWriter = isYuvSurface();
        boolean shouldProbeImageWriter = mustUseImageWriter || surfaceFormat == FORMAT_UNKNOWN;
        if (shouldProbeImageWriter && writeLatestImage(nv21, width, height)) {
            return;
        }
        if (mustUseImageWriter) {
            if (totalFrameCount % 30 == 0) {
                Log.w(TAG, "mustUseImageWriter_but_failed label=" + label
                        + " retryIn=" + (IMAGE_WRITER_RETRY_INTERVAL - imageWriterFailedCount) + " frames");
            }
            return;
        }
        Bitmap bitmap = Nv21Converter.nv21ToBitmap(nv21, width, height);
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                if (totalFrameCount % 30 == 0) {
                    Log.w(TAG, "canvas_null label=" + label);
                }
                return;
            }
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
            canvasFrameCount++;
            if (canvasFrameCount == 1) {
                Log.i(TAG, "first_canvas_frame label=" + label
                        + " canvas=" + canvas.getWidth() + "x" + canvas.getHeight()
                        + " frame=" + width + "x" + height);
            }
        } finally {
            if (canvas != null) {
                surface.unlockCanvasAndPost(canvas);
            }
            bitmap.recycle();
        }
    }

    private PreparedFrame prepareFrameForTarget(byte[] nv21, int width, int height, int outputWidth, int outputHeight) {
        int safeOutputWidth = Math.max(2, outputWidth & ~1);
        int safeOutputHeight = Math.max(2, outputHeight & ~1);
        int safeWidth = Math.max(2, width & ~1);
        int safeHeight = Math.max(2, height & ~1);
        byte[] source = nv21;
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
        return new PreparedFrame(source, safeWidth, safeHeight, rotationDegrees);
    }

    private int rotationForTarget(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
        boolean sourcePortrait = sourceHeight > sourceWidth;
        boolean outputPortrait = outputHeight > outputWidth;
        if (sourcePortrait == outputPortrait) {
            return 0;
        }
        // WhatsApp video-call buffers need the opposite quarter-turn from the
        // camera metadata value; otherwise portrait output appears upside down.
        return sourcePortrait ? 90 : 270;
    }

    private static final class PreparedFrame {
        final byte[] nv21;
        final int width;
        final int height;
        final int rotationDegrees;

        PreparedFrame(byte[] nv21, int width, int height, int rotationDegrees) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
        }
    }
    private boolean isYuvSurface() {
        return surfaceFormat == ImageFormat.YUV_420_888
                || surfaceFormat == ImageFormat.NV21
                || surfaceFormat == ImageFormat.YV12;
    }

    private boolean writeLatestImage(byte[] nv21, int width, int height) {
        if (imageWriterFailed) {
            // Periodically retry ImageWriter in case Surface was reconfigured
            imageWriterFailedCount++;
            if (imageWriterFailedCount >= IMAGE_WRITER_RETRY_INTERVAL) {
                Log.i(TAG, "image_writer_retry label=" + label);
                imageWriterFailed = false;
                imageWriterFailedCount = 0;
                closeImageWriter();
            } else {
                return false;
            }
        }
        Image image = null;
        try {
            ImageWriter writer = imageWriter;
            if (writer == null) {
                writer = ImageWriter.newInstance(surface, 2);
                imageWriter = writer;
                Log.i(TAG, "image_writer_created label=" + label + " format=" + surfaceFormat);
            }
            image = writer.dequeueInputImage();
            int imageWidth = Math.max(2, image.getWidth() & ~1);
            int imageHeight = Math.max(2, image.getHeight() & ~1);
            PreparedFrame imageFrame = prepareFrameForTarget(nv21, width, height, imageWidth, imageHeight);
            if (imageWriterFrameCount < 5 || imageWriterFrameCount % 30 == 0 || imageFrame.rotationDegrees != 0) {
                Log.d(TAG, "image_writer_mapping label=" + label
                        + " input=" + width + "x" + height
                        + " image=" + imageWidth + "x" + imageHeight
                        + " output=" + imageFrame.width + "x" + imageFrame.height
                        + " rotation=" + imageFrame.rotationDegrees);
            }
            writeNv21ToImage(imageFrame.nv21, imageFrame.width, imageFrame.height, image);
            image.setTimestamp(System.nanoTime());
            writer.queueInputImage(image);
            image = null;
            imageWriterFrameCount++;
            if (imageWriterFrameCount == 1) {
                Log.i(TAG, "first_image_writer_frame label=" + label
                        + " image=" + imageWidth + "x" + imageHeight
                        + " source=" + width + "x" + height
                        + " format=" + surfaceFormat);
            }
            return true;
        } catch (Throwable error) {
            imageWriterFailed = true;
            imageWriterFailedCount = 0;
            Log.w(TAG, "image_writer_failed label=" + label
                    + " format=" + surfaceFormat
                    + " error=" + error.getClass().getSimpleName()
                    + ": " + (error.getMessage() == null ? "" : error.getMessage()));
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
        int frameSize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int srcRow = frameSize + row * width;
            int dstRow = row * rowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int src = srcRow + col * 2 + (uPlane ? 1 : 0);
                int dst = dstRow + col * pixelStride;
                putSafe(buffer, dst, limit, src >= 0 && src < nv21.length ? nv21[src] : (byte) 128);
            }
        }
    }

    private static void putSafe(ByteBuffer buffer, int index, int limit, byte value) {
        if (index >= 0 && index < limit) {
            buffer.put(index, value);
        }
    }

    private void closeImageWriter() {
        ImageWriter writer = imageWriter;
        imageWriter = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
