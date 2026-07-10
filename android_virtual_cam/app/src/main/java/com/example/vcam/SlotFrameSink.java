package com.example.vcam;

public final class SlotFrameSink implements FrameSink {
    private final int slotId;
    private final String sessionToken;

    public SlotFrameSink(int slotId, String sessionToken) {
        this.slotId = slotId;
        this.sessionToken = sessionToken;
    }

    @Override
    public void onNv21Frame(byte[] nv21, int width, int height, long timestampNs) {
        VirtualCameraBridge.publishNv21(slotId, sessionToken, nv21, width, height, timestampNs);
    }

    @Override
    public void onI420Frame(byte[] i420, int width, int height, long timestampNs) {
        onNv21Frame(Nv21Converter.i420ToNv21(i420, width, height), width, height, timestampNs);
    }

    @Override
    public void onRgbaFrame(byte[] rgba, int width, int height, long timestampNs) {
        onNv21Frame(Nv21Converter.rgbaToNv21(rgba, width, height), width, height, timestampNs);
    }
}
