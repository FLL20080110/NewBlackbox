package top.niunaijun.blackbox.core;

import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Guest-process crash bridge. Keep this handler deliberately small: virtual processes may be in a
 * partially initialized or partially torn-down state when an exception reaches this point.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "BlackBoxCrashHandler";
    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (!(current instanceof CrashHandler)) {
            new CrashHandler(current);
        }
    }

    public CrashHandler() {
        this(Thread.getDefaultUncaughtExceptionHandler());
    }

    private CrashHandler(Thread.UncaughtExceptionHandler defaultHandler) {
        mDefaultHandler = defaultHandler;
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            Thread.UncaughtExceptionHandler handler = BlackBoxCore.get().getExceptionHandler();
            if (handler != null && handler != this && handler != mDefaultHandler) {
                handler.uncaughtException(t, e);
            }
        } catch (Throwable callbackError) {
            Log.e(TAG, "Exception callback failed", callbackError);
        }

        try {
            if (mDefaultHandler != null && mDefaultHandler != this) {
                mDefaultHandler.uncaughtException(t, e);
                return;
            }
        } catch (Throwable defaultError) {
            Log.e(TAG, "Default crash handler failed", defaultError);
        }

        // Last-resort termination avoids recursive uncaught-exception loops and frozen guest slots.
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
        System.exit(10);
    }
}
