package io.github.wechatbubbles;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Read-only observer of the displayed WeChat 8.0.71/8.0.80 UI. No storage or sending APIs. */
final class ChatStateObserver {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, Boolean> fragments = new WeakHashMap<>();
    private final Class<?> fragmentClass;
    private final Adapter adapter;
    private final Class<?> freeformManager;
    private Context app;
    private int settling;
    private boolean screenReceiver;
    private boolean errorLogged;
    private final Runnable publish = this::publishNow;

    /** Known APK signatures, resolved together before installing any observer hooks. */
    private static final class Adapter {
        final String profile;
        final Field contextField;
        final Method talkerGetter;
        final Method resume;
        final Method pause;
        final Method exit;
        final Method destroy;

        private Adapter(String profile, Field contextField, String talkerGetter,
                        Class<?> chat, Class<?> base, String resume, String pause, String exit)
                throws ReflectiveOperationException {
            this.profile = profile;
            this.contextField = contextField;
            contextField.setAccessible(true);
            this.talkerGetter = exactMethod(contextField.getType(), talkerGetter, String.class);
            this.resume = exactMethod(chat, resume, void.class);
            this.pause = exactMethod(chat, pause, void.class);
            this.exit = exactMethod(chat, exit, void.class);
            this.destroy = exactMethod(base, "onDestroy", void.class);
        }

        static Adapter resolve(Class<?> chat, Class<?> base) throws ReflectiveOperationException {
            if (!base.isAssignableFrom(chat)) return null;
            Field field = base.getDeclaredField("f");
            // Select by the context's exact APK signature, never by a getter name alone.
            // In 8.0.80 wk5.d.w() is a nickname/component getter, not the old username getter.
            switch (field.getType().getName()) {
                case "c95.d":
                    return new Adapter("8.0.71/3080", field, "w", chat, base, "C0", "B0", "U0");
                case "wk5.d":
                    return new Adapter("8.0.80/3220", field, "x", chat, base, "K0", "J0", "c1");
                default:
                    return null;
            }
        }

        private static Method exactMethod(Class<?> owner, String name, Class<?> result)
                throws ReflectiveOperationException {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers())) {
                throw new NoSuchMethodException();
            }
            method.setAccessible(true);
            return method;
        }
    }

    private ChatStateObserver(Class<?> fragmentClass, Adapter adapter, ClassLoader loader) {
        this.fragmentClass = fragmentClass;
        this.adapter = adapter;
        this.freeformManager = XposedHelpers.findClassIfExists("miui.app.MiuiFreeFormManager", loader);
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> chat = XposedHelpers.findClassIfExists("com.tencent.mm.ui.chatting.ChattingUIFragment", loader);
            Class<?> base = XposedHelpers.findClassIfExists("com.tencent.mm.ui.chatting.BaseChattingUIFragment", loader);
            if (chat == null || base == null) return;
            Adapter adapter = Adapter.resolve(chat, base);
            if (adapter == null) return;
            ChatStateObserver observer = new ChatStateObserver(chat, adapter, loader);
            XC_MethodHook sample = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    observer.track(param.thisObject);
                }
            };
            // Exact doResume/doPause and onExitEnd signatures also cover LauncherUI's custom paths.
            XposedBridge.hookMethod(adapter.resume, sample);
            XposedBridge.hookMethod(adapter.pause, sample);
            XC_MethodHook remove = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    observer.main.post(() -> {
                        observer.fragments.remove(param.thisObject);
                        observer.schedule();
                    });
                }
            };
            XposedBridge.hookMethod(adapter.exit, remove);
            XposedBridge.hookMethod(adapter.destroy, remove);
            Class<?> mm = XposedHelpers.findClassIfExists("com.tencent.mm.ui.MMFragment", loader);
            if (mm != null) for (String name : new String[]{
                    "onHiddenChanged", "onFragmentVisibilityChanged", "onVisibilityChanged"}) {
                XposedBridge.hookAllMethods(mm, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (chat.isInstance(param.thisObject)) observer.track(param.thisObject);
                    }
                });
            }
            XposedBridge.hookAllMethods(Activity.class, "onConfigurationChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    observer.main.post(observer::schedule);
                }
            });
            XposedBridge.log("[WeChatBubbles] read-only chat UI observer installed "
                    + BuildConfig.VERSION_NAME + " profile=" + adapter.profile);
        } catch (Throwable error) { SystemUiHook.logClass("chat observer install", error); }
    }

    private void track(Object fragment) {
        main.post(() -> {
            fragments.put(fragment, Boolean.TRUE);
            schedule();
        });
    }

    private void schedule() {
        settling = 3;
        main.removeCallbacks(publish);
        main.post(publish);
    }

    private void publishNow() {
        Set<String> visibleTokens = new LinkedHashSet<>();
        try {
            for (Object fragment : fragments.keySet().toArray()) {
                if (fragment == null || !fragmentClass.isInstance(fragment)) continue;
                Object rawActivity = XposedHelpers.callMethod(fragment, "getActivity");
                if (!(rawActivity instanceof Activity)) continue;
                Activity activity = (Activity) rawActivity;
                if (activity.isFinishing() || activity.isDestroyed()) continue;
                if (app == null) {
                    app = activity.getApplicationContext();
                    registerScreenReceiver();
                }
                PowerManager power = activity.getSystemService(PowerManager.class);
                if (power == null || !power.isInteractive()) continue;
                if (!NativeFreeformLauncher.isActivityInSmallWindow(activity)) continue;
                View chatView = (View) XposedHelpers.callMethod(fragment, "getView");
                // A paused but still visible freeform chat remains the same displayed conversation.
                boolean shown = chatView != null && chatView.isShown();
                if (!shown && !isPinned(activity)) continue;
                Object chatContext = adapter.contextField.get(fragment);
                if (chatContext == null) continue;
                // Verified memory-only chains: c95.d.w()->t()->contact.c1(), wk5.d.x()->u()->contact.a1().
                Object rawTalker = adapter.talkerGetter.invoke(chatContext);
                if (!(rawTalker instanceof String)) continue;
                String token = ChatIdentity.token(BubblePolicy.WECHAT, (String) rawTalker);
                if (!token.isEmpty()) visibleTokens.add(token);
                if (visibleTokens.size() >= 8) break;
            }
        } catch (Throwable error) {
            visibleTokens.clear();
            if (!errorLogged) {
                errorLogged = true;
                SystemUiHook.logClass("chat UI state", error);
            }
        }
        if (app != null) ChatStateClient.report(app, visibleTokens);
        main.removeCallbacks(publish);
        if (!visibleTokens.isEmpty() || settling-- > 0) main.postDelayed(publish, 1000L);
    }

    private boolean isPinned(Activity activity) {
        if (freeformManager == null) return false;
        try {
            Object stack = XposedHelpers.callStaticMethod(freeformManager,
                    "getFreeFormStackInfoByActivity", activity);
            return stack != null && XposedHelpers.getBooleanField(stack, "inPinMode");
        } catch (Throwable ignored) { return false; }
    }

    private void registerScreenReceiver() {
        if (app == null || screenReceiver) return;
        screenReceiver = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { schedule(); }
        }, filter, android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0);
    }
}
