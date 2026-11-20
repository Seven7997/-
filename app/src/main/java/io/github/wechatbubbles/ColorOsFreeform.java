package io.github.wechatbubbles;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * ColorOS 17 native notification-window client, verified against PLK110_11.A.01_001.
 * OEM types stay behind reflection; this class has no Oplus compile dependency.
 * See research-rom/coloros17/freeform/README.md for the complete Binder path.
 */
final class ColorOsFreeform {
    private static final String ZOOM_MANAGER = "com.oplus.zoomwindow.OplusZoomWindowManager";
    private static final String FLEXIBLE_MANAGER = "com.oplus.flexiblewindow.FlexibleWindowManager";
    private static final String COMPONENT_SAFE = "oplus.permission.OPLUS_COMPONENT_SAFE";
    private static final int OWNER_USER = 0;

    private final Context context;
    private final Class<?> zoomClass;
    private final Class<?> flexibleClass;
    private final Object zoomManager;
    private final Object flexibleManager;
    private final boolean hasLaunchApi;

    ColorOsFreeform(Context context, ClassLoader loader) {
        this.context = context;
        zoomClass = XposedHelpers.findClassIfExists(ZOOM_MANAGER, loader);
        flexibleClass = XposedHelpers.findClassIfExists(FLEXIBLE_MANAGER, loader);
        Object zoom = null;
        Object flexible = null;
        boolean launchApi = false;
        try {
            if (zoomClass != null) {
                zoom = XposedHelpers.callStaticMethod(zoomClass, "getInstance");
                launchApi = XposedHelpers.findMethodExactIfExists(zoomClass,
                        "startZoomWindowByPendingIntent", PendingIntent.class, Bundle.class,
                        int.class, String.class) != null;
            }
            if (flexibleClass != null) {
                flexible = XposedHelpers.callStaticMethod(flexibleClass, "getInstance");
            }
        } catch (Throwable ignored) {
            // Absence or incompatible signatures must leave the MIUI backend usable.
        }
        zoomManager = zoom;
        flexibleManager = flexible;
        hasLaunchApi = launchApi;
    }

    boolean isAvailable() {
        if (context == null || zoomManager == null || !hasLaunchApi) return false;
        try {
            // The native Binder entry enforces this permission; a system feature named
            // android.software.freeform_window_management is not its availability test.
            return context.checkSelfPermission(COMPONENT_SAFE) == PackageManager.PERMISSION_GRANTED
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(zoomManager,
                    "isSupportZoomWindowMode"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Caller performs the common allowlist, unlocked-device and creator checks first. */
    void launch(String targetPackage, PendingIntent original) throws Exception {
        if (targetPackage == null || original == null || !original.isActivity()
                || !targetPackage.equals(original.getCreatorPackage())
                || original.getCreatorUserHandle() == null
                || (Integer) XposedHelpers.callMethod(original.getCreatorUserHandle(),
                        "getIdentifier") != OWNER_USER) {
            throw new IllegalArgumentException("Owner-user activity notification intent required");
        }
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Native ColorOS small window unavailable");
        }
        ActivityOptions basic = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 34) {
            basic.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        Bundle entry = basic.toBundle();
        // These are native entry-Bundle fields, not an options-only factory. On this
        // ROM the server converts extra_window_mode into real ActivityOptions, then
        // executes FlexibleTaskController.startFlexibleTaskInternal().
        entry.putInt((String) XposedHelpers.getStaticObjectField(zoomClass, "EXTRA_WINDOW_MODE"),
                XposedHelpers.getStaticIntField(zoomClass, "WINDOWING_MODE_ZOOM"));
        entry.putInt((String) XposedHelpers.getStaticObjectField(zoomClass,
                        "KEY_ZOOM_TYPE_FROM_RECENTS"),
                XposedHelpers.getStaticIntField(zoomClass, "FLAG_ON_START_ZOOM_FROM_NOTIFICATION"));
        // Both literals come from this ROM's SystemUI NotificationZoomStartUtil.
        entry.putString("launch_source_type", "localnotification");
        entry.putString("notificationStartFrom", "startWithoutAnim");
        Object raw = XposedHelpers.callMethod(zoomManager, "startZoomWindowByPendingIntent",
                original, entry, OWNER_USER, context.getPackageName());
        if (!(raw instanceof Integer)) {
            throw new IllegalStateException("Unexpected ColorOS small-window result");
        }
        int result = (Integer) raw;
        // ActivityManager.isStartResultSuccessful: [0, 100); includes deferred start=0.
        if (result < 0 || result >= 100) {
            throw new IllegalStateException("ColorOS small-window start failed: " + result);
        }
    }

    boolean hasActiveSmallWindow(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            if (zoomManager != null) {
                Object top = XposedHelpers.callMethod(zoomManager, "getCurrentZoomWindowState");
                if (isShownOwnerWindow(top, packageName)) return true;
                if (top != null) {
                    Object extra = XposedHelpers.getObjectField(top, "extension");
                    if (extra instanceof Bundle) {
                        // FlexibleTaskController's native multi-window snapshot key.
                        List<?> windows = ((Bundle) extra).getParcelableArrayList("ZoomWindows");
                        if (windows != null) {
                            for (Object window : windows) {
                                if (isShownOwnerWindow(window, packageName)) return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Continue with live task information; never print OEM state/Intent data.
        }
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null || flexibleManager == null) return false;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(100);
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (XposedHelpers.getIntField(task, "userId") != OWNER_USER) continue;
                boolean target = task.topActivity != null
                        && packageName.equals(task.topActivity.getPackageName());
                target |= task.baseActivity != null
                        && packageName.equals(task.baseActivity.getPackageName());
                if (!target || !XposedHelpers.getBooleanField(task, "isVisible")) continue;
                Object state = XposedHelpers.callMethod(flexibleManager,
                        "getFlexibleWindowState", task);
                if (state instanceof Integer && (Integer) state ==
                        XposedHelpers.getStaticIntField(flexibleClass, "FLEXIBLE_WINDOW_FREEFORM_MODE")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Unknown state must not suppress a new notification.
        }
        // lockPkg alone is deliberately insufficient: the inspected Flexible path
        // does not populate lockUserId. An unverified edge handle must fail open.
        return false;
    }

    private boolean isShownOwnerWindow(Object info, String packageName) {
        return info != null
                && packageName.equals(XposedHelpers.getObjectField(info, "zoomPkg"))
                && XposedHelpers.getIntField(info, "zoomUserId") == OWNER_USER
                && XposedHelpers.getBooleanField(info, "windowShown");
    }

    static boolean isActivityInSmallWindow(Activity activity) {
        if (activity == null) return false;
        try {
            Class<?> type = XposedHelpers.findClassIfExists(FLEXIBLE_MANAGER,
                    activity.getClassLoader());
            if (type == null) return false;
            Object manager = XposedHelpers.callStaticMethod(type, "getInstance");
            // The native Activity overload reads only this Activity's own extension
            // configuration. It needs no task enumeration or privileged Binder call.
            Object state = XposedHelpers.callMethod(manager, "getFlexibleWindowState", activity);
            return state instanceof Integer && (Integer) state ==
                    XposedHelpers.getStaticIntField(type, "FLEXIBLE_WINDOW_FREEFORM_MODE");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
