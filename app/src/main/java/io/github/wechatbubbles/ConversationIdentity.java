package io.github.wechatbubbles;

import android.app.PendingIntent;
import android.content.Intent;
import de.robv.android.xposed.XposedHelpers;

/** Opaque equality tokens; never uses a contact's display name as an identity. */
final class ConversationIdentity {
    private ConversationIdentity() { }

    static String token(String packageName, String talker) {
        return ChatIdentity.token(packageName, talker);
    }

    static String notificationToken(String packageName, PendingIntent pendingIntent) {
        if (pendingIntent == null || !packageName.equals(pendingIntent.getCreatorPackage())) return "";
        try {
            Object raw = XposedHelpers.callMethod(pendingIntent, "getIntent");
            if (!(raw instanceof Intent)) return "";
            Intent route = (Intent) raw;
            if (BubblePolicy.WECHAT.equals(packageName)) {
                return token(packageName, route.getStringExtra("Chat_User"));
            }
            if (BubblePolicy.MODULE.equals(packageName)) {
                return token(packageName, route.getStringExtra("bubble_test_conversation"));
            }
        } catch (Throwable ignored) { /* Unknown routing: show the notification rather than guess. */ }
        return "";
    }
}
