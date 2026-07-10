package com.example.vcam;

import java.io.File;

public final class VirtualMediaResolver {
    private static final String[] VIDEO_NAMES = {
            "virtual.mp4",
            "virtual.3gp",
            "virtual.3gpp",
            "virtual.mov",
            "virtual.webm"
    };
    private static final String[] IMAGE_NAMES = {
            "virtual.jpg",
            "virtual.jpeg",
            "virtual.png",
            "virtual.webp",
            "1000.bmp"
    };

    private VirtualMediaResolver() {
    }

    public static File findVideoFile(String basePath) {
        for (String name : VIDEO_NAMES) {
            File file = new File(basePath + name);
            if (file.exists()) {
                return file;
            }
        }
        return new File(basePath + VIDEO_NAMES[0]);
    }

    public static File findImageFile(String basePath) {
        for (String name : IMAGE_NAMES) {
            File file = new File(basePath + name);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    public static boolean hasAnySource(String basePath) {
        return findVideoFile(basePath).exists() || findImageFile(basePath) != null;
    }

    public static String activeVideoPath(String basePath) {
        return findVideoFile(basePath).getAbsolutePath();
    }
}
