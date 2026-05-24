package com.yourapp.remotectrl.input;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Main {
    private static KernelTouchInjector kernelInjector;
    private static Method injectMethod;
    private static Object inputManager;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Missing auth token.");
            return;
        }

        try {
            Method getRuntimeMethod = Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("getRuntime");
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setHiddenApiExemptionsMethod = vmRuntime.getClass().getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setHiddenApiExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Exception e) {
            e.printStackTrace();
        }

        String authToken = args[0];

        initReflection();

        String touchNode = findTouchScreenNode();
        try {
            kernelInjector = new KernelTouchInjector(touchNode);
            System.out.println("Kernel Touch Injector initialized on: " + touchNode + " (64bit=" + kernelInjector.is64Bit + ")");
        } catch (Exception e) {
            System.err.println("Kernel injection FAILED: " + e.getMessage());
            kernelInjector = null;
        }

        try (LocalServerSocket serverSocket = new LocalServerSocket("supercontroler_input")) {
            while (true) {
                LocalSocket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket, authToken)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(LocalSocket clientSocket, String authToken) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.equals(authToken)) {
                return;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                processCommand(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (Exception ignored) {}
        }
    }

    private static void processCommand(String cmd) {
        try {
            String[] parts = cmd.split(",");
            if (parts.length == 0) return;

            switch (parts[0]) {
                case "T":
                    injectTap(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                    break;
                case "S":
                    injectSwipe(
                        Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                        Float.parseFloat(parts[3]), Float.parseFloat(parts[4]),
                        Integer.parseInt(parts[5])
                    );
                    break;
                case "L":
                    injectLongPress(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                    break;
                case "R":
                    injectScroll(
                        Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                        Float.parseFloat(parts[3]), Float.parseFloat(parts[4])
                    );
                    break;
                case "K":
                    injectKey(Integer.parseInt(parts[1]));
                    break;
            }
        } catch (Exception ignored) {}
    }

    private static void initReflection() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance");
            inputManager = getInstanceMethod.invoke(null);
            injectMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void injectEvent(InputEvent event) {
        try {
            if (injectMethod != null && inputManager != null) {
                injectMethod.invoke(inputManager, event, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void injectTap(float x, float y) {
        if (kernelInjector == null) {
            System.err.println("injectTap: kernel injector not available");
            return;
        }
        try {
            kernelInjector.injectTap((int) x, (int) y);
        } catch (Exception e) {
            System.err.println("injectTap failed: " + e.getMessage());
        }
    }

    private static void injectSwipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (kernelInjector == null) {
            System.err.println("injectSwipe: kernel injector not available");
            return;
        }
        try {
            kernelInjector.injectSwipe((int) x1, (int) y1, (int) x2, (int) y2, durationMs);
        } catch (Exception e) {
            System.err.println("injectSwipe failed: " + e.getMessage());
        }
    }

    private static void injectLongPress(float x, float y) {
        if (kernelInjector == null) {
            System.err.println("injectLongPress: kernel injector not available");
            return;
        }
        try {
            kernelInjector.injectLongPress((int) x, (int) y);
        } catch (Exception e) {
            System.err.println("injectLongPress failed: " + e.getMessage());
        }
    }

    private static void injectScroll(float x, float y, float dx, float dy) {
        if (kernelInjector == null) {
            System.err.println("injectScroll: kernel injector not available");
            return;
        }
        try {
            kernelInjector.injectSwipe((int) x, (int) y, (int) (x + dx), (int) (y + dy), 200);
        } catch (Exception e) {
            System.err.println("injectScroll failed: " + e.getMessage());
        }
    }

    private static void injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        injectEvent(downEvent);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        injectEvent(upEvent);
    }

    static String findTouchScreenNode() {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec("getevent -pl");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String currentNode = null;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("add device")) {
                    int idx = line.indexOf("/dev/input/event");
                    if (idx >= 0) {
                        currentNode = line.substring(idx).trim();
                    }
                } else if (line.contains("ABS_MT_POSITION_X") && currentNode != null) {
                    return currentNode;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
            if (process != null) { process.destroy(); try { process.waitFor(); } catch (Exception ignored) {} }
        }
        return "/dev/input/event2";
    }

    static class KernelTouchInjector {
        private final FileOutputStream out;
        private final boolean is64Bit;
        private final int structSize;
        private final byte[] bufferArray;
        private final ByteBuffer buffer;
        private static int trackingIdCounter = 1;
        private final java.util.Random random = new java.util.Random();

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

        public KernelTouchInjector(String deviceNode) throws Exception {
            this.is64Bit = android.os.Process.is64Bit();
            this.structSize = is64Bit ? 24 : 16;
            this.bufferArray = new byte[structSize];
            this.buffer = ByteBuffer.wrap(bufferArray).order(ByteOrder.LITTLE_ENDIAN);
            this.out = new FileOutputStream(deviceNode);
        }

        private synchronized void sendEvent(short type, short code, int value) throws Exception {
            buffer.clear();
            if (is64Bit) {
                buffer.putLong(0L);
                buffer.putLong(0L);
            } else {
                buffer.putInt(0);
                buffer.putInt(0);
            }
            buffer.putShort(type);
            buffer.putShort(code);
            buffer.putInt(value);
            out.write(bufferArray);
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
            out.flush();

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
            out.flush();

            Thread.sleep(phase2);

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_KEY, BTN_TOOL_FINGER, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
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
            out.flush();

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
                out.flush();
            }

            Thread.sleep(stepTime + random.nextInt(20));

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_KEY, BTN_TOOL_FINGER, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
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
            out.flush();

            Thread.sleep(600);

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
        }
    }
}
