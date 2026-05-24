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

        public void injectTap(int x, int y) throws Exception {
            int jitterX = x + (int)(Math.random() * 5 - 2);
            int jitterY = y + (int)(Math.random() * 5 - 2);
            int trackingId = getNextTrackingId();

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
            sendEvent(EV_ABS, ABS_MT_POSITION_X, jitterX);
            sendEvent(EV_ABS, ABS_MT_POSITION_Y, jitterY);
            sendEvent(EV_ABS, ABS_X, jitterX);
            sendEvent(EV_ABS, ABS_Y, jitterY);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 30 + (int)(Math.random() * 10));
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 5);
            sendEvent(EV_KEY, BTN_TOUCH, 1);
            sendEvent(EV_KEY, BTN_TOOL_FINGER, 1);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();

            Thread.sleep(40 + (int)(Math.random() * 20));

            int moveX = jitterX + (Math.random() > 0.5 ? 1 : -1) * (1 + (int)(Math.random() * 2));
            int moveY = jitterY + (Math.random() > 0.5 ? 1 : -1) * (1 + (int)(Math.random() * 2));

            sendEvent(EV_ABS, ABS_MT_POSITION_X, moveX);
            sendEvent(EV_ABS, ABS_MT_POSITION_Y, moveY);
            sendEvent(EV_ABS, ABS_X, moveX);
            sendEvent(EV_ABS, ABS_Y, moveY);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 60 + (int)(Math.random() * 30));
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 12 + (int)(Math.random() * 5));
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();

            Thread.sleep(50 + (int)(Math.random() * 30));

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
            int steps = Math.max(durationMs / 16, 1);
            int stepTime = durationMs / steps;
            int trackingId = getNextTrackingId();

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
            sendEvent(EV_ABS, ABS_MT_POSITION_X, x1);
            sendEvent(EV_ABS, ABS_MT_POSITION_Y, y1);
            sendEvent(EV_ABS, ABS_X, x1);
            sendEvent(EV_ABS, ABS_Y, y1);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 50);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 10);
            sendEvent(EV_KEY, BTN_TOUCH, 1);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();

            for (int i = 1; i <= steps; i++) {
                Thread.sleep(stepTime);
                int cx = x1 + (x2 - x1) * i / steps;
                int cy = y1 + (y2 - y1) * i / steps;

                sendEvent(EV_ABS, ABS_MT_POSITION_X, cx);
                sendEvent(EV_ABS, ABS_MT_POSITION_Y, cy);
                sendEvent(EV_ABS, ABS_X, cx);
                sendEvent(EV_ABS, ABS_Y, cy);
                sendEvent(EV_SYN, SYN_REPORT, 0);
                out.flush();
            }

            Thread.sleep(stepTime);

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
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
