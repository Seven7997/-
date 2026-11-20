package io.github.wechatbubbles;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationListenerService.RankingMap;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** SystemUI presents bubbles; WeChat only reports the visible chat UI's opaque identity. */
public final class SystemUiHook implements IXposedHookLoadPackage {
    private static final String TAG = "[WeChatBubbles] ";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile BubbleController controller;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (Edition.HOOK_WECHAT && BubblePolicy.WECHAT.equals(p.packageName)
                && BubblePolicy.WECHAT.equals(p.processName)
                && android.os.Process.myUid() / 100000 == 0
                && INSTALLED.compareAndSet(false, true)) {
            // Wait for Tinker's attachBaseContext/class-loader setup before resolving chat UI classes.
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Application)) return;
                    Application app = (Application) param.thisObject;
                    if (BubblePolicy.WECHAT.equals(app.getPackageName())) {
                        Edition.installWechatObserver(app.getClassLoader());
                    }
                }
            });
            return;
        }
        if (!"com.android.systemui".equals(p.packageName)
                || !"com.android.systemui".equals(p.processName)
                || !INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> application = XposedHelpers.findClassIfExists(
                    "com.android.systemui.SystemUIApplication", p.classLoader);
            if (application == null) application = XposedHelpers.findClassIfExists(
                    "com.android.systemui.application.impl.SystemUIApplicationImpl", p.classLoader);
            if (application != null) {
                XposedBridge.hookAllMethods(application, "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Application) {
                            initialize((Context) param.thisObject, p.classLoader);
                        }
                    }
                });
            }
            Class<?> listener = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.MiuiNotificationListener", p.classLoader);
            if (listener == null) listener = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.NotificationListener", p.classLoader);
            if (listener == null) {
                log("NotificationListener class missing; no notification behavior changed");
                return;
            }
            XposedBridge.hookAllMethods(listener, "onListenerConnected", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    initializeFromListener(param.thisObject, p.classLoader);
                }
            });
            XposedBridge.hookAllMethods(listener, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.hasThrowable()) return;
                        if (controller == null) initializeFromListener(param.thisObject, p.classLoader);
                        BubbleController c = controller;
                        if (c == null) return;
                        StatusBarNotification sbn = null;
                        RankingMap ranks = null;
                        for (Object arg : param.args) {
                            if (arg instanceof StatusBarNotification) sbn = (StatusBarNotification) arg;
                            if (arg instanceof RankingMap) ranks = (RankingMap) arg;
                        }
                        if (sbn != null) c.onNotification(sbn, ranks);
                    } catch (Throwable e) { logClass("notification callback", e); }
                }
            });
            XposedBridge.hookAllMethods(listener, "onNotificationRemoved", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        BubbleController c = controller;
                        if (c == null) return;
                        StatusBarNotification sbn = null;
                        int reason = 0;
                        for (Object arg : param.args) {
                            if (arg instanceof StatusBarNotification) sbn = (StatusBarNotification) arg;
                            if (arg instanceof Integer) reason = (Integer) arg;
                        }
                        if (sbn != null) c.onRemoved(sbn, reason);
                    } catch (Throwable e) { logClass("remove callback", e); }
                }
            });
            // Only replace an actual heads-up presentation after our bubble was attached.
            // The shade notification, channel importance, vibration and sound are untouched.
            String[] interrupters = {
                    "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                    "com.android.systemui.statusbar.notification.interruption.MiuiNotificationInterruptStateProviderImpl"
            };
            for (String name : interrupters) {
                Class<?> type = XposedHelpers.findClassIfExists(name, p.classLoader);
                if (type == null) continue;
                XposedBridge.hookAllMethods(type, "shouldHeadsUp", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!Boolean.TRUE.equals(param.getResult()) || param.args.length == 0
                                    || Looper.myLooper() != Looper.getMainLooper()) return;
                            BubbleController c = controller;
                            if (c != null && c.presentFromEntry(param.args[0])) param.setResult(false);
                        } catch (Throwable e) { logClass("heads-up", e); }
                    }
                });
            }
            Class<?> visual = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
                    p.classLoader);
            if (visual != null) {
                XC_MethodHook replaceVisualPeek = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object decision = param.getResult();
                            if (decision == null || param.args.length == 0
                                    || Looper.myLooper() != Looper.getMainLooper()
                                    || !XposedHelpers.getBooleanField(decision, "shouldInterrupt")) return;
                            BubbleController c = controller;
                            if (c != null && c.presentFromEntry(param.args[0])) {
                                Object suppressed;
                                try {
                                    // ColorOS 17 / current AOSP: DecisionImpl(boolean, String).
                                    suppressed = XposedHelpers.newInstance(decision.getClass(), false, "WeChatBubbles");
                                } catch (NoSuchMethodError legacySignature) {
                                    // Preserve the inspected HyperOS constructor order.
                                    suppressed = XposedHelpers.newInstance(decision.getClass(), "WeChatBubbles", false);
                                }
                                param.setResult(suppressed);
                            }
                        } catch (Throwable error) { logClass("visual decision", error); }
                    }
                };
                XposedBridge.hookAllMethods(visual, "makeAndLogHeadsUpDecision", replaceVisualPeek);
                XposedBridge.hookAllMethods(visual, "makeUnloggedHeadsUpDecision", replaceVisualPeek);
            }
            log("bubble hooks installed " + BuildConfig.VERSION_NAME
                    + "; SystemUI; listener=" + listener.getName());
        } catch (Throwable e) { logClass("install", e); }
    }

    private static void initializeFromListener(Object listener, ClassLoader loader) {
        if (controller != null) return;
        try {
            // SystemUI creates this listener itself; its inherited Service base is NOT attached.
            // Use the injected real Context, never the listener's ContextWrapper methods.
            Object realContext = XposedHelpers.getObjectField(listener, "mContext");
            if (realContext instanceof Context) initialize((Context) realContext, loader);
        } catch (Throwable error) { logClass("listener context", error); }
    }

    private static synchronized void initialize(Context context, ClassLoader loader) {
        if (controller != null) return;
        try {
            Context appContext = context.getApplicationContext();
            controller = new BubbleController(appContext == null ? context : appContext, loader);
            controller.start();
        } catch (Throwable e) { logClass("initialize", e); }
    }

    static void log(String value) { XposedBridge.log(TAG + value); }
    static void logClass(String operation, Throwable e) {
        // Do not log notification titles, text, intents, recipients, or exception messages.
        XposedBridge.log(TAG + operation + ": " + e.getClass().getSimpleName());
        int count = 0;
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith("io.github.wechatbubbles.") && count++ < 4) {
                XposedBridge.log(TAG + "at " + frame.getClassName() + "." + frame.getMethodName()
                        + ":" + frame.getLineNumber());
            }
        }
    }
}
