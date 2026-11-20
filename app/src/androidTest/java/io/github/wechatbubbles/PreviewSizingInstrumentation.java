package io.github.wechatbubbles;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native, synthetic-only checks for the preview's actual Android text measurement. */
public final class PreviewSizingInstrumentation extends Instrumentation {
    private int assertions;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        final Bundle results = new Bundle();
        final Throwable[] failure = new Throwable[1];
        try {
            runOnMainSync(() -> {
                // Catch here so an assertion cannot escape onto the app's main thread.
                try {
                    results.putString("stream", runCases());
                } catch (Throwable error) {
                    failure[0] = error;
                }
            });
        } catch (Throwable error) {
            failure[0] = error;
        }
        if (failure[0] != null) {
            results.putString("stream", "FAIL: generic assertion error\n");
            finish(Activity.RESULT_CANCELED, results);
        } else {
            finish(Activity.RESULT_OK, results);
        }
    }

    private String runCases() {
        assertions = 0;
        Context context = getTargetContext();
        int min = dp(context, 72);
        int max = dp(context, 264);
        String title = "气泡测试";
        StringBuilder sizes = new StringBuilder();

        Sample shortText = measure(context, "short", title, "好", min, max, sizes);
        Sample mediumText = measure(context, "medium-10-cjk", title,
                "一二三四五六七八九十", min, max, sizes);
        Sample longText = measure(context, "long-mixed-emoji", title,
                repeat("这是一段合成测试文字 Android preview sizing 😀🎈 ", 12),
                min, max, sizes);
        Sample multiline = measure(context, "multiline", title, "好\n好\n好",
                min, max, sizes);
        Sample longTitle = measure(context, "long-title", repeat("合成超长标题", 24),
                "好", min, max, sizes);
        Sample narrow = measure(context, "ultra-narrow", title, "窄屏合成文本😀",
                min, 1, sizes);
        Sample minimum = measure(context, "minimum", "", "", min, max, sizes);
        Sample huge = measure(context, "huge", title,
                repeat("超长合成文本 ABCDEFGHIJKLMNOPQRSTUVWXYZ 😀🎈👩‍💻 ", 128),
                min, max, sizes);

        check(shortText.width < mediumText.width && mediumText.width <= longText.width);
        check(longText.width == max && huge.width == max);
        check(multiline.height > shortText.height);
        check(longTitle.width == max && longTitle.height == shortText.height);
        check(narrow.width == 1);
        check(minimum.width == min);
        return "PASS " + assertions + " assertions\n" + sizes;
    }

    private Sample measure(Context context, String name, String title, String text,
            int min, int max, StringBuilder sizes) {
        LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(context, 15), dp(context, 11),
                dp(context, 15), dp(context, 12));

        TextView heading = textView(context, title, 13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        preview.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = textView(context, text, 14);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(context, 4);
        preview.addView(body, bodyParams);

        int width = PreviewSizing.measureWidth(preview, min, max);
        int height = preview.getMeasuredHeight();
        preview.layout(0, 0, width, height);
        check(width == preview.getMeasuredWidth()
                && width >= Math.min(min, max) && width <= max && height > 0
                && heading.getLayout() != null && heading.getLineCount() <= 1
                && body.getLayout() != null && body.getLineCount() <= 3);
        sizes.append(name).append(": width=").append(width).append(" px, height=")
                .append(height).append(" px\n");
        return new Sample(width, height);
    }

    private static TextView textView(Context context, String text, int sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void check(boolean condition) {
        if (!condition) throw new AssertionError("generic assertion error");
        assertions++;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static final class Sample {
        final int width;
        final int height;

        Sample(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
