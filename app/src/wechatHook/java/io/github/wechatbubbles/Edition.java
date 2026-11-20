package io.github.wechatbubbles;

/** Build-time edition switches for the WeChat-aware flavor. */
final class Edition {
    static final boolean HOOK_WECHAT = true;

    private Edition() { }

    static void installWechatObserver(ClassLoader loader) {
        ChatStateObserver.install(loader);
    }
}
