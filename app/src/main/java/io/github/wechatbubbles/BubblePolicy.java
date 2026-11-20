package io.github.wechatbubbles;

/** Small pure-Java policy surface; it never reads or writes conversation storage. */
public final class BubblePolicy {
    private BubblePolicy() {}

    public static final String WECHAT = "com.tencent.mm";
    public static final String MODULE = "io.github.wechatbubbles";
    public static final String TEST_CHANNEL = "bubble_test";

    public static boolean acceptsPackage(String pkg, String channel) {
        return WECHAT.equals(pkg) || (MODULE.equals(pkg) && TEST_CHANNEL.equals(channel));
    }

    public static String clip(CharSequence value, int limit) {
        if (value == null || limit <= 0) return "";
        String s = value.toString().replace('\u0000', ' ').trim();
        if (s.length() <= limit) return s;
        int end = Math.max(0, limit - 1);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    public static String conversationKey(String pkg, String shortcut, String title, String key) {
        if (shortcut != null && !shortcut.isBlank()) return pkg + "|s|" + shortcut;
        // Display names are not identities: two contacts/groups can share one name.
        return pkg + "|n|" + key;
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }

    public static boolean forgetOnRemoval(int reason) {
        // User/system revocations clear the bubble; app read/cancel retains a shortcut in RAM.
        return reason == 2 || reason == 3 || reason == 4 || reason == 5 || reason == 6 || reason == 7
                || reason == 9 || reason == 10 || reason == 11 || reason == 14
                || reason == 15 || reason == 17 || reason == 20 || reason == 21
                || reason == 22 || reason == 23;
    }

    public static boolean matchesActiveChat(String incomingToken, java.util.Collection<String> visibleTokens,
                                             long updatedElapsed, long nowElapsed) {
        return incomingToken != null && !incomingToken.isEmpty() && visibleTokens != null
                && updatedElapsed > 0L && nowElapsed >= updatedElapsed
                && nowElapsed - updatedElapsed <= 6000L && visibleTokens.contains(incomingToken);
    }

    public static boolean isDuplicate(String oldSignature, String newSignature) {
        return oldSignature != null && oldSignature.equals(newSignature);
    }
}
