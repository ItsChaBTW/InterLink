package com.interlink.app.util;

import java.lang.reflect.Method;

/**
 * Universal logging utility that routes to android.util.Log via reflection when running
 * on Android, and gracefully falls back to standard console output on JVM / unit tests.
 */
public class LogUtil {
    private static Method logD;
    private static Method logI;
    private static Method logW;
    private static Method logE;
    private static Method logEWithThrowable;
    private static boolean isAndroid = false;

    static {
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            logD = logClass.getMethod("d", String.class, String.class);
            logI = logClass.getMethod("i", String.class, String.class);
            logW = logClass.getMethod("w", String.class, String.class);
            logE = logClass.getMethod("e", String.class, String.class);
            logEWithThrowable = logClass.getMethod("e", String.class, String.class, Throwable.class);
            isAndroid = true;
        } catch (Throwable ignored) {
            isAndroid = false;
        }
    }

    public static void d(String tag, String msg) {
        if (isAndroid && logD != null) {
            try {
                logD.invoke(null, tag, msg);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println("[D/" + tag + "] " + msg);
    }

    public static void i(String tag, String msg) {
        if (isAndroid && logI != null) {
            try {
                logI.invoke(null, tag, msg);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println("[I/" + tag + "] " + msg);
    }

    public static void w(String tag, String msg) {
        if (isAndroid && logW != null) {
            try {
                logW.invoke(null, tag, msg);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println("[W/" + tag + "] " + msg);
    }

    public static void e(String tag, String msg) {
        if (isAndroid && logE != null) {
            try {
                logE.invoke(null, tag, msg);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println("[E/" + tag + "] " + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (isAndroid && logEWithThrowable != null) {
            try {
                logEWithThrowable.invoke(null, tag, msg, tr);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println("[E/" + tag + "] " + msg + ": " + (tr != null ? tr.getMessage() : ""));
    }
}

