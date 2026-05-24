package com.yourapp.remotectrl.input;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class Main {
    private static UinputInjector uinputInjector;
    private static Method injectMethod;
    private static Object inputManager;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: Main <authToken> <screenWidth> <screenHeight>");
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
        int screenWidth = args.length > 1 ? Integer.parseInt(args[1]) : 1080;
        int screenHeight = args.length > 2 ? Integer.parseInt(args[2]) : 2400;

        initReflection();

        uinputInjector = new UinputInjector();
        if (!uinputInjector.init(screenWidth, screenHeight)) {
            System.err.println("Uinput initialization failed!");
            return;
        }
        System.out.println("Virtual Uinput Touch Device Created Successfully (" + screenWidth + "x" + screenHeight + ")");

        try (LocalServerSocket serverSocket = new LocalServerSocket("supercontroler_input")) {
            while (true) {
                LocalSocket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket, authToken)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (uinputInjector != null) {
                uinputInjector.destroy();
            }
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
        if (uinputInjector == null) return;
        try {
            uinputInjector.injectTap((int) x, (int) y);
        } catch (Exception e) {
            System.err.println("injectTap failed: " + e.getMessage());
        }
    }

    private static void injectSwipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (uinputInjector == null) return;
        try {
            uinputInjector.injectSwipe((int) x1, (int) y1, (int) x2, (int) y2, durationMs);
        } catch (Exception e) {
            System.err.println("injectSwipe failed: " + e.getMessage());
        }
    }

    private static void injectLongPress(float x, float y) {
        if (uinputInjector == null) return;
        try {
            uinputInjector.injectLongPress((int) x, (int) y);
        } catch (Exception e) {
            System.err.println("injectLongPress failed: " + e.getMessage());
        }
    }

    private static void injectScroll(float x, float y, float dx, float dy) {
        if (uinputInjector == null) return;
        try {
            uinputInjector.injectSwipe((int) x, (int) y, (int) (x + dx), (int) (y + dy), 200);
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
}
