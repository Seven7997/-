package io.github.wechatbubbles;

/** SystemUI-only best-effort memory. It cannot observe navigation inside WeChat. */
final class LastOpenedChat {
    private String token = "";
    private long launchDeadline;
    private boolean windowSeen;

    void opened(String conversationToken, long nowElapsed) {
        clear();
        if (conversationToken == null || conversationToken.isEmpty()) return;
        token = conversationToken;
        launchDeadline = nowElapsed + 3000L;
    }

    boolean hasConversation() { return !token.isEmpty(); }

    boolean observeWindow(boolean windowOpen, long nowElapsed) {
        if (!hasConversation()) return false;
        if (windowOpen) {
            windowSeen = true;
        } else if (windowSeen || nowElapsed >= launchDeadline) {
            clear();
        }
        return hasConversation();
    }

    boolean shouldSuppress(String incomingToken, boolean windowOpen, long nowElapsed) {
        observeWindow(windowOpen, nowElapsed);
        return windowOpen && hasConversation() && token.equals(incomingToken);
    }

    void clear() {
        token = "";
        launchDeadline = 0L;
        windowSeen = false;
    }
}
