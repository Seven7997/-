package io.github.wechatbubbles;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collection;

/** Asynchronous, UID-authenticated IPC. Never performs provider I/O on WeChat's UI thread. */
final class ChatStateClient {
    private static Handler worker;
    private static long lastError;
    private static final Uri URI = Uri.parse("content://io.github.wechatbubbles.config");

    private ChatStateClient() { }

    static synchronized void report(Context context, Collection<String> tokens) {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("BubbleChatState", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        Context app = context.getApplicationContext();
        if (app == null) return;
        ArrayList<String> snapshot = new ArrayList<>(tokens);
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            try {
                Bundle input = new Bundle();
                input.putStringArrayList("conversation_tokens", snapshot);
                app.getContentResolver().call(URI, "report_chat_state", null, input);
            } catch (Throwable error) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastError > 30_000L) {
                    lastError = now;
                    android.util.Log.w("WeChatBubbles", "chat state relay: " + error.getClass().getSimpleName());
                }
            }
        });
    }
}
