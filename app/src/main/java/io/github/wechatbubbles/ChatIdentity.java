package io.github.wechatbubbles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only opaque equality tokens leave the UI observer; no names or message text. */
final class ChatIdentity {
    private ChatIdentity() { }

    static String token(String packageName, String talker) {
        if (talker == null || talker.isBlank() || talker.length() > 512) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (packageName + "\u0000" + talker).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (Exception ignored) { return ""; }
    }
}
