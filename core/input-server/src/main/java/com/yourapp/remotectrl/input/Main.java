package com.yourapp.remotectrl.input;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Main {
    private static Method injectMethod;
    private static Object inputManager;
    private static int realDeviceId;
    private static Method setDeviceIdMethod;
    private static KernelTouchInjector kernelInjector;
    private static boolean useKernelInjection = false;
    private static int kernelFailCount = 0;
    private static final int MAX_KERNEL_FAILS = 3;

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
        realDeviceId = getRealTouchScreenId();

        try {
            setDeviceIdMethod = MotionEvent.class.getMethod("setDeviceId", int.class);
        } catch (NoSuchMethodException e) {
            setDeviceIdMethod = null;
        }

        String touchNode = findTouchScreenNode();
        try {
            kernelInjector = new KernelTouchInjector(touchNode);
            useKernelInjection = true;
            System.out.println("Kernel Touch Injector initialized on: " + touchNode + " (64bit=" + kernelInjector.is64Bit + ")");
        } catch (Exception e) {
            useKernelInjection = false;
            System.err.println("Kernel injection failed, falling back to InputManager: " + e.getMessage());
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

    private static void setDeviceId(MotionEvent event, int deviceId) {
        if (setDeviceIdMethod != null) {
            try {
                setDeviceIdMethod.invoke(event, deviceId);
            } catch (Exception ignored) {}
        }
    }

    private static boolean shouldUseKernel() {
        return useKernelInjection && kernelInjector != null && kernelFailCount < MAX_KERNEL_FAILS;
    }

    private static void onKernelFail(String op, Exception e) {
        kernelFailCount++;
        System.err.println("Kernel " + op + " failed (" + kernelFailCount + "/" + MAX_KERNEL_FAILS + "): " + e.getMessage());
        if (kernelFailCount >= MAX_KERNEL_FAILS) {
            useKernelInjection = false;
            System.err.println("Kernel injection permanently disabled, using InputManager fallback");
        }
    }

    private static void injectTap(float x, float y) {
        if (shouldUseKernel()) {
            try {
                kernelInjector.injectTap((int) x, (int) y);
                return;
            } catch (Exception e) {
                onKernelFail("tap", e);
            }
        }

        long downTime = SystemClock.uptimeMillis();

        MotionEvent downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(downEvent, realDeviceId);
        injectEvent(downEvent);

        try { Thread.sleep((long)(50 + Math.random() * 70)); } catch (InterruptedException e) {}

        float endX = x + (float)((Math.random() - 0.5) * 4);
        float endY = y + (float)((Math.random() - 0.5) * 4);
        long upTime = SystemClock.uptimeMillis();
        MotionEvent upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(upEvent, realDeviceId);
        injectEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    private static void injectSwipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (shouldUseKernel()) {
            try {
                kernelInjector.injectSwipe((int) x1, (int) y1, (int) x2, (int) y2, durationMs);
                return;
            } catch (Exception e) {
                onKernelFail("swipe", e);
            }
        }

        long downTime = SystemClock.uptimeMillis();

        MotionEvent downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(downEvent, realDeviceId);
        injectEvent(downEvent);

        int steps = Math.max(durationMs / 16, 1);
        float dx = (x2 - x1) / steps;
        float dy = (y2 - y1) / steps;
        long stepTime = durationMs / steps;

        for (int i = 1; i <= steps; i++) {
            try { Thread.sleep(stepTime); } catch (InterruptedException e) {}
            long eventTime = downTime + i * stepTime;
            float cx = x1 + dx * i;
            float cy = y1 + dy * i;
            MotionEvent moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, cx, cy, 0);
            moveEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDeviceId(moveEvent, realDeviceId);
            injectEvent(moveEvent);
            moveEvent.recycle();
        }

        try { Thread.sleep(stepTime); } catch (InterruptedException e) {}
        long upTime = SystemClock.uptimeMillis();
        MotionEvent upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x2, y2, 0);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(upEvent, realDeviceId);
        injectEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    private static void injectLongPress(float x, float y) {
        if (shouldUseKernel()) {
            try {
                kernelInjector.injectLongPress((int) x, (int) y);
                return;
            } catch (Exception e) {
                onKernelFail("longpress", e);
            }
        }

        long downTime = SystemClock.uptimeMillis();

        MotionEvent downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(downEvent, realDeviceId);
        injectEvent(downEvent);

        try { Thread.sleep(600); } catch (InterruptedException e) {}

        long upTime = SystemClock.uptimeMillis();
        MotionEvent upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(upEvent, realDeviceId);
        injectEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    private static void injectScroll(float x, float y, float dx, float dy) {
        if (shouldUseKernel()) {
            try {
                kernelInjector.injectSwipe((int) x, (int) y, (int) (x + dx), (int) (y + dy), 200);
                return;
            } catch (Exception e) {
                onKernelFail("scroll", e);
            }
        }

        long downTime = SystemClock.uptimeMillis();

        MotionEvent downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(downEvent, realDeviceId);
        injectEvent(downEvent);

        int steps = 8;
        float stepDx = dx / steps;
        float stepDy = dy / steps;

        for (int i = 1; i <= steps; i++) {
            try { Thread.sleep(16); } catch (InterruptedException e) {}
            long eventTime = downTime + i * 16;
            float cx = x + stepDx * i;
            float cy = y + stepDy * i;
            MotionEvent moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, cx, cy, 0);
            moveEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDeviceId(moveEvent, realDeviceId);
            injectEvent(moveEvent);
            moveEvent.recycle();
        }

        long upTime = SystemClock.uptimeMillis();
        float endX = x + dx;
        float endY = y + dy;
        MotionEvent upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDeviceId(upEvent, realDeviceId);
        injectEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    private static void injectKey(int keyCode) {
        if (shouldUseKernel()) {
            try {
                kernelInjector.injectKey(keyCode);
                return;
            } catch (Exception e) {
                onKernelFail("key", e);
            }
        }

        long downTime = SystemClock.uptimeMillis();

        KeyEvent downEvent = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        injectEvent(downEvent);

        try { Thread.sleep(50); } catch (InterruptedException e) {}

        long upTime = SystemClock.uptimeMillis();
        KeyEvent upEvent = new KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0);
        injectEvent(upEvent);
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

    private static int getRealTouchScreenId() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual() && !device.isExternal() &&
               (device.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                return id;
            }
        }
        return 0;
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

    static String findKeyEventNode() {
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
                } else if (line.contains("KEY_VOLUMEUP") && currentNode != null) {
                    return currentNode;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
            if (process != null) { process.destroy(); try { process.waitFor(); } catch (Exception ignored) {} }
        }
        return null;
    }

    static class KernelTouchInjector {
        private final FileOutputStream out;
        private final boolean is64Bit;
        private final int structSize;
        private final byte[] bufferArray;
        private final ByteBuffer buffer;

        private static final short EV_SYN = 0x00;
        private static final short EV_KEY = 0x01;
        private static final short EV_ABS = 0x03;

        private static final short SYN_REPORT = 0x00;
        private static final short BTN_TOUCH = 0x14a;
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
            long time = System.currentTimeMillis();
            long sec = time / 1000;
            long usec = (time % 1000) * 1000;

            if (is64Bit) {
                buffer.putLong(sec);
                buffer.putLong(usec);
            } else {
                buffer.putInt((int) sec);
                buffer.putInt((int) usec);
            }
            buffer.putShort(type);
            buffer.putShort(code);
            buffer.putInt(value);

            out.write(bufferArray);
        }

        public void injectTap(int x, int y) throws Exception {
            int jitterX = x + (int)(Math.random() * 5 - 2);
            int jitterY = y + (int)(Math.random() * 5 - 2);

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, (int)(Math.random() * 10000) + 1);
            sendEvent(EV_ABS, ABS_MT_POSITION_X, jitterX);
            sendEvent(EV_ABS, ABS_MT_POSITION_Y, jitterY);
            sendEvent(EV_ABS, ABS_X, jitterX);
            sendEvent(EV_ABS, ABS_Y, jitterY);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 50);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 10);
            sendEvent(EV_KEY, BTN_TOUCH, 1);
            sendEvent(EV_SYN, SYN_REPORT, 0);

            Thread.sleep(45 + (int)(Math.random() * 20));

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            sendEvent(EV_ABS, ABS_X, 0);
            sendEvent(EV_ABS, ABS_Y, 0);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);

            out.flush();
        }

        public void injectSwipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
            int steps = Math.max(durationMs / 16, 1);
            int stepTime = durationMs / steps;

            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, (int)(Math.random() * 10000) + 1);
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
            sendEvent(EV_ABS, ABS_X, 0);
            sendEvent(EV_ABS, ABS_Y, 0);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
        }

        public void injectLongPress(int x, int y) throws Exception {
            sendEvent(EV_ABS, ABS_MT_SLOT, 0);
            sendEvent(EV_ABS, ABS_MT_TRACKING_ID, (int)(Math.random() * 10000) + 1);
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
            sendEvent(EV_ABS, ABS_X, 0);
            sendEvent(EV_ABS, ABS_Y, 0);
            sendEvent(EV_ABS, ABS_MT_PRESSURE, 0);
            sendEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 0);
            sendEvent(EV_KEY, BTN_TOUCH, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
        }

        public void injectKey(int keyCode) throws Exception {
            sendEvent(EV_KEY, (short) keyCode, 1);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();

            Thread.sleep(50);

            sendEvent(EV_KEY, (short) keyCode, 0);
            sendEvent(EV_SYN, SYN_REPORT, 0);
            out.flush();
        }
    }
}
