package io.github.wechatbubbles;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main settings screen and local-only ordinary notification test publisher. */
public final class MainActivity extends Activity {
    private static final String PREFS_NAME = "config";
    private static final String ACTION_CONFIG_CHANGED = "io.github.wechatbubbles.CONFIG_CHANGED";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String CHANNEL_ID = "bubble_test";
    private static final String TEST_TEXT =
            "这是一条本机测试消息，点按通知可验证小窗和键盘，不会发送给任何联系人。";
    private static final int TEST_NOTIFICATION_ID = 73101;
    private static final int TEST_PENDING_INTENT_REQUEST = 73102;
    private static final int REQUEST_POST_NOTIFICATIONS = 73103;
    private static final String LAUNCHER_ALIAS = "io.github.wechatbubbles.LauncherAlias";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RESTART_BUSY = new AtomicBoolean(false);
    // Accessed only on the main thread; the worker never owns an Activity or a View.
    private static WeakReference<MainActivity> restartUiTarget = new WeakReference<>(null);
    private static SystemUiRestarter.Result pendingRestartResult;

    private LinearLayout content;
    private TextView statusText;
    private Switch hideLauncherSwitch;
    private TextView launcherIconStatus;
    private boolean updatingLauncherSwitch;
    private boolean lastKnownLauncherHidden;
    private Button restartSystemUiButton;
    private AlertDialog restartConfirmation;

    private SharedPreferences preferences() {
        Context deviceContext = createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("微信气泡通知");
        buildScreen();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restartUiTarget = new WeakReference<>(this);
        if (statusText != null) {
            refreshStatus();
        }
        refreshLauncherIconState();
        updateRestartButton();
        deliverRestartResult();
    }

    @Override
    protected void onPause() {
        if (restartUiTarget.get() == this) {
            restartUiTarget.clear();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (restartConfirmation != null) {
            restartConfirmation.dismiss();
            restartConfirmation = null;
        }
        if (restartUiTarget.get() == this) {
            restartUiTarget.clear();
        }
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 247, 249));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        TextView title = text("微信气泡通知", 27, Color.rgb(24, 32, 43), true);
        content.addView(title, matchWrap());
        TextView subtitle = text(
                "查看 SystemUI 模块状态，调整本机偏好并测试 ROM 原生小窗。",
                14, Color.rgb(91, 101, 114), false);
        subtitle.setPadding(0, dp(7), 0, dp(18));
        content.addView(subtitle, matchWrap());

        LinearLayout statusCard = card();
        statusCard.addView(text("运行状态", 18, Color.rgb(30, 39, 52), true), matchWrap());
        statusText = text("正在读取状态…", 14, Color.rgb(77, 87, 100), false);
        statusText.setPadding(0, dp(10), 0, dp(12));
        statusCard.addView(statusText, matchWrap());
        Button refreshButton = button("刷新状态");
        refreshButton.setOnClickListener(v -> refreshStatus());
        statusCard.addView(refreshButton, matchWrap());
        Button managerButton = button("打开 LSPosed 设置作用域");
        managerButton.setOnClickListener(v -> openLsposed());
        statusCard.addView(managerButton, matchWrap());
        restartSystemUiButton = button("重启系统界面");
        restartSystemUiButton.setOnClickListener(v -> confirmSystemUiRestart());
        statusCard.addView(restartSystemUiButton, matchWrap());
        Button quickTest = button("测试头像气泡和小窗");
        quickTest.setOnClickListener(v -> publishLocalTestNotification());
        statusCard.addView(quickTest, matchWrap());
        content.addView(statusCard, cardLayout());

        LinearLayout configCard = card();
        configCard.addView(text("本机设置", 18, Color.rgb(30, 39, 52), true), matchWrap());
        addToggle(configCard, "启用气泡", "允许 SystemUI 模块按配置处理符合条件的通知。",
                "enabled", true);
        addToggle(configCard, "消息预览", "允许预览提示；关闭后模块不应显示通知正文。",
                "preview", true);
        hideLauncherSwitch = addSwitchRow(configCard, "隐藏桌面图标",
                "隐藏后请从 LSPosed → 模块 → 微信气泡 → 设置进入，在此关闭开关即可恢复桌面图标。");
        hideLauncherSwitch.setEnabled(false);
        hideLauncherSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updatingLauncherSwitch) {
                changeLauncherIconHidden(checked);
            }
        });
        launcherIconStatus = text("正在读取桌面入口状态…", 13, Color.rgb(103, 112, 124), false);
        launcherIconStatus.setPadding(0, dp(4), 0, 0);
        configCard.addView(launcherIconStatus, matchWrap());
        TextView chatRule = text(Edition.HOOK_WECHAT
                ? "微信 Hook 版：实时识别小窗当前会话。同一会话不再弹气泡，其他会话仍显示；可跟随小窗内手动切换聊天。"
                : "仅 SystemUI 版：不注入微信，记住最后点开的气泡会话。该小窗存续时抑制此会话，其他会话仍显示；无法跟随微信内手动切换聊天。",
                14, Color.rgb(77, 87, 100), false);
        chatRule.setPadding(0, dp(14), 0, dp(6));
        configCard.addView(chatRule, matchWrap());
        content.addView(configCard, cardLayout());

        LinearLayout actionsCard = card();
        actionsCard.addView(text("本机测试", 18, Color.rgb(30, 39, 52), true), matchWrap());
        TextView testDescription = text(
                "生成本机测试消息并返回桌面。模块已加载时，会出现头像气泡和短时预览。\n"
                        + "点按头像或预览可检查原生小窗、键盘和输入；测试内容只显示在本地，不会发送给微信联系人。",
                14, Color.rgb(77, 87, 100), false);
        testDescription.setPadding(0, dp(8), 0, dp(12));
        actionsCard.addView(testDescription, matchWrap());

        Button testButton = button("生成测试通知");
        testButton.setOnClickListener(v -> publishLocalTestNotification());
        actionsCard.addView(testButton, matchWrap());

        Button permissionButton = button("检查 / 请求通知权限");
        permissionButton.setOnClickListener(v -> requestNotificationPermission());
        LinearLayout.LayoutParams secondButton = matchWrap();
        secondButton.topMargin = dp(8);
        actionsCard.addView(permissionButton, secondButton);
        content.addView(actionsCard, cardLayout());

        LinearLayout notesCard = card();
        notesCard.addView(text("接入与外观说明", 18, Color.rgb(30, 39, 52), true), matchWrap());
        TextView notes = text(
                (Edition.HOOK_WECHAT
                        ? "• 微信 Hook 版：勾选“系统界面”和“微信”，重新载入两个进程。微信仅只读观察界面状态；不勾选系统框架。\n"
                        : "• 仅 SystemUI 版：只勾选“系统界面”，取消“微信”。从 Hook 版切换后需重新启动微信和系统界面，清除旧版已载入代码。\n")
                        + "• 微信需开启系统新消息通知；若微信隐藏通知正文，气泡也不能还原正文。\n"
                        + "• 点按气泡打开真实微信会话小窗，小窗边框和聊天界面沿用当前手机系统与微信版本。\n"
                        + "• 预览仅在内存中显示，不保存通知正文；锁屏时清除气泡。",
                14, Color.rgb(77, 87, 100), false);
        notes.setPadding(0, dp(9), 0, dp(2));
        notesCard.addView(notes, matchWrap());
        Button settingsButton = button("打开本应用通知设置");
        settingsButton.setOnClickListener(v -> openAppNotificationSettings());
        LinearLayout.LayoutParams settingsParams = matchWrap();
        settingsParams.topMargin = dp(10);
        notesCard.addView(settingsButton, settingsParams);
        content.addView(notesCard, cardLayout());
    }

    private void addToggle(LinearLayout parent, String title, String description,
            String key, boolean defaultValue) {
        Switch toggle = addSwitchRow(parent, title, description);
        toggle.setChecked(preferences().getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences().edit().putBoolean(key, checked).apply();
            notifySystemUiConfigChanged();
        });
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(13), 0, dp(3));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, 16, Color.rgb(36, 45, 58), true);
        labels.addView(label, matchWrap());
        TextView detail = text(description, 13, Color.rgb(103, 112, 124), false);
        detail.setPadding(0, dp(3), dp(8), 0);
        labels.addView(detail, matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setContentDescription(title);
        row.addView(toggle, wrapWrap());
        parent.addView(row, matchWrap());
        return toggle;
    }

    private boolean isLauncherIconHidden() throws PackageManager.NameNotFoundException {
        PackageManager packageManager = getPackageManager();
        ComponentName alias = new ComponentName(this, LAUNCHER_ALIAS);
        int state = packageManager.getComponentEnabledSetting(alias);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            // The alias is enabled in the manifest, including on upgrades from the old launcher.
            return !packageManager.getActivityInfo(alias,
                    PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        }
        return state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void renderLauncherIconState(boolean hidden, boolean available, String message) {
        updatingLauncherSwitch = true;
        try {
            hideLauncherSwitch.setChecked(hidden);
            hideLauncherSwitch.setEnabled(available);
            launcherIconStatus.setText(message);
        } finally {
            updatingLauncherSwitch = false;
        }
    }

    private void refreshLauncherIconState() {
        if (hideLauncherSwitch == null) return;
        try {
            lastKnownLauncherHidden = isLauncherIconHidden();
            renderLauncherIconState(lastKnownLauncherHidden, true,
                    lastKnownLauncherHidden ? "桌面入口已隐藏，可随时从 LSPosed 设置恢复。"
                            : "桌面入口可见。");
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            renderLauncherIconState(lastKnownLauncherHidden, false,
                    "无法读取桌面入口状态，请重新进入设置后重试。");
        }
    }

    private void changeLauncherIconHidden(boolean hidden) {
        boolean previousHidden = lastKnownLauncherHidden;
        try {
            previousHidden = isLauncherIconHidden();
            if (previousHidden != hidden) {
                // Only the launcher alias changes. MainActivity remains available to LSPosed.
                getPackageManager().setComponentEnabledSetting(
                        new ComponentName(this, LAUNCHER_ALIAS),
                        hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
            lastKnownLauncherHidden = isLauncherIconHidden();
            renderLauncherIconState(lastKnownLauncherHidden, true,
                    lastKnownLauncherHidden ? "桌面入口已隐藏，可随时从 LSPosed 设置恢复。"
                            : "桌面入口可见。");
            if (lastKnownLauncherHidden != hidden) {
                Toast.makeText(this, "桌面入口状态未生效，请重试。", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, hidden
                    ? "桌面图标已隐藏；请从 LSPosed → 模块 → 设置进入恢复。"
                    : "桌面图标已恢复；桌面可能稍后刷新。", Toast.LENGTH_LONG).show();
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            // A PackageManager call can fail after applying a change: re-read before rolling back UI.
            try {
                lastKnownLauncherHidden = isLauncherIconHidden();
                renderLauncherIconState(lastKnownLauncherHidden, true,
                        "操作未能确认，请按当前桌面入口状态重试。");
            } catch (PackageManager.NameNotFoundException | RuntimeException readException) {
                lastKnownLauncherHidden = previousHidden;
                renderLauncherIconState(previousHidden, false,
                        "无法确认桌面入口状态，请重新进入设置后重试。");
            }
            Toast.makeText(this, "无法更改桌面图标，请重新进入设置后重试。",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmSystemUiRestart() {
        if (RESTART_BUSY.get() || (restartConfirmation != null
                && restartConfirmation.isShowing())) return;
        restartConfirmation = new AlertDialog.Builder(this)
                .setTitle("重启系统界面")
                .setMessage("状态栏、导航栏和通知面板等会短暂消失，随后由系统重新加载。"
                        + "此操作需要 Root 授权，当前气泡会收起；后续通知会使用保存的位置。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启", (dialog, which) -> startSystemUiRestart())
                .create();
        restartConfirmation.setOnDismissListener(dialog -> restartConfirmation = null);
        restartConfirmation.show();
    }

    private void updateRestartButton() {
        if (restartSystemUiButton == null) return;
        boolean busy = RESTART_BUSY.get();
        restartSystemUiButton.setEnabled(!busy);
        restartSystemUiButton.setText(busy ? "正在重启系统界面…" : "重启系统界面");
    }

    private void startSystemUiRestart() {
        if (!RESTART_BUSY.compareAndSet(false, true)) return;
        updateRestartButton();
        try {
            // Static worker: never capture an Activity, dialog, View, or Context.
            new Thread(new SystemUiRestartTask(), "systemui-restart").start();
        } catch (RuntimeException exception) {
            finishSystemUiRestart(SystemUiRestarter.Result.START_FAILED);
        }
    }

    private static final class SystemUiRestartTask implements Runnable {
        @Override
        public void run() {
            SystemUiRestarter.Result result = SystemUiRestarter.restart();
            MAIN_HANDLER.post(() -> finishSystemUiRestart(result));
        }
    }

    private static void finishSystemUiRestart(SystemUiRestarter.Result result) {
        RESTART_BUSY.set(false);
        pendingRestartResult = result;
        MainActivity activity = restartUiTarget.get();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.updateRestartButton();
            activity.deliverRestartResult();
        }
    }

    private void deliverRestartResult() {
        if (pendingRestartResult == null) return;
        SystemUiRestarter.Result result = pendingRestartResult;
        pendingRestartResult = null;
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
    }

    private void refreshStatus() {
        try {
            Bundle status = getContentResolver().call(
                    ConfigProvider.URI, ConfigProvider.METHOD_GET_STATUS, null, null);
            if (status == null) {
                statusText.setText("尚未收到系统界面状态，请启用模块并重新载入后刷新。\n"
                        + "启用气泡：" + yesNo(preferences().getBoolean("enabled", true)));
                return;
            }
            boolean hookReportedActive = status.getBoolean("hook_active", false);
            boolean freeform = status.getBoolean("freeform_available", false);
            String version = nonNull(status.getString("hook_version", ""));
            int received = status.getInt("received_count", 0);
            int shown = status.getInt("shown_count", 0);
            String lastEvent = nonNull(status.getString("last_event", ""));
            String lastError = nonNull(status.getString("last_error", ""));
            long updatedAt = status.getLong("updated_at", 0L);
            long heartbeatAge = System.currentTimeMillis() - updatedAt;
            boolean heartbeatFresh = updatedAt > 0L && heartbeatAge >= 0L
                    && heartbeatAge < 180_000L;
            boolean correctEdition = BuildConfig.VERSION_NAME.equals(version);
            boolean hookActive = hookReportedActive && heartbeatFresh && correctEdition;

            StringBuilder display = new StringBuilder();
            display.append("SystemUI Hook：");
            if (hookActive) {
                display.append("已加载（心跳正常）");
            } else if (hookReportedActive && !correctEdition) {
                display.append("仍为其他版本，请重新载入系统界面与微信");
            } else if (hookReportedActive) {
                display.append("状态已过期，当前未确认");
            } else {
                display.append("未加载 / 尚未上报");
            }
            if (!version.isEmpty()) display.append("  ·  ").append(version);
            display.append("\nROM 小窗能力：").append(freeform ? "可用" : "未确认");
            display.append("\n已安装：").append(BuildConfig.VERSION_NAME);
            if (Edition.HOOK_WECHAT) {
                display.append("\n当前小窗会话：").append(status.getInt("active_chat_count", 0));
            } else {
                display.append("\n会话判断：最后点开的气泡记录（不读取微信界面）");
            }
            display.append("\n收到 / 显示：").append(received).append(" / ").append(shown);
            if (!lastEvent.isEmpty()) display.append("\n最近事件：").append(lastEvent);
            if (!lastError.isEmpty()) display.append("\n最近错误：").append(lastError);
            if (updatedAt > 0L) {
                display.append("\n最近心跳：").append(DateFormat.getDateTimeInstance()
                        .format(new Date(updatedAt)));
                if (heartbeatAge >= 0L) {
                    display.append("（").append(heartbeatAge / 1000L).append(" 秒前）");
                }
            }
            statusText.setText(display.toString());
        } catch (RuntimeException exception) {
            statusText.setText("暂时无法读取模块状态。\n"
                    + "请确认 ConfigProvider 已导出并允许 SystemUI 调用。\n"
                    + "详情：" + exception.getClass().getSimpleName());
        }
    }

    private void publishLocalTestNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先允许通知权限，再发送测试通知。", Toast.LENGTH_LONG).show();
            requestNotificationPermission();
            return;
        }

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            Toast.makeText(this, "系统通知服务不可用。", Toast.LENGTH_LONG).show();
            return;
        }
        ensureTestChannel(notificationManager);

        Intent demoIntent = new Intent(this, DemoActivity.class)
                .setAction("io.github.wechatbubbles.OPEN_LOCAL_DEMO")
                .setData(Uri.parse("wechatbubbles://demo/local-test"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this,
                TEST_PENDING_INTENT_REQUEST, demoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("气泡测试")
                .setContentText(TEST_TEXT)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(true);

        try {
            notificationManager.notify(TEST_NOTIFICATION_ID, builder.build());
            Toast.makeText(this, "本机测试通知已发出；正在返回桌面。", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> moveTaskToBack(true), 500L);
        } catch (SecurityException exception) {
            Toast.makeText(this, "发送失败：请检查通知权限与应用通知设置。", Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            Toast.makeText(this, "发送测试通知失败：" + exception.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensureTestChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < 26 || notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "本机气泡测试", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("本机测试通知，不会发送给微信联系人。");
        notificationManager.createNotificationChannel(channel);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "此 Android 版本不需要运行时通知权限。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限已允许。", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "通知权限已允许。" : "通知权限未允许。",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void notifySystemUiConfigChanged() {
        Intent changed = new Intent(ACTION_CONFIG_CHANGED).setPackage(SYSTEM_UI_PACKAGE);
        sendBroadcast(changed);
    }

    private void openAppNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开本应用通知设置。", Toast.LENGTH_SHORT).show();
        }
    }

    private String nonNull(String value) { return value == null ? "" : value; }

    private String yesNo(boolean value) {
        return value ? "开" : "关";
    }

    private void openLsposed() {
        try {
            startActivity(new Intent().setComponent(new ComponentName(
                    "org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")));
        } catch (RuntimeException e) {
            Toast.makeText(this, Edition.HOOK_WECHAT
                    ? "请在 LSPosed 启用本模块，勾选系统界面与微信"
                    : "请在 LSPosed 启用本模块，仅勾选系统界面并取消微信", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        return card;
    }

    private LinearLayout.LayoutParams cardLayout() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        return params;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
