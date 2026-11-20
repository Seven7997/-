package io.github.wechatbubbles;

/** Build-time edition switches for the SystemUI-only flavor. */
final class Edition {
    static final boolean HOOK_WECHAT = false;

    private Edition() { }

    static void installWechatObserver(ClassLoader loader) {
        // Intentionally empty: this flavor does not ship WeChat process observer classes.
    }
}
