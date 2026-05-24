package com.yourapp.remotectrl.input;

public class UinputInjector {
    static {
        System.load("/data/local/tmp/libuinput-ctrl.so");
    }

    private native boolean nativeInit(int width, int height);
    private native void nativeInjectEvent(short type, short code, int value);
    private native void nativeDestroy();

    private static final short EV_SYN = 0x00;
    private static final short EV_KEY = 0x01;
    private static final short EV_ABS = 0x03;

    private static final short SYN_REPORT = 0x00;
    private static final short BTN_TOUCH = 0x14a;
    private static final short BTN_TOOL_FINGER = 0x145;
    private static final short ABS_X = 0x00;
    private static final short ABS_Y = 0x01;
    private static final short ABS_MT_SLOT = 0x2f;
    private static final short ABS_MT_TRACKING_ID = 0x39;
    private static final short ABS_MT_POSITION_X = 0x35;
    private static final short ABS_MT_POSITION_Y = 0x36;
    private static final short ABS_MT_PRESSURE = 0x3a;
    private static final short ABS_MT_TOUCH_MAJOR = 0x30;

    private int trackingIdCounter = 1;
    private final java.util.Random random = new java.util.Random();

    public boolean init(int screenWidth, int screenHeight) {
        return nativeInit(screenWidth, screenHeight);
    }

    private void sendEvent(short type, short code, int value) {
        nativeInjectEvent(type, code, value);
    }

    private int getNextTrackingId() {
        return (trackingIdCounter++) % 10000 + 1;
    }

    private float easeInOutQuad(float t) {
        return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }

    private float[] getBezierPoint(float t, float[] p0, float[] p1, float[] p2, float[] p3) {
        float u = 1 - t;
        float tt = t * t;
        float uu = u * u;
        float uuu = uu * u;
        float ttt = tt * t;
        float[] p = new float[2];
        p[0] = uuu * p0[0] + 3 * uu * t * p1[0] + 3 * u * tt * p2[0] + ttt * p3[0];
        p[1] = uuu * p0[1] + 3 * uu * t * p1[1] + 3 * u * tt * p2[1] + ttt * p3[1];
        return p;
    }

    private int getHumanTapDuration() {
        int duration = (int) (random.nextGaussian() * 30 + 100);
        return Math.max(50, Math.min(250, duration));
    }

    public void injectTap(int x, int y) throws Exception {
        int jitterX = x + (int)(random.nextGaussian() * 3);
        int jitterY = y + (int)(random.nextGaussian() * 3);
        int trackingId = getNextTrackingId();

        int totalDuration = getHumanTapDuration();
        int phase1 = totalDuration / 3;
        int phase2 = totalDuration - phase1;

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
        sendEvent(EV_ABS, ABS_MT_POSITION_X, jitterX);
        sendEvent(EV_ABS, ABS_MT_POSITION_Y, jitterY);
        sendEvent(EV_ABS, ABS_X, jitterX);
        sendEvent(EV_ABS, ABS_Y, jitterY);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 40 + random.nextInt(15));
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 7);
        sendEvent(EV_KEY, BTN_TOUCH, 1);
        sendEvent(EV_KEY, BTN_TOOL_FINGER, 1);
        sendEvent(EV_SYN, SYN_REPORT, 0);

        Thread.sleep(phase1);

        int moveX = jitterX + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(2));
        int moveY = jitterY + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(2));

        sendEvent(EV_ABS, ABS_MT_POSITION_X, moveX);
        sendEvent(EV_ABS, ABS_MT_POSITION_Y, moveY);
        sendEvent(EV_ABS, ABS_X, moveX);
        sendEvent(EV_ABS, ABS_Y, moveY);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 70 + random.nextInt(20));
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 14);
        sendEvent(EV_SYN, SYN_REPORT, 0);

        Thread.sleep(phase2);

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
        sendEvent(EV_KEY, BTN_TOUCH, 0);
        sendEvent(EV_KEY, BTN_TOOL_FINGER, 0);
        sendEvent(EV_SYN, SYN_REPORT, 0);
    }

    public void injectSwipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        int steps = Math.max(durationMs / 10, 10);
        int stepTime = durationMs / steps;
        int trackingId = getNextTrackingId();

        float[] p0 = {x1, y1};
        float[] p3 = {x2, y2};
        float offset1 = (random.nextBoolean() ? 1 : -1) * (10 + random.nextInt(30));
        float offset2 = (random.nextBoolean() ? 1 : -1) * (10 + random.nextInt(30));
        float[] p1 = {x1 + (x2 - x1) * 0.33f + offset1, y1 + (y2 - y1) * 0.33f + offset1};
        float[] p2 = {x1 + (x2 - x1) * 0.66f + offset2, y1 + (y2 - y1) * 0.66f + offset2};

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
        sendEvent(EV_ABS, ABS_MT_POSITION_X, x1);
        sendEvent(EV_ABS, ABS_MT_POSITION_Y, y1);
        sendEvent(EV_ABS, ABS_X, x1);
        sendEvent(EV_ABS, ABS_Y, y1);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 70 + random.nextInt(20));
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 12 + random.nextInt(5));
        sendEvent(EV_KEY, BTN_TOUCH, 1);
        sendEvent(EV_KEY, BTN_TOOL_FINGER, 1);
        sendEvent(EV_SYN, SYN_REPORT, 0);

        for (int i = 1; i <= steps; i++) {
            int jitteredSleep = stepTime + (int)(random.nextGaussian() * 2);
            Thread.sleep(Math.max(1, jitteredSleep));

            float t = (float) i / steps;
            float easedT = easeInOutQuad(t);
            float[] point = getBezierPoint(easedT, p0, p1, p2, p3);

            int cx = (int) point[0] + (random.nextInt(3) - 1);
            int cy = (int) point[1] + (random.nextInt(3) - 1);

            int dynamicPressure = 40 + (int)(Math.abs(easedT - 0.5f) * 60) + random.nextInt(5);

            sendEvent(EV_ABS, ABS_MT_POSITION_X, cx);
            sendEvent(EV_ABS, ABS_MT_POSITION_Y, cy);
            sendEvent(EV_ABS, ABS_X, cx);
            sendEvent(EV_ABS, ABS_Y, cy);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, dynamicPressure);
            sendEvent(EV_SYN, SYN_REPORT, 0);
        }

        Thread.sleep(stepTime + random.nextInt(20));

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
        sendEvent(EV_KEY, BTN_TOUCH, 0);
        sendEvent(EV_KEY, BTN_TOOL_FINGER, 0);
        sendEvent(EV_SYN, SYN_REPORT, 0);
    }

    public void injectLongPress(int x, int y) throws Exception {
        int trackingId = getNextTrackingId();

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
        sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
        sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
        sendEvent(EV_ABS, ABS_X, x);
        sendEvent(EV_ABS, ABS_Y, y);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 50);
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 10);
        sendEvent(EV_KEY, BTN_TOUCH, 1);
        sendEvent(EV_SYN, SYN_REPORT, 0);

        Thread.sleep(600);

        sendEvent(EV_ABS, ABS_MT_SLOT, 0);
        sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
        sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
        sendEvent(EV_KEY, BTN_TOUCH, 0);
        sendEvent(EV_SYN, SYN_REPORT, 0);
    }

    public void destroy() {
        nativeDestroy();
    }
}
