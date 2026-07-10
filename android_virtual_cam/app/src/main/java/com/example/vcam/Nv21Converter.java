package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.Color;

public final class Nv21Converter {
    private Nv21Converter() {
    }

    public static byte[] placeholder(int width, int height) {
        int safeWidth = Math.max(2, width);
        int safeHeight = Math.max(2, height);
        int frameSize = safeWidth * safeHeight;
        byte[] nv21 = new byte[frameSize + frameSize / 2];
        for (int i = 0; i < frameSize; i++) {
            nv21[i] = (byte) 145;
        }
        for (int i = frameSize; i + 1 < nv21.length; i += 2) {
            nv21[i] = (byte) 34;
            nv21[i + 1] = (byte) 54;
        }
        return nv21;
    }

    public static byte[] greenTest(int width, int height) {
        return placeholder(width, height);
    }

    public static byte[] waitingForDecart(int width, int height) {
        int safeWidth = Math.max(2, width);
        int safeHeight = Math.max(2, height);
        int frameSize = safeWidth * safeHeight;
        byte[] nv21 = new byte[frameSize + frameSize / 2];
        for (int y = 0; y < safeHeight; y++) {
            for (int x = 0; x < safeWidth; x++) {
                int pulse = ((x + y) / 48) % 2 == 0 ? 8 : 0;
                nv21[y * safeWidth + x] = (byte) (44 + pulse);
            }
        }
        for (int i = frameSize; i + 1 < nv21.length; i += 2) {
            nv21[i] = (byte) 156;
            nv21[i + 1] = (byte) 110;
        }
        return nv21;
    }

    public static byte[] movingColorTest(int width, int height, long timestampMs) {
        int safeWidth = Math.max(2, width);
        int safeHeight = Math.max(2, height);
        int frameSize = safeWidth * safeHeight;
        byte[] nv21 = new byte[frameSize + frameSize / 2];
        int phase = (int) ((timestampMs / 45L) % Math.max(1, safeWidth));
        int band = Math.max(24, safeWidth / 6);
        for (int y = 0; y < safeHeight; y++) {
            for (int x = 0; x < safeWidth; x++) {
                int distance = Math.abs(((x - phase + safeWidth) % safeWidth) - safeWidth / 2);
                boolean brightBand = distance < band || ((y / 34) % 2 == 0 && Math.abs(x - phase) < band);
                int base = brightBand ? 210 : 72 + ((x + y + phase) % 60);
                nv21[y * safeWidth + x] = (byte) clamp(base);
            }
        }
        int chromaPhase = (int) ((timestampMs / 160L) % 4L);
        for (int y = 0; y < safeHeight / 2; y++) {
            for (int x = 0; x < safeWidth / 2; x++) {
                int index = frameSize + y * safeWidth + x * 2;
                int block = ((x / 12) + (y / 12) + chromaPhase) % 4;
                int v;
                int u;
                if (block == 0) {
                    v = 240;
                    u = 90;
                } else if (block == 1) {
                    v = 40;
                    u = 210;
                } else if (block == 2) {
                    v = 180;
                    u = 50;
                } else {
                    v = 90;
                    u = 240;
                }
                if (index + 1 < nv21.length) {
                    nv21[index] = (byte) v;
                    nv21[index + 1] = (byte) u;
                }
            }
        }
        return nv21;
    }
    public static byte[] bitmapToNv21(Bitmap source, int width, int height) {
        Bitmap scaled = centerCrop(source, width, height);
        int[] argb = new int[width * height];
        scaled.getPixels(argb, 0, width, 0, 0, width, height);
        if (scaled != source) {
            scaled.recycle();
        }
        return argbToNv21(argb, width, height);
    }

    public static byte[] rgbaToNv21(byte[] rgba, int width, int height) {
        int[] argb = new int[width * height];
        int pixels = Math.min(argb.length, rgba.length / 4);
        for (int i = 0; i < pixels; i++) {
            int base = i * 4;
            int r = rgba[base] & 0xff;
            int g = rgba[base + 1] & 0xff;
            int b = rgba[base + 2] & 0xff;
            int a = rgba[base + 3] & 0xff;
            argb[i] = Color.argb(a, r, g, b);
        }
        return argbToNv21(argb, width, height);
    }

    public static byte[] i420ToNv21(byte[] i420, int width, int height) {
        int frameSize = width * height;
        int chromaSize = frameSize / 4;
        byte[] nv21 = new byte[frameSize + chromaSize * 2];
        System.arraycopy(i420, 0, nv21, 0, Math.min(frameSize, i420.length));
        int uOffset = frameSize;
        int vOffset = frameSize + chromaSize;
        int output = frameSize;
        for (int i = 0; i < chromaSize && output + 1 < nv21.length; i++) {
            nv21[output++] = safeRead(i420, vOffset + i);
            nv21[output++] = safeRead(i420, uOffset + i);
        }
        return nv21;
    }

    public static byte[] resizeNv21(byte[] input, int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        if (inputWidth == outputWidth && inputHeight == outputHeight) {
            byte[] copy = new byte[Math.min(input.length, outputWidth * outputHeight * 3 / 2)];
            System.arraycopy(input, 0, copy, 0, copy.length);
            return copy;
        }
        byte[] output = new byte[outputWidth * outputHeight * 3 / 2];
        int outputFrameSize = outputWidth * outputHeight;
        int inputFrameSize = inputWidth * inputHeight;
        for (int y = 0; y < outputHeight; y++) {
            int srcY = y * inputHeight / outputHeight;
            for (int x = 0; x < outputWidth; x++) {
                int srcX = x * inputWidth / outputWidth;
                output[y * outputWidth + x] = safeRead(input, srcY * inputWidth + srcX);
            }
        }
        for (int y = 0; y < outputHeight / 2; y++) {
            int srcY = y * inputHeight / outputHeight;
            for (int x = 0; x < outputWidth / 2; x++) {
                int srcX = x * inputWidth / outputWidth;
                int src = inputFrameSize + (srcY * inputWidth) + (srcX * 2);
                int dst = outputFrameSize + (y * outputWidth) + (x * 2);
                output[dst] = safeRead(input, src);
                output[dst + 1] = safeRead(input, src + 1);
            }
        }
        return output;
    }

    public static byte[] rotateNv21(byte[] input, int width, int height, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) {
            byte[] copy = new byte[Math.min(input.length, width * height * 3 / 2)];
            System.arraycopy(input, 0, copy, 0, copy.length);
            return copy;
        }
        if (normalized != 90 && normalized != 270) {
            throw new IllegalArgumentException("Only 90/270 degree NV21 rotation is supported here");
        }

        int safeWidth = Math.max(2, width & ~1);
        int safeHeight = Math.max(2, height & ~1);
        int outputWidth = safeHeight;
        int outputHeight = safeWidth;
        int inputFrameSize = safeWidth * safeHeight;
        byte[] output = new byte[outputWidth * outputHeight * 3 / 2];

        int out = 0;
        if (normalized == 90) {
            for (int x = 0; x < safeWidth; x++) {
                for (int y = safeHeight - 1; y >= 0; y--) {
                    output[out++] = safeRead(input, y * safeWidth + x);
                }
            }
        } else {
            for (int x = safeWidth - 1; x >= 0; x--) {
                for (int y = 0; y < safeHeight; y++) {
                    output[out++] = safeRead(input, y * safeWidth + x);
                }
            }
        }

        int outputFrameSize = outputWidth * outputHeight;
        int outputChromaWidth = outputWidth / 2;
        int outputChromaHeight = outputHeight / 2;
        int inputChromaWidth = safeWidth / 2;
        int inputChromaHeight = safeHeight / 2;
        for (int dy = 0; dy < outputChromaHeight; dy++) {
            for (int dx = 0; dx < outputChromaWidth; dx++) {
                int srcCol;
                int srcRow;
                if (normalized == 90) {
                    srcCol = dy;
                    srcRow = inputChromaHeight - 1 - dx;
                } else {
                    srcCol = inputChromaWidth - 1 - dy;
                    srcRow = dx;
                }
                int src = inputFrameSize + srcRow * safeWidth + srcCol * 2;
                int dst = outputFrameSize + dy * outputWidth + dx * 2;
                output[dst] = safeRead(input, src);
                output[dst + 1] = safeRead(input, src + 1);
            }
        }
        return output;
    }
    public static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        int[] argb = new int[width * height];
        int frameSize = width * height;
        int yp = 0;
        for (int y = 0; y < height; y++) {
            int uvp = frameSize + (y >> 1) * width;
            int u = 0;
            int v = 0;
            for (int x = 0; x < width; x++, yp++) {
                int yValue = (safeRead(nv21, yp) & 0xff) - 16;
                if (yValue < 0) {
                    yValue = 0;
                }
                if ((x & 1) == 0) {
                    v = (safeRead(nv21, uvp++) & 0xff) - 128;
                    u = (safeRead(nv21, uvp++) & 0xff) - 128;
                }
                int c = 298 * yValue;
                int r = (c + 409 * v + 128) >> 8;
                int g = (c - 100 * u - 208 * v + 128) >> 8;
                int b = (c + 516 * u + 128) >> 8;
                argb[yp] = Color.rgb(clamp(r), clamp(g), clamp(b));
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private static byte[] argbToNv21(int[] argb, int width, int height) {
        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize + frameSize / 2];
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = argb[y * width + x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int yValue = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int uValue = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vValue = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                nv21[yIndex++] = (byte) clamp(yValue);
                if ((y & 1) == 0 && (x & 1) == 0 && uvIndex + 1 < nv21.length) {
                    nv21[uvIndex++] = (byte) clamp(vValue);
                    nv21[uvIndex++] = (byte) clamp(uValue);
                }
            }
        }
        return nv21;
    }

    private static Bitmap centerCrop(Bitmap source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        float sourceRatio = source.getWidth() / (float) source.getHeight();
        float targetRatio = width / (float) height;
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();
        int cropX = 0;
        int cropY = 0;
        if (sourceRatio > targetRatio) {
            cropWidth = Math.round(source.getHeight() * targetRatio);
            cropX = (source.getWidth() - cropWidth) / 2;
        } else if (sourceRatio < targetRatio) {
            cropHeight = Math.round(source.getWidth() / targetRatio);
            cropY = (source.getHeight() - cropHeight) / 2;
        }
        Bitmap cropped = Bitmap.createBitmap(source, cropX, cropY, cropWidth, cropHeight);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, width, height, true);
        if (cropped != source) {
            cropped.recycle();
        }
        return scaled;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static byte safeRead(byte[] data, int index) {
        return index >= 0 && index < data.length ? data[index] : (byte) 128;
    }
}
