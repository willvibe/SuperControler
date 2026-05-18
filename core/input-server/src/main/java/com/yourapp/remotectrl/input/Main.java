package com.yourapp.remotectrl.input;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class Main {
    private static Method injectMethod;
    private static Object inputManager;
    private static int realDeviceId;
    private static Method setDeviceIdMethod;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Missing auth token.");
            return;
        }
        String authToken = args[0];

        initReflection();
        realDeviceId = getRealTouchScreenId();

        try {
            setDeviceIdMethod = MotionEvent.class.getMethod("setDeviceId", int.class);
        } catch (NoSuchMethodException e) {
            setDeviceIdMethod = null;
        }

        try (LocalServerSocket serverSocket = new LocalServerSocket("supercontroler_input")) {
            while (true) {
                LocalSocket clientSocket = serverSocket.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                String firstLine = reader.readLine();
                if (firstLine == null || !firstLine.equals(authToken)) {
                    clientSocket.close();
                    continue;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    processCommand(line);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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

    private static void injectTap(float x, float y) {
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
}
