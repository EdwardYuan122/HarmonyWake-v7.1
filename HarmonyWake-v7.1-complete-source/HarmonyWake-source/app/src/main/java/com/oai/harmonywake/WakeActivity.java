package com.oai.harmonywake;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

public class WakeActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WakeTask task;
    private TextView statusText;
    private boolean launchFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * v6 中 WakeActivity 只是 Full-screen notification 的 UI 兜底。
         * 真正的闹钟触发、亮屏和自动上划均由 BroadcastReceiver + ScreenOnService 执行。
         */
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false);
            setTurnScreenOn(true);
        }

        statusText = new TextView(this);
        statusText.setTextSize(21);
        statusText.setPadding(48, 80, 48, 48);
        statusText.setText("Harmony Wake 正在执行定时唤醒…");
        setContentView(statusText);

        int taskId = getIntent().getIntExtra("task_id", -1);
        task = TaskStore.find(this, taskId);
        if (task == null) {
            statusText.setText("未找到对应的唤醒任务");
            handler.postDelayed(this::finish, 2500L);
            return;
        }

        handler.post(this::checkUnlockState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int taskId = intent.getIntExtra("task_id", -1);
        task = TaskStore.find(this, taskId);
        launchFinished = false;
        handler.post(this::checkUnlockState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(this::checkUnlockState);
    }

    private void checkUnlockState() {
        if (task == null || launchFinished || isFinishing()) return;

        if (isKeyguardLocked()) {
            if (hasSecureCredential()) {
                statusText.setText("检测到 PIN/密码/图案锁，请手动完成系统认证。认证后会自动打开目标 App。");
            } else {
                statusText.setText("Harmony Wake 正在对系统锁屏执行自动上划…");
            }
            handler.postDelayed(this::checkUnlockState, 700L);
            return;
        }

        launchTargetApp();
    }

    private boolean isKeyguardLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private boolean hasSecureCredential() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return km.isDeviceSecure();
        }
        return km.isKeyguardSecure();
    }

    private void launchTargetApp() {
        if (launchFinished || task == null) return;

        // 如果 AccessibilityService 已经把目标 App 拉起，这里只结束自己的兜底页面。
        if (!LaunchGuard.claim(task.id)) {
            launchFinished = true;
            finish();
            return;
        }

        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(task.packageName);
            if (launchIntent == null) {
                LaunchGuard.release(task.id);
                statusText.setText("无法找到目标 App 的启动入口");
                return;
            }
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            startActivity(launchIntent);
            launchFinished = true;
            handler.postDelayed(this::finish, 700L);
        } catch (Exception e) {
            LaunchGuard.release(task.id);
            statusText.setText("启动目标 App 失败");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
