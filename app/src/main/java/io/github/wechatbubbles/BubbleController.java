package io.github.wechatbubbles;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/** A bounded, in-memory presentation layer owned by SystemUI. All View mutations use main. */
final class BubbleController {
    private static final Uri CONFIG_URI = Uri.parse("content://io.github.wechatbubbles.config");
    private static final String CONFIG_CHANGED = "io.github.wechatbubbles.CONFIG_CHANGED";
    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final int GREEN = Color.rgb(13, 159, 104);
    private static final int DARK = Color.rgb(25, 31, 38);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread ioThread = new HandlerThread("WeChatBubblesConfig");
    private Handler io;
    private final WindowManager windows;
    private final KeyguardManager keyguard;
    private final PowerManager power;
    private final NativeFreeformLauncher launcher;
    private final LinkedHashMap<String, Conversation> conversations = new LinkedHashMap<>();
    private final java.util.Set<String> smallWindowChats = new java.util.HashSet<>();
    private long chatStateUpdatedElapsed;
    private final LastOpenedChat lastOpenedChat = new LastOpenedChat();
    private final Runnable checkLastOpenedChat = this::watchLastOpenedChat;
    private volatile Settings config = new Settings();
    private volatile boolean configLoaded;
    private volatile boolean available;
    private volatile boolean circuitOpen;
    private boolean started;
    private boolean railAttached;
    private boolean previewAttached;
    private boolean targetAttached;
    private boolean rightEdge;
    private boolean positionInitialized;
    private boolean positionSaved;
    private boolean dragging;
    private float positionYFraction;
    private LinearLayout rail;
    private LinearLayout preview;
    private TextView dragTarget;
    private WindowManager.LayoutParams railParams;
    private WindowManager.LayoutParams previewParams;
    private WindowManager.LayoutParams targetParams;
    private ValueAnimator snap;
    private String previewKey;
    private int failures;
    private int receivedCount;
    private int shownCount;
    private String lastEvent = "starting";
    private String lastError = "";
    private long lastConfigRead;
    private boolean reportQueued;
    private float density;
    private int screenWidth;
    private int screenHeight;
    private int topInset;
    private int bottomInset;

    private static final class Settings {
        boolean enabled = true;
        boolean preview = true;
        int previewSeconds = 6;
        int maxBubbles = 4;
    }

    private static final class Conversation {
        String id;
        String notificationKey;
        String conversationToken;
        String packageName;
        String title;
        String text;
        String signature;
        PendingIntent intent;
        Drawable avatar;
        int unread;
        long lastUpdated;
    }

    BubbleController(Context context, ClassLoader loader) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        power = context.getSystemService(PowerManager.class);
        launcher = new NativeFreeformLauncher(context, loader);
    }

    void start() {
        main.post(() -> safely("start", () -> {
            if (started) return;
            started = true;
            ioThread.start();
            io = new Handler(ioThread.getLooper());
            available = launcher.isAvailable() && windows != null;
            updateMetrics();
            IntentFilter filter = new IntentFilter(CONFIG_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction("android.intent.action.USER_SWITCHED");
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            IntentFilter packageChanges = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            packageChanges.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (intent.getData() != null
                            && BubblePolicy.MODULE.equals(intent.getData().getSchemeSpecificPart())
                            && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        safely("module removed", () -> {
                            configLoaded = false;
                            circuitOpen = true;
                            clearAll();
                        });
                    }
                }
            }, packageChanges, Context.RECEIVER_EXPORTED);
            if (Edition.HOOK_WECHAT) context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context unused, Intent state) {
                    safely("chat state", () -> updateChatState(
                            state.getStringArrayListExtra("conversation_tokens"),
                            state.getLongExtra("chat_state_updated_elapsed", 0L)));
                }
            }, new IntentFilter("io.github.wechatbubbles.CHAT_STATE_CHANGED"),
                    "io.github.wechatbubbles.permission.CHAT_STATE", main, Context.RECEIVER_EXPORTED);
            requestConfig(true);
            main.postDelayed(expireOld, 60_000L);
            report("ready", "");
        }));
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) {
            safely("system event", () -> {
                String action = intent.getAction();
                if (CONFIG_CHANGED.equals(action)) {
                    // A broadcast is only a wake-up. The authenticated provider supplies values.
                    requestConfig(false);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)
                        || "android.intent.action.USER_SWITCHED".equals(action)) {
                    clearAll();
                    report("locked_or_user_changed", "");
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    requestConfig(false);
                } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                    hidePreview();
                    updateMetrics();
                    if (railAttached) {
                        if (snap != null) { snap.cancel(); snap = null; }
                        restorePosition();
                        constrainRail();
                        windows.updateViewLayout(rail, railParams);
                    }
                } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    hidePreview();
                }
            });
        }
    };

    private void requestConfig(boolean force) {
        if (io == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastConfigRead < 350) {
            main.removeCallbacks(refreshConfig);
            main.postDelayed(refreshConfig, 400);
            return;
        }
        lastConfigRead = now;
        io.post(() -> {
            try {
                Bundle result = context.getContentResolver().call(CONFIG_URI, "get_config", null, null);
                if (result == null) return;
                Settings settings = new Settings();
                settings.enabled = result.getBoolean("enabled", true);
                settings.preview = result.getBoolean("preview", true);
                settings.previewSeconds = BubblePolicy.clamp(result.getInt("preview_seconds", 6), 2, 15);
                settings.maxBubbles = BubblePolicy.clamp(result.getInt("max_bubbles", 4), 1, 5);
                main.post(() -> safely("config", () -> {
                    config = settings;
                    if (!positionInitialized) {
                        positionInitialized = true;
                        positionYFraction = result.getFloat("bubble_y_fraction", Float.NaN);
                        positionSaved = result.getBoolean("bubble_position_saved", false)
                                && Float.isFinite(positionYFraction);
                        rightEdge = positionSaved && result.getBoolean("bubble_right_edge", false);
                    }
                    configLoaded = true;
                    updateChatState(result.getStringArrayList("conversation_tokens"),
                            result.getLong("chat_state_updated_elapsed", 0L));
                    if (!settings.enabled) clearAll();
                    else {
                        trimConversations();
                        if (!settings.preview) hidePreview();
                        if (railAttached) rebuildRail();
                    }
                    report(settings.enabled ? "ready" : "disabled", "");
                }));
            } catch (Throwable error) {
                SystemUiHook.logClass("configuration provider", error);
                main.postDelayed(refreshConfig, 10_000L);
            }
        });
    }

    private final Runnable refreshConfig = () -> requestConfig(true);

    void onNotification(StatusBarNotification sbn, NotificationListenerService.RankingMap ranks) {
        // Avoid holding unrelated packages' Notification objects or personal data in our queue.
        if (sbn == null || !BubblePolicy.acceptsPackage(sbn.getPackageName(),
                sbn.getNotification().getChannelId())) return;
        main.post(() -> safely("posted", () -> present(sbn, ranks, null)));
    }

    boolean presentFromEntry(Object entry) {
        if (Looper.myLooper() != Looper.getMainLooper() || entry == null) return false;
        try {
            Object raw;
            try { raw = XposedHelpers.getObjectField(entry, "mSbn"); }
            catch (Throwable ignored) { raw = XposedHelpers.callMethod(entry, "getSbn"); }
            if (!(raw instanceof StatusBarNotification)) return false;
            NotificationListenerService.Ranking ranking = null;
            try {
                Object r;
                try { r = XposedHelpers.getObjectField(entry, "mRanking"); }
                catch (Throwable ignored) { r = XposedHelpers.callMethod(entry, "getRanking"); }
                if (r instanceof NotificationListenerService.Ranking) ranking = (NotificationListenerService.Ranking) r;
            } catch (Throwable ignored) { /* Listener callback has the complete RankingMap. */ }
            return present((StatusBarNotification) raw, null, ranking);
        } catch (Throwable error) {
            SystemUiHook.logClass("entry", error);
            return false;
        }
    }

    private boolean canPresent(StatusBarNotification sbn, NotificationListenerService.Ranking ranking) {
        if (!configLoaded || !config.enabled || !available || circuitOpen || sbn == null
                || sbn.getUserId() != 0 || !isUnlocked()) return false;
        // SystemUI lives through user changes; do not leak another user's notifications.
        try {
            Object currentUser = XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
            if (currentUser instanceof Integer && (Integer) currentUser != 0) return false;
        } catch (Throwable ignored) { return false; }
        Notification n = sbn.getNotification();
        if (!BubblePolicy.acceptsPackage(sbn.getPackageName(), n.getChannelId())) return false;
        if (n.visibility == Notification.VISIBILITY_SECRET
                || (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY)) != 0
                || n.contentIntent == null || !n.contentIntent.isActivity()) return false;
        if (ranking == null) return false;
        {
            if (!ranking.matchesInterruptionFilter() || ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
                return false;
            }
            int effects = ranking.getSuppressedVisualEffects();
            if ((effects & (NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
                    | NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)) != 0) return false;
        }
        return true;
    }

    private boolean present(StatusBarNotification sbn, NotificationListenerService.RankingMap ranks,
                            NotificationListenerService.Ranking knownRanking) {
        if (!BubblePolicy.acceptsPackage(sbn.getPackageName(), sbn.getNotification().getChannelId())) return false;
        NotificationListenerService.Ranking ranking = knownRanking;
        if (ranking == null && ranks != null) {
            NotificationListenerService.Ranking candidate = new NotificationListenerService.Ranking();
            if (ranks.getRanking(sbn.getKey(), candidate)) ranking = candidate;
        }
        if (!canPresent(sbn, ranking)) return false;
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        String title = BubblePolicy.clip(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE), 96);
        CharSequence body = extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(body) && extras != null) body = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(body) && extras != null) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) body = lines[lines.length - 1];
        }
        try {
            if (extras != null) {
                CharSequence conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
                if (!TextUtils.isEmpty(conversationTitle)) title = BubblePolicy.clip(conversationTitle, 96);
                android.os.Parcelable[] messageData = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                List<Notification.MessagingStyle.Message> messages = messageData == null ? java.util.Collections.emptyList()
                        : Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageData);
                if (!messages.isEmpty()) {
                    Notification.MessagingStyle.Message last = messages.get(messages.size() - 1);
                    if (!TextUtils.isEmpty(last.getText())) body = last.getText();
                    if (title.isEmpty() && last.getSenderPerson() != null) {
                        title = BubblePolicy.clip(last.getSenderPerson().getName(), 96);
                    }
                }
            }
        } catch (Throwable ignored) { /* Keep existing notification preview exactly as supplied. */ }
        String text = BubblePolicy.clip(body, 1200);
        if (text.isEmpty()) return false;
        if (title.isEmpty()) title = BubblePolicy.WECHAT.equals(sbn.getPackageName()) ? "微信" : "气泡测试";
        String token = ConversationIdentity.notificationToken(sbn.getPackageName(), n.contentIntent);
        String id = token.isEmpty()
                ? BubblePolicy.conversationKey(sbn.getPackageName(), n.getShortcutId(), title, sbn.getKey())
                : sbn.getPackageName() + "|chat|" + token;
        // Prune reused routes even when the incoming conversation will be suppressed.
        boolean routeReplaced = conversations.entrySet().removeIf(item -> !id.equals(item.getKey())
                && n.contentIntent.equals(item.getValue().intent));
        if (routeReplaced && previewKey != null && !conversations.containsKey(previewKey)) hidePreview();
        if (sameChatInSmallWindow(token, sbn.getPackageName())) {
            boolean removed = conversations.remove(id) != null;
            if (removed || routeReplaced) rebuildRail();
            if (id.equals(previewKey)) hidePreview();
            report("same_chat_in_small_window", "");
            return false;
        }
        String signature = sbn.getKey() + "|" + n.when + "|" + title + "|" + text;
        Conversation old = conversations.get(id);
        if (old != null && BubblePolicy.isDuplicate(old.signature, signature)) {
            old.intent = n.contentIntent;
            old.notificationKey = sbn.getKey();
            if (routeReplaced) rebuildRail();
            return railAttached;
        }
        receivedCount++;
        Conversation c = old == null ? new Conversation() : old;
        if (old != null && !title.equals(old.title)) {
            c.unread = 0;
            c.avatar = null;
        }
        c.id = id;
        c.conversationToken = token;
        c.notificationKey = sbn.getKey();
        c.packageName = sbn.getPackageName();
        c.title = title;
        c.text = text;
        c.signature = signature;
        c.intent = n.contentIntent;
        c.lastUpdated = SystemClock.elapsedRealtime();
        c.unread = Math.min(99, c.unread + 1);
        try {
            if (n.getLargeIcon() != null) c.avatar = n.getLargeIcon().loadDrawable(context);
            if (c.avatar == null) c.avatar = context.getPackageManager().getApplicationIcon(c.packageName);
        } catch (Throwable ignored) { /* BubbleView has its own drawable fallback. */ }
        conversations.remove(id);
        conversations.put(id, c);
        trimConversations();
        rebuildRail();
        if (railAttached) {
            shownCount++;
            showPreview(c);
            report("bubble_shown", "");
            return true;
        }
        return false;
    }

    void onRemoved(StatusBarNotification sbn, int reason) {
        if (sbn == null || !BubblePolicy.acceptsPackage(sbn.getPackageName(),
                sbn.getNotification().getChannelId())) return;
        main.post(() -> safely("removed", () -> {
            if (BubblePolicy.forgetOnRemoval(reason)) {
                Iterator<Map.Entry<String, Conversation>> it = conversations.entrySet().iterator();
                boolean changed = false;
                while (it.hasNext()) {
                    Conversation c = it.next().getValue();
                    if (sbn.getKey().equals(c.notificationKey)) {
                        if (c.id.equals(previewKey)) hidePreview();
                        it.remove();
                        changed = true;
                    }
                }
                if (changed) rebuildRail();
            } else if (previewKey != null) {
                Conversation c = conversations.get(previewKey);
                if (c != null && sbn.getKey().equals(c.notificationKey)) hidePreview();
            }
        }));
    }

    private boolean sameChatInSmallWindow(String token, String packageName) {
        if (!Edition.HOOK_WECHAT) {
            if (!BubblePolicy.WECHAT.equals(packageName) || !lastOpenedChat.hasConversation()) return false;
            return lastOpenedChat.shouldSuppress(token, launcher.hasActiveSmallWindow(packageName),
                    SystemClock.elapsedRealtime());
        }
        return BubblePolicy.matchesActiveChat(token, smallWindowChats,
                chatStateUpdatedElapsed, SystemClock.elapsedRealtime())
                && launcher.hasActiveSmallWindow(packageName);
    }

    private void updateChatState(ArrayList<String> tokens, long updated) {
        if (!Edition.HOOK_WECHAT) return;
        if (updated < chatStateUpdatedElapsed) return;
        long age = SystemClock.elapsedRealtime() - updated;
        smallWindowChats.clear();
        chatStateUpdatedElapsed = updated;
        if (tokens != null && tokens.size() <= 8 && updated > 0 && age >= 0 && age <= 6000L) {
            for (String token : tokens) {
                if (token != null && token.matches("[0-9a-f]{64}")) smallWindowChats.add(token);
            }
        }
        boolean removed = false;
        Iterator<Map.Entry<String, Conversation>> iterator = conversations.entrySet().iterator();
        while (iterator.hasNext()) {
            Conversation c = iterator.next().getValue();
            if (sameChatInSmallWindow(c.conversationToken, c.packageName)) {
                if (c.id.equals(previewKey)) hidePreview();
                iterator.remove();
                removed = true;
            }
        }
        if (removed) rebuildRail();
    }

    private void watchLastOpenedChat() {
        if (Edition.HOOK_WECHAT || !lastOpenedChat.hasConversation()) return;
        long now = SystemClock.elapsedRealtime();
        boolean windowOpen = launcher.hasActiveSmallWindow(BubblePolicy.WECHAT);
        if (!lastOpenedChat.observeWindow(windowOpen, now)) return;
        boolean removed = false;
        Iterator<Map.Entry<String, Conversation>> iterator = conversations.entrySet().iterator();
        while (iterator.hasNext()) {
            Conversation c = iterator.next().getValue();
            if (BubblePolicy.WECHAT.equals(c.packageName)
                    && lastOpenedChat.shouldSuppress(c.conversationToken, windowOpen, now)) {
                if (c.id.equals(previewKey)) hidePreview();
                iterator.remove();
                removed = true;
            }
        }
        if (removed) rebuildRail();
        main.postDelayed(checkLastOpenedChat, 750L);
    }

    private boolean isUnlocked() {
        return keyguard != null && power != null && !keyguard.isKeyguardLocked() && power.isInteractive();
    }

    private void updateMetrics() {
        density = context.getResources().getDisplayMetrics().density;
        android.view.WindowMetrics metrics = windows.getCurrentWindowMetrics();
        screenWidth = metrics.getBounds().width();
        screenHeight = metrics.getBounds().height();
        android.graphics.Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        topInset = bars.top + dp(8);
        bottomInset = bars.bottom + dp(8);
    }

    private int dp(float value) { return Math.round(value * density); }

    private WindowManager.LayoutParams params(int width, int height) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.setTitle("WeChatBubbles");
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        p.setFitInsetsTypes(0);
        return p;
    }

    private void trimConversations() {
        while (conversations.size() > config.maxBubbles) {
            Iterator<String> ids = conversations.keySet().iterator();
            String id = ids.next();
            if (id.equals(previewKey)) hidePreview();
            ids.remove();
        }
    }

    private void rebuildRail() {
        if (conversations.isEmpty() || !config.enabled || !isUnlocked()) {
            detachRail();
            hidePreview();
            return;
        }
        updateMetrics();
        if (rail == null) {
            rail = new LinearLayout(context);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            rail.setPadding(dp(2), dp(3), dp(2), dp(3));
            rail.setClipChildren(false);
            rail.setClipToPadding(false);
            railParams = params(dp(62), dp(62));
            railParams.x = dp(3);
            railParams.y = topInset + dp(104);
        }
        rail.removeAllViews();
        List<Conversation> reversed = new ArrayList<>(conversations.values());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            Conversation c = reversed.get(i);
            BubbleView icon = new BubbleView(context, c.avatar, c.unread, BubblePolicy.MODULE.equals(c.packageName));
            icon.setContentDescription(c.title + "，" + c.unread + " 条未读，点按小窗打开，长按关闭");
            icon.setOnClickListener(view -> safely("bubble click", () -> openConversation(c.id)));
            icon.setOnLongClickListener(view -> {
                safely("bubble long click", () -> dismiss(c.id));
                return true;
            });
            icon.setOnTouchListener(new DragTouch(c.id));
            rail.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(60)));
        }
        railParams.height = dp(6 + 60 * conversations.size());
        if (!dragging) {
            if (snap != null) { snap.cancel(); snap = null; }
            restorePosition();
        }
        constrainRail();
        if (railAttached) windows.updateViewLayout(rail, railParams);
        else {
            windows.addView(rail, railParams);
            railAttached = true;
        }
    }

    private void restorePosition() {
        if (!positionSaved || railParams == null) return;
        railParams.x = BubblePosition.edgeX(rightEdge, screenWidth, railParams.width, dp(2));
        railParams.y = BubblePosition.restore(positionYFraction, topInset,
                screenHeight - bottomInset, dp(66), railParams.height);
    }

    private void savePosition() {
        if (railParams == null || io == null) return;
        positionInitialized = true;
        positionSaved = true;
        positionYFraction = BubblePosition.fraction(railParams.y, topInset,
                screenHeight - bottomInset, dp(66));
        Bundle position = new Bundle();
        position.putBoolean("right_edge", rightEdge);
        position.putFloat("y_fraction", positionYFraction);
        // Only the final drag placement crosses Binder. Disk writes never run on SystemUI main.
        io.post(() -> {
            try {
                Bundle result = context.getContentResolver().call(CONFIG_URI,
                        ConfigProvider.METHOD_REPORT_BUBBLE_POSITION, null, position);
                if (result == null || !result.getBoolean("ok", false)) {
                    throw new IllegalStateException("Position was not saved");
                }
            } catch (Throwable error) {
                SystemUiHook.logClass("save position", error);
            }
        });
    }

    private void constrainRail() {
        railParams.x = BubblePolicy.clamp(railParams.x, 0, screenWidth - railParams.width);
        railParams.y = BubblePolicy.clamp(railParams.y, topInset,
                screenHeight - bottomInset - railParams.height);
    }

    private void detachRail() {
        dragging = false;
        if (snap != null) { snap.cancel(); snap = null; }
        if (railAttached && rail != null) {
            railAttached = false;
            try { windows.removeViewImmediate(rail); }
            catch (Throwable error) { SystemUiHook.logClass("detach rail", error); }
        }
        hideTarget();
    }

    private void showPreview(Conversation c) {
        hidePreview();
        if (!config.preview || !railAttached || !isUnlocked()) return;
        previewKey = c.id;
        preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(15), dp(11), dp(15), dp(12));
        preview.setBackground(roundRect(Color.rgb(255, 255, 255), 17));
        preview.setElevation(dp(5));
        TextView title = textView(c.title, 13, DARK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        preview.addView(title);
        TextView content = textView(c.text, 14, Color.rgb(65, 72, 80));
        content.setMaxLines(3);
        content.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2);
        body.topMargin = dp(4);
        preview.addView(content, body);
        preview.setContentDescription(c.title + "：" + c.text + "，点按小窗回复");
        preview.setOnClickListener(view -> safely("preview click", () -> openConversation(c.id)));
        int maxPreviewWidth = Math.max(1, Math.min(dp(264), screenWidth - dp(84)));
        int previewWidth = PreviewSizing.measureWidth(preview, dp(72), maxPreviewWidth);
        previewParams = params(previewWidth, WindowManager.LayoutParams.WRAP_CONTENT);
        previewParams.setTitle("WeChatBubblesPreview");
        previewParams.x = rightEdge ? railParams.x - previewParams.width - dp(5)
                : railParams.x + railParams.width + dp(5);
        previewParams.x = BubblePolicy.clamp(previewParams.x, dp(4), screenWidth - previewParams.width - dp(4));
        previewParams.y = BubblePolicy.clamp(railParams.y + dp(5), topInset,
                screenHeight - bottomInset - preview.getMeasuredHeight());
        windows.addView(preview, previewParams);
        previewAttached = true;
        preview.setAlpha(0f);
        preview.setTranslationX(rightEdge ? dp(7) : -dp(7));
        preview.animate().alpha(1f).translationX(0f).setDuration(180).start();
        main.postDelayed(hidePreviewTask, config.previewSeconds * 1000L);
    }

    private final Runnable hidePreviewTask = () -> safely("preview timeout", this::hidePreview);

    private void hidePreview() {
        main.removeCallbacks(hidePreviewTask);
        previewKey = null;
        if (previewAttached && preview != null) {
            previewAttached = false;
            try {
                preview.animate().cancel();
                windows.removeViewImmediate(preview);
            } catch (Throwable error) { SystemUiHook.logClass("hide preview", error); }
        }
        preview = null;
    }

    private TextView textView(String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private void openConversation(String id) {
        Conversation c = conversations.get(id);
        if (c == null || !isUnlocked()) { clearAll(); return; }
        hidePreview();
        try {
            String currentRoute = ConversationIdentity.notificationToken(c.packageName, c.intent);
            if (c.conversationToken != null && !c.conversationToken.isEmpty()
                    && !c.conversationToken.equals(currentRoute)) {
                conversations.remove(id);
                rebuildRail();
                Toast.makeText(context, "这条通知已更新，请点击最新气泡", Toast.LENGTH_SHORT).show();
                report("notification_route_updated", "");
                return;
            }
            launcher.launch(c.packageName, c.intent);
            if (!Edition.HOOK_WECHAT && BubblePolicy.WECHAT.equals(c.packageName)) {
                lastOpenedChat.opened(c.conversationToken, SystemClock.elapsedRealtime());
                main.removeCallbacks(checkLastOpenedChat);
                if (lastOpenedChat.hasConversation()) main.postDelayed(checkLastOpenedChat, 500L);
            }
            conversations.remove(id);
            rebuildRail();
            report("small_window_requested", "");
        } catch (PendingIntent.CanceledException expired) {
            conversations.remove(id);
            rebuildRail();
            Toast.makeText(context, "原通知已失效，请等待新的微信消息", Toast.LENGTH_SHORT).show();
            report("intent_expired", "CanceledException");
        } catch (Throwable error) {
            SystemUiHook.logClass("native window", error);
            Toast.makeText(context, "系统未允许此通知的小窗启动；原通知仍在通知栏", Toast.LENGTH_LONG).show();
            report("small_window_failed", error.getClass().getSimpleName());
        }
    }

    private final class DragTouch implements View.OnTouchListener {
        private final String id;
        private float downX, downY;
        private int initialX, initialY;
        private boolean moved, longPressed;
        private long downAt;
        private final Runnable longClick;
        DragTouch(String id) {
            this.id = id;
            this.longClick = () -> safely("dismiss", () -> {
                if (!moved) {
                    longPressed = true;
                    dismiss(this.id);
                }
            });
        }
        @Override public boolean onTouch(View view, MotionEvent event) {
            try { return handle(view, event); }
            catch (Throwable error) { fail("touch", error); return true; }
        }
        private boolean handle(View view, MotionEvent event) {
            if (railParams == null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (snap != null) snap.cancel();
                    downX = event.getRawX(); downY = event.getRawY();
                    initialX = railParams.x; initialY = railParams.y;
                    moved = false; longPressed = false;
                    downAt = SystemClock.uptimeMillis();
                    main.postDelayed(longClick, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > dp(7)) {
                        moved = true;
                        main.removeCallbacks(longClick);
                        hidePreview();
                        showTarget();
                    }
                    if (moved && railAttached) {
                        railParams.x = initialX + Math.round(dx);
                        railParams.y = initialY + Math.round(dy);
                        constrainRail();
                        windows.updateViewLayout(rail, railParams);
                        if (dragTarget != null) dragTarget.setAlpha(overTarget(event) ? 1f : .65f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    dragging = false;
                    main.removeCallbacks(longClick);
                    boolean remove = moved && overTarget(event);
                    hideTarget();
                    if (remove) dismiss(id);
                    else if (moved) snapToEdge();
                    else if (!longPressed && SystemClock.uptimeMillis() - downAt < ViewConfiguration.getLongPressTimeout()) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    main.removeCallbacks(longClick);
                    hideTarget();
                    if (moved) snapToEdge();
                    return true;
                default: return true;
            }
        }
    }

    private boolean overTarget(MotionEvent event) {
        if (!targetAttached || targetParams == null) return false;
        float x = targetParams.x + targetParams.width / 2f;
        float y = targetParams.y + targetParams.height / 2f;
        return Math.hypot(event.getRawX() - x, event.getRawY() - y) <= dp(54);
    }

    private void showTarget() {
        if (targetAttached) return;
        dragTarget = textView("×", 34, Color.WHITE);
        dragTarget.setGravity(Gravity.CENTER);
        dragTarget.setBackground(roundRect(Color.rgb(190, 53, 63), 32));
        dragTarget.setContentDescription("拖到这里关闭气泡");
        targetParams = params(dp(64), dp(64));
        targetParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        targetParams.setTitle("WeChatBubblesDismiss");
        targetParams.x = (screenWidth - dp(64)) / 2;
        targetParams.y = screenHeight - bottomInset - dp(94);
        windows.addView(dragTarget, targetParams);
        targetAttached = true;
    }

    private void hideTarget() {
        if (targetAttached && dragTarget != null) {
            targetAttached = false;
            try { windows.removeViewImmediate(dragTarget); }
            catch (Throwable error) { SystemUiHook.logClass("hide target", error); }
        }
        dragTarget = null;
    }

    private void dismiss(String id) {
        conversations.remove(id);
        hidePreview();
        hideTarget();
        rebuildRail();
        report("bubble_dismissed", "");
    }

    private void snapToEdge() {
        if (!railAttached || railParams == null) return;
        rightEdge = railParams.x + railParams.width / 2 > screenWidth / 2;
        int start = railParams.x;
        int end = BubblePosition.edgeX(rightEdge, screenWidth, railParams.width, dp(2));
        savePosition();
        snap = ValueAnimator.ofInt(start, end);
        snap.setDuration(170);
        snap.addUpdateListener(animator -> safely("snap", () -> {
            if (!railAttached) return;
            railParams.x = (Integer) animator.getAnimatedValue();
            windows.updateViewLayout(rail, railParams);
        }));
        snap.start();
    }

    private final Runnable expireOld = new Runnable() {
        @Override public void run() {
            safely("expiry", () -> {
                long now = SystemClock.elapsedRealtime();
                boolean changed = false;
                Iterator<Conversation> it = conversations.values().iterator();
                while (it.hasNext()) {
                    if (now - it.next().lastUpdated > STALE_MS) { it.remove(); changed = true; }
                }
                if (changed) { hidePreview(); rebuildRail(); }
                if (!isUnlocked() && !conversations.isEmpty()) clearAll();
                report(lastEvent, lastError);
            });
            main.postDelayed(this, 60_000L);
        }
    };

    private void clearAll() {
        lastOpenedChat.clear();
        main.removeCallbacks(checkLastOpenedChat);
        conversations.clear();
        hidePreview();
        detachRail();
    }

    private void report(String event, String error) {
        lastEvent = event;
        lastError = error;
        if (io == null || reportQueued) return;
        reportQueued = true;
        main.postDelayed(() -> {
            reportQueued = false;
            Bundle state = new Bundle();
            state.putBoolean("hook_active", true);
            state.putString("hook_version", BuildConfig.VERSION_NAME);
            state.putBoolean("freeform_available", available && !circuitOpen);
            state.putInt("received_count", receivedCount);
            state.putInt("shown_count", shownCount);
            state.putString("last_event", lastEvent);
            state.putString("last_error", lastError);
            state.putLong("updated_at", System.currentTimeMillis());
            io.post(() -> {
                try { context.getContentResolver().call(CONFIG_URI, "report_status", null, state); }
                catch (Throwable error1) { SystemUiHook.logClass("status provider", error1); }
            });
        }, 400);
    }

    private interface SafeAction { void run() throws Exception; }
    private void safely(String operation, SafeAction action) {
        try { action.run(); }
        catch (Throwable error) { fail(operation, error); }
    }

    private void fail(String operation, Throwable error) {
        SystemUiHook.logClass(operation, error);
        failures++;
        if (failures >= 3) {
            circuitOpen = true;
            try { clearAll(); } catch (Throwable ignored) { /* Never crash the SystemUI host. */ }
        }
        report("presentation_error", error.getClass().getSimpleName());
    }
}
