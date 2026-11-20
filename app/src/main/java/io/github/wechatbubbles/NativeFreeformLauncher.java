package io.github.wechatbubbles;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.XposedHelpers;

/** Uses the detected ROM's native notification small-window entry from the SystemUI UID. */
final class NativeFreeformLauncher {
    private final Context context;
    private final Class<?> miuiUtils;
    private final Class<?> freeformManager;
    private final ColorOsFreeform colorOs;

    NativeFreeformLauncher(Context context, ClassLoader loader) {
        this.context = context;
        this.miuiUtils = XposedHelpers.findClassIfExists("android.util.MiuiMultiWindowUtils", loader);
        this.freeformManager = XposedHelpers.findClassIfExists("miui.app.MiuiFreeFormManager", loader);
        this.colorOs = new ColorOsFreeform(context, loader);
    }

    boolean hasActiveSmallWindow(String targetPackage) {
        if (colorOs.isAvailable()) return colorOs.hasActiveSmallWindow(targetPackage);
        try {
            if (freeformManager != null) {
                Object front = XposedHelpers.callStaticMethod(freeformManager,
                        "getAllFreeFormStackInfosOnDisplay", 0);
                if (containsPackage(front, targetPackage)) return true;
                Object pinned = XposedHelpers.callStaticMethod(freeformManager,
                        "getAllPinedFreeFormStackInfosOnDisplay", 0);
                if (containsPackage(pinned, targetPackage)) return true;
                if (front instanceof java.util.List && pinned instanceof java.util.List) return false;
            }
        } catch (Throwable ignored) { /* Fall back to live task information on other ROMs. */ }
        try {
            android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningTaskInfo task : am.getRunningTasks(50)) {
                boolean target = task.topActivity != null && targetPackage.equals(task.topActivity.getPackageName());
                target |= task.baseActivity != null && targetPackage.equals(task.baseActivity.getPackageName());
                if (!target) continue;
                Object configuration = XposedHelpers.getObjectField(task, "configuration");
                Object window = XposedHelpers.getObjectField(configuration, "windowConfiguration");
                int mode = (Integer) XposedHelpers.callMethod(window, "getWindowingMode");
                if (mode == 5 && XposedHelpers.getBooleanField(task, "isVisible")) return true;
            }
        } catch (Throwable ignored) { /* An unknown state must not lose a new notification. */ }
        return false;
    }

    static boolean isActivityInSmallWindow(Activity activity) {
        if (activity == null) return false;
        if (ColorOsFreeform.isActivityInSmallWindow(activity)) return true;
        try {
            Object configuration = XposedHelpers.getObjectField(
                    activity.getResources().getConfiguration(), "windowConfiguration");
            return Integer.valueOf(5).equals(XposedHelpers.callMethod(configuration, "getWindowingMode"));
        } catch (Throwable ignored) { return false; }
    }

    private boolean containsPackage(Object raw, String targetPackage) {
        if (!(raw instanceof java.util.List)) return false;
        for (Object stack : (java.util.List<?>) raw) {
            if (stack == null) continue;
            Object name = XposedHelpers.getObjectField(stack, "packageName");
            int userId = XposedHelpers.getIntField(stack, "userId");
            if (userId == 0 && targetPackage.equals(name)) return true;
        }
        return false;
    }

    boolean isAvailable() {
        return colorOs.isAvailable() || (miuiUtils != null
                && context.getPackageManager().hasSystemFeature("android.software.freeform_window_management"));
    }

    String backendName() {
        if (colorOs.isAvailable()) return "ColorOS FlexibleWindow";
        return miuiUtils == null ? "Unavailable" : "HyperOS Freeform";
    }

    void launch(String targetPackage, PendingIntent pendingIntent) throws Exception {
        if (!BubblePolicy.WECHAT.equals(targetPackage) && !BubblePolicy.MODULE.equals(targetPackage)) {
            throw new SecurityException("Unsupported target");
        }
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardLocked()) {
            throw new SecurityException("Device locked");
        }
        if (pendingIntent == null || !pendingIntent.isActivity()
                || !targetPackage.equals(pendingIntent.getCreatorPackage())) {
            throw new IllegalArgumentException("Activity notification intent required");
        }
        if (!isAvailable()) throw new UnsupportedOperationException("Native freeform unavailable");
        if (colorOs.isAvailable()) {
            // Pass the original notification PendingIntent to ColorOS's own launch service.
            colorOs.launch(targetPackage, pendingIntent);
            return;
        }
        Object result = XposedHelpers.callStaticMethod(miuiUtils, "getActivityOptions",
                context, targetPackage, true);
        if (!(result instanceof ActivityOptions)) {
            throw new UnsupportedOperationException("ROM denied small window for target");
        }
        ActivityOptions options = (ActivityOptions) result;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        Bundle bundle = options.toBundle();
        // No text, RemoteInput or send-message intent is created.
        pendingIntent.send(context, 0, null, null, null, null, bundle);
    }
}
