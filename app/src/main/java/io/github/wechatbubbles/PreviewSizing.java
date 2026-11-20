package io.github.wechatbubbles;

import android.view.View;

/** Uses Android's text/layout measurement, including CJK, emoji and the current font scale. */
public final class PreviewSizing {
    private PreviewSizing() { }

    public static int measureWidth(View preview, int minWidthPx, int maxWidthPx) {
        int limit = Math.max(1, maxWidthPx);
        preview.setMinimumWidth(Math.min(Math.max(0, minWidthPx), limit));
        preview.measure(View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return Math.max(1, Math.min(limit, preview.getMeasuredWidth()));
    }
}
