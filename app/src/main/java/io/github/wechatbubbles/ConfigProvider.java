package io.github.wechatbubbles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Configuration, bounded diagnostics, and short-lived in-memory conversation-token bridge.
 * No notification text, conversation identifiers, or message history is persisted.
 * Manifest must register this provider with authority io.github.wechatbubbles.config
 * and android:exported="true".
 */
public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.wechatbubbles.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String METHOD_REPORT_STATUS = "report_status";
    public static final String METHOD_GET_STATUS = "get_status";
    public static final String METHOD_REPORT_CHAT_STATE = "report_chat_state";
    public static final String METHOD_REPORT_BUBBLE_POSITION = "report_bubble_position";
    private static final String PREF_BUBBLE_POSITION_SAVED = "bubble_position_saved";
    private static final String PREF_BUBBLE_RIGHT_EDGE = "bubble_right_edge";
    private static final String PREF_BUBBLE_Y_FRACTION = "bubble_y_fraction";

    private static final String CHAT_STATE_CHANGED = "io.github.wechatbubbles.CHAT_STATE_CHANGED";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final long CHAT_STATE_TTL_MILLIS = 6_000L;
    private static final int MAX_CONVERSATION_TOKENS = 8;
    private static final int PER_USER_UID_RANGE = 100_000;

    private final Object chatStateLock = new Object();
    private volatile ArrayList<String> conversationTokens = new ArrayList<>();
    private volatile long chatStateUpdatedElapsed;

    private static final String PREFS_NAME = "config";
    private static final String STATUS_PREFIX = "status_";
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;

    private static final String[] STATUS_KEYS = {
            "hook_active",
            "hook_version",
            "freeform_available",
            "received_count",
            "shown_count",
            "last_event",
            "last_error",
            "updated_at"
    };

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    private Context preferencesContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider is not attached to a context");
        }
        return context.createDeviceProtectedStorageContext();
    }

    private android.content.SharedPreferences preferences() {
        return preferencesContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private int mainUserWechatUid() {
        return Edition.HOOK_WECHAT ? mainUserPackageUid(WECHAT_PACKAGE, 0) : -1;
    }

    private int mainUserPackageUid(String packageName, int flags) {
        Context context = getContext();
        if (context == null) return -1;
        try {
            int uid = context.getPackageManager().getPackageUid(packageName, flags);
            return uid >= 0 && uid / PER_USER_UID_RANGE == 0 ? uid : -1;
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            return -1;
        }
    }

    private void enforceOwnerOrSystemCaller() {
        final int callerUid = Binder.getCallingUid();
        final Context context = getContext();
        if (context == null) {
            throw new SecurityException("Provider is not available");
        }
        final int wechatUid = mainUserWechatUid();
        if (callerUid == wechatUid) {
            throw new SecurityException("WeChat UID may only report chat state");
        }
        final int ownUid = context.getApplicationInfo().uid;
        if (callerUid == ownUid || callerUid == Process.SYSTEM_UID) return;
        // ColorOS can run SystemUI under android.uid.systemui instead of android.uid.system.
        int systemUiUid = mainUserPackageUid(SYSTEM_UI_PACKAGE, PackageManager.MATCH_SYSTEM_ONLY);
        if (systemUiUid >= 0 && callerUid == systemUiUid) return;
        throw new SecurityException("Caller is not authorized");
    }

    private void enforceChatStateCaller() {
        final int callerUid = Binder.getCallingUid();
        final Context context = getContext();
        if (context == null) {
            throw new SecurityException("Provider is not available");
        }
        final int ownUid = context.getApplicationInfo().uid;
        final int wechatUid = mainUserWechatUid();
        if (callerUid != ownUid && callerUid != wechatUid) {
            throw new SecurityException("Caller is not authorized to report chat state");
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_REPORT_CHAT_STATE.equals(method)) {
            if (!Edition.HOOK_WECHAT) {
                throw new SecurityException("Chat-state reporting is disabled in this edition");
            }
            enforceChatStateCaller();
            return reportChatState(extras);
        }
        enforceOwnerOrSystemCaller();
        if (METHOD_GET_CONFIG.equals(method)) {
            return readConfig();
        }
        if (METHOD_REPORT_STATUS.equals(method)) {
            return writeStatus(extras);
        }
        if (METHOD_GET_STATUS.equals(method)) {
            return readStatus();
        }
        if (METHOD_REPORT_BUBBLE_POSITION.equals(method)) {
            return writeBubblePosition(extras);
        }
        throw new IllegalArgumentException("Unknown provider method");
    }

    private Bundle readConfig() {
        android.content.SharedPreferences prefs = preferences();
        Bundle result = new Bundle();
        result.putBoolean("enabled", prefs.getBoolean("enabled", true));
        result.putBoolean("preview", prefs.getBoolean("preview", true));
        result.putInt("preview_seconds", 6);
        result.putInt("max_bubbles", 4);
        result.putBoolean("bubble_position_saved", prefs.getBoolean(PREF_BUBBLE_POSITION_SAVED, false));
        result.putBoolean("bubble_right_edge", prefs.getBoolean(PREF_BUBBLE_RIGHT_EDGE, false));
        result.putFloat("bubble_y_fraction", prefs.getFloat(PREF_BUBBLE_Y_FRACTION, 0.25f));
        synchronized (chatStateLock) {
            long updatedElapsed = chatStateUpdatedElapsed;
            ArrayList<String> currentTokens = new ArrayList<>(conversationTokens);
            long age = SystemClock.elapsedRealtime() - updatedElapsed;
            if (updatedElapsed <= 0L || age < 0L || age > CHAT_STATE_TTL_MILLIS) {
                currentTokens.clear();
                conversationTokens = new ArrayList<>();
            }
            result.putStringArrayList("conversation_tokens", currentTokens);
            result.putLong("chat_state_updated_elapsed", updatedElapsed);
        }
        return result;
    }

    private Bundle reportChatState(Bundle extras) {
        if (extras == null || !extras.containsKey("conversation_tokens")) {
            throw new IllegalArgumentException("conversation_tokens is required");
        }
        final ArrayList<?> incoming;
        try {
            incoming = extras.getStringArrayList("conversation_tokens");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("conversation_tokens must be a string list");
        }
        if (incoming == null || incoming.size() > MAX_CONVERSATION_TOKENS) {
            throw new IllegalArgumentException("Invalid conversation_tokens size");
        }

        ArrayList<String> validated = new ArrayList<>(incoming.size());
        HashSet<String> unique = new HashSet<>();
        for (Object value : incoming) {
            if (!(value instanceof String token)
                    || !isConversationToken(token) || !unique.add(token)) {
                throw new IllegalArgumentException("Invalid conversation token");
            }
            validated.add(token);
        }

        final long updatedElapsed = SystemClock.elapsedRealtime();
        final ArrayList<String> snapshot = new ArrayList<>(validated);
        synchronized (chatStateLock) {
            conversationTokens = snapshot;
            chatStateUpdatedElapsed = updatedElapsed;
        }

        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider is not attached to a context");
        }
        Intent changed = new Intent(CHAT_STATE_CHANGED)
                .setPackage("com.android.systemui")
                .putStringArrayListExtra("conversation_tokens", new ArrayList<>(validated))
                .putExtra("chat_state_updated_elapsed", updatedElapsed);
        context.sendBroadcast(changed);

        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        result.putLong("chat_state_updated_elapsed", updatedElapsed);
        return result;
    }

    private boolean isConversationToken(String token) {
        if (token == null || token.length() != 64) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private Bundle writeStatus(Bundle incoming) {
        android.content.SharedPreferences prefs = preferences();
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (incoming != null) {
            if (incoming.containsKey("hook_active")) {
                editor.putBoolean(STATUS_PREFIX + "hook_active",
                        incoming.getBoolean("hook_active", false));
            }
            if (incoming.containsKey("hook_version")) {
                editor.putString(STATUS_PREFIX + "hook_version",
                        boundedString(incoming.getString("hook_version")));
            }
            if (incoming.containsKey("freeform_available")) {
                editor.putBoolean(STATUS_PREFIX + "freeform_available",
                        incoming.getBoolean("freeform_available", false));
            }
            if (incoming.containsKey("received_count")) {
                editor.putInt(STATUS_PREFIX + "received_count",
                        Math.max(0, incoming.getInt("received_count", 0)));
            }
            if (incoming.containsKey("shown_count")) {
                editor.putInt(STATUS_PREFIX + "shown_count",
                        Math.max(0, incoming.getInt("shown_count", 0)));
            }
            if (incoming.containsKey("last_event")) {
                editor.putString(STATUS_PREFIX + "last_event",
                        boundedString(incoming.getString("last_event")));
            }
            if (incoming.containsKey("last_error")) {
                editor.putString(STATUS_PREFIX + "last_error",
                        boundedString(incoming.getString("last_error")));
            }
            if (incoming.containsKey("updated_at")) {
                editor.putLong(STATUS_PREFIX + "updated_at",
                        Math.max(0L, incoming.getLong("updated_at", 0L)));
            }
        }
        if (incoming == null || !incoming.containsKey("updated_at")) {
            editor.putLong(STATUS_PREFIX + "updated_at", System.currentTimeMillis());
        }
        boolean saved = editor.commit();
        Bundle result = new Bundle();
        result.putBoolean("ok", saved);
        return result;
    }

    private Bundle writeBubblePosition(Bundle incoming) {
        if (incoming == null || !incoming.containsKey("right_edge")
                || !incoming.containsKey("y_fraction")) {
            throw new IllegalArgumentException("Bubble position fields are required");
        }
        float fraction = incoming.getFloat("y_fraction", Float.NaN);
        if (Float.isNaN(fraction) || Float.isInfinite(fraction)) {
            throw new IllegalArgumentException("Invalid bubble position");
        }
        fraction = Math.max(0f, Math.min(1f, fraction));
        boolean saved = preferences().edit()
                .putBoolean(PREF_BUBBLE_POSITION_SAVED, true)
                .putBoolean(PREF_BUBBLE_RIGHT_EDGE, incoming.getBoolean("right_edge", false))
                .putFloat(PREF_BUBBLE_Y_FRACTION, fraction)
                .commit();
        Bundle result = new Bundle();
        result.putBoolean("ok", saved);
        return result;
    }

    private String boundedString(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= MAX_DIAGNOSTIC_LENGTH
                ? clean : clean.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    private Bundle readStatus() {
        android.content.SharedPreferences prefs = preferences();
        Bundle result = new Bundle();
        result.putBoolean("hook_active", prefs.getBoolean(STATUS_PREFIX + "hook_active", false));
        result.putString("hook_version", prefs.getString(STATUS_PREFIX + "hook_version", ""));
        result.putBoolean("freeform_available",
                prefs.getBoolean(STATUS_PREFIX + "freeform_available", false));
        result.putInt("received_count", prefs.getInt(STATUS_PREFIX + "received_count", 0));
        result.putInt("shown_count", prefs.getInt(STATUS_PREFIX + "shown_count", 0));
        result.putString("last_event", prefs.getString(STATUS_PREFIX + "last_event", ""));
        result.putString("last_error", prefs.getString(STATUS_PREFIX + "last_error", ""));
        result.putLong("updated_at", prefs.getLong(STATUS_PREFIX + "updated_at", 0L));
        synchronized (chatStateLock) {
            long updatedElapsed = chatStateUpdatedElapsed;
            long age = SystemClock.elapsedRealtime() - updatedElapsed;
            int activeCount = updatedElapsed > 0L && age >= 0L
                    && age <= CHAT_STATE_TTL_MILLIS ? conversationTokens.size() : 0;
            if (activeCount == 0 && updatedElapsed > 0L
                    && (age < 0L || age > CHAT_STATE_TTL_MILLIS)) {
                conversationTokens = new ArrayList<>();
            }
            result.putInt("active_chat_count", activeCount);
            result.putLong("chat_state_updated_elapsed", updatedElapsed);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
