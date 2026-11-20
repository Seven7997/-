package io.github.wechatbubbles;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** A local-only conversation-like surface for testing an embedded activity and the IME. */
public final class DemoActivity extends Activity {
    private LinearLayout messageList;
    private ScrollView messageScroll;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("本地测试会话");
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(242, 244, 246));
        root.setFocusableInTouchMode(true);
        setContentView(root);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        TextView localNotice = new TextView(this);
        localNotice.setText("本机演示 · 不连接微信 · 不会发送消息");
        localNotice.setTextSize(12);
        localNotice.setTextColor(Color.rgb(93, 103, 115));
        localNotice.setGravity(Gravity.CENTER);
        localNotice.setPadding(dp(8), dp(5), dp(8), dp(5));
        localNotice.setBackgroundColor(Color.rgb(235, 238, 241));
        root.addView(localNotice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(dp(14), dp(14), dp(14), dp(14));
        messageScroll.addView(messageList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addMessage("这是气泡内的本地测试会话。", false);
        addMessage("点按下方输入框可验证小窗中的键盘与布局调整。", false);

        root.addView(buildComposer(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(14), dp(8));
        header.setBackgroundColor(Color.WHITE);

        TextView avatar = new TextView(this);
        avatar.setText("测");
        avatar.setTextSize(18);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setColor(Color.rgb(84, 139, 211));
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avatarBackground);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("气泡测试会话");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(31, 39, 49));
        labels.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = new TextView(this);
        subtitle.setText("本地演示");
        subtitle.setTextSize(12);
        subtitle.setTextColor(Color.rgb(104, 113, 125));
        labels.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("完成");
        close.setTextSize(14);
        close.setTextColor(Color.rgb(48, 111, 183));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(8), 0, dp(8));
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private View buildComposer() {
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(8), dp(10), dp(8));
        composer.setBackgroundColor(Color.WHITE);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(Color.rgb(34, 42, 52));
        input.setHintTextColor(Color.rgb(137, 145, 155));
        input.setHint("输入本地测试文字");
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(13), dp(8), dp(13), dp(8));
        GradientDrawable inputBackground = new GradientDrawable();
        inputBackground.setColor(Color.rgb(245, 246, 248));
        inputBackground.setCornerRadius(dp(20));
        input.setBackground(inputBackground);
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1f));

        TextView send = new TextView(this);
        send.setText("本地测试");
        send.setTextSize(14);
        send.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        send.setTextColor(Color.WHITE);
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(13), 0, dp(13), 0);
        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setColor(Color.rgb(50, 135, 88));
        sendBackground.setCornerRadius(dp(18));
        send.setBackground(sendBackground);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        sendParams.leftMargin = dp(8);
        composer.addView(send, sendParams);
        send.setOnClickListener(v -> appendLocalMessage());
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                appendLocalMessage();
                return true;
            }
            return false;
        });
        return composer;
    }

    private void appendLocalMessage() {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "请输入一段仅用于本地显示的测试文字。", Toast.LENGTH_SHORT).show();
            return;
        }
        addMessage(value, true);
        input.setText("");
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addMessage(String value, boolean outgoing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(outgoing ? Gravity.END : Gravity.START);

        TextView bubble = new TextView(this);
        bubble.setText(value);
        bubble.setTextSize(15);
        bubble.setTextColor(Color.rgb(36, 43, 52));
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        GradientDrawable bubbleBackground = new GradientDrawable();
        bubbleBackground.setColor(outgoing ? Color.rgb(194, 235, 205) : Color.WHITE);
        bubbleBackground.setCornerRadius(dp(15));
        bubble.setBackground(bubbleBackground);
        bubble.setMaxWidth(dp(300));
        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(9);
        messageList.addView(row, rowParams);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
