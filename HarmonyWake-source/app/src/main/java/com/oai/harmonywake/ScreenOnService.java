package com.oai.harmonywake;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public class ScreenOnService extends Service {

    /*
     * 使用全新的 channel id。Android 不允许程序把已经创建的 LOW 频道提升为 HIGH，
     * 所以不能继续复用 v5 的频道 id。
     */
    private static final String CHANNEL_ID = "harmony_wake_alarm_v6";
    private static final int NOTIFICATION_ID = 2601;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private PowerManager.WakeLock screenWakeLock;
    private WakeTask task;
    private int taskId = -1;
    private int swipeAttempts = 0;
    private boolean targetLaunchRequested = false;
    private long sessionDeadline = 0L;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                handler.post(ScreenOnService.this::launchTargetIfUnlocked);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                handler.postDelayed(ScreenOnService.this::driveUnlockFlow, 500L);
            }
        }
    };

    public static void startForTask(Context context, int taskId) {
        if (context == null || taskId < 0) return;
        Intent serviceIntent = new Intent(context, ScreenOnService.class);
        serviceIntent.putExtra("task_id", taskId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        resetSessionState();

        taskId = intent == null ? -1 : intent.getIntExtra("task_id", -1);
        task = TaskStore.find(this, taskId);
        WakeDebugLog.add(this, "ScreenOnService onStartCommand：task=" + taskId);

        if (task == null) {
            startForeground(NOTIFICATION_ID, buildNotification(null));
            stopSelf();
            return START_NOT_STICKY;
        }

        long duration = Math.max(1, task.screenMinutes) * 60000L;
        sessionDeadline = System.currentTimeMillis() + duration;

        // 必须尽快进入前台，避免 Android/HarmonyOS 杀掉从闹钟广播启动的服务。
        startForeground(NOTIFICATION_ID, buildNotification(task));
        WakeDebugLog.add(this, "前台服务已启动，准备保持亮屏 " + task.screenMinutes + " 分钟");
        acquireScreenWakeLock(duration);
        registerScreenStateReceiver();

        // 让无障碍服务知道：一旦检测到锁屏已经消失，直接启动目标 App。
        UnlockAccessibilityService.armTaskAutomation(task, sessionDeadline);

        // 给屏幕点亮和 Huawei Keyguard 动画留出稳定时间。
        handler.postDelayed(this::driveUnlockFlow, 1200L);

        handler.postDelayed(() -> {
            UnlockAccessibilityService.clearArmedTarget(taskId);
            stopSelf();
        }, duration);

        return START_REDELIVER_INTENT;
    }

    private void driveUnlockFlow() {
        if (task == null || targetLaunchRequested) return;
        if (System.currentTimeMillis() >= sessionDeadline) return;

        if (!isKeyguardLocked()) {
            WakeDebugLog.add(this, "检测到 Keyguard 已解除");
            launchTargetIfUnlocked();
            return;
        }

        // PIN / 密码 / 图案属于安全锁，只等待用户认证，不发送绕过手势。
        if (hasSecureCredential()) {
            WakeDebugLog.add(this, "检测到安全锁，等待用户认证，不发送手势");
            handler.postDelayed(this::driveUnlockFlow, 1000L);
            return;
        }

        if (!UnlockAccessibilityService.isReady()) {
            WakeDebugLog.add(this, "自动上划服务尚未连接，700ms 后重试");
            // 无障碍服务可能刚被系统重新绑定，持续等待；USER_PRESENT 仍可作为手动解锁兜底。
            handler.postDelayed(this::driveUnlockFlow, 700L);
            return;
        }

        if (swipeAttempts >= 5) {
            // 已尝试 5 种轨迹，停止自动手势，但服务继续等待用户手动上划。
            handler.postDelayed(this::launchTargetIfUnlocked, 1000L);
            return;
        }

        swipeAttempts++;
        boolean dispatched = UnlockAccessibilityService.performSwipeUnlock(swipeAttempts);
        WakeDebugLog.add(this, "发送第 " + swipeAttempts + " 次上划手势：dispatch=" + dispatched);
        handler.postDelayed(this::checkAfterSwipe, dispatched ? 1200L : 700L);
    }

    private void checkAfterSwipe() {
        if (task == null || targetLaunchRequested) return;
        if (!isKeyguardLocked()) {
            launchTargetIfUnlocked();
        } else {
            driveUnlockFlow();
        }
    }

    private void launchTargetIfUnlocked() {
        if (task == null || targetLaunchRequested) return;
        if (isKeyguardLocked()) {
            if (System.currentTimeMillis() < sessionDeadline) {
                handler.postDelayed(this::launchTargetIfUnlocked, 1000L);
            }
            return;
        }

        // 首选从 AccessibilityService 启动。系统绑定的无障碍服务具有更可靠的后台拉起能力。
        if (UnlockAccessibilityService.launchArmedTargetNow()) {
            WakeDebugLog.add(this, "AccessibilityService 已请求启动目标 App");
            targetLaunchRequested = true;
            return;
        }

        // 如果无障碍服务暂时不在线，使用 PendingIntent / startActivity 作为兜底。
        if (task.packageName == null || task.packageName.trim().isEmpty()) return;

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(task.packageName);
        if (launchIntent == null) return;
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        if (!LaunchGuard.claim(task.id)) {
            targetLaunchRequested = true;
            return;
        }

        try {
            PendingIntent pending = PendingIntent.getActivity(
                    this,
                    task.id ^ 0x31415926,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            pending.send();
            WakeDebugLog.add(this, "PendingIntent 已请求启动目标 App");
            targetLaunchRequested = true;
        } catch (Exception first) {
            try {
                startActivity(launchIntent);
                WakeDebugLog.add(this, "startActivity 已请求启动目标 App");
                targetLaunchRequested = true;
            } catch (Exception second) {
                LaunchGuard.release(task.id);
            }
        }
    }

    private KeyguardManager keyguardManager() {
        return (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
    }

    private boolean isKeyguardLocked() {
        KeyguardManager km = keyguardManager();
        return km != null && km.isKeyguardLocked();
    }

    private boolean hasSecureCredential() {
        KeyguardManager km = keyguardManager();
        if (km == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return km.isDeviceSecure();
        }
        return km.isKeyguardSecure();
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLock(long duration) {
        releaseScreenWakeLock();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "HarmonyWake:AlarmSession"
            );
            screenWakeLock.setReferenceCounted(false);
            screenWakeLock.acquire(duration + 5000L);
            WakeDebugLog.add(this, "ScreenOnService 亮屏 WakeLock acquire 成功");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseScreenWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
            }
        } catch (Exception ignored) {
        }
        screenWakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Harmony Wake 定时唤醒",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("用于定时亮屏和锁屏唤醒流程");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(WakeTask wakeTask) {
        Intent wakeIntent = new Intent(this, WakeActivity.class);
        wakeIntent.putExtra("task_id", wakeTask == null ? -1 : wakeTask.id);
        wakeIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        PendingIntent fullScreenIntent = PendingIntent.getActivity(
                this,
                wakeTask == null ? 2602 : (wakeTask.id ^ 0x10203040),
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String text = wakeTask == null
                ? "正在准备定时唤醒"
                : "正在唤醒并准备打开 " + (
                wakeTask.appName == null || wakeTask.appName.isEmpty()
                        ? "目标 App" : wakeTask.appName
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Harmony Wake")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(fullScreenIntent)
                    .setFullScreenIntent(fullScreenIntent, true)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Harmony Wake")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(fullScreenIntent)
                .setFullScreenIntent(fullScreenIntent, true)
                .build();
    }

    private void registerScreenStateReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenStateReceiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterScreenStateReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void resetSessionState() {
        handler.removeCallbacksAndMessages(null);
        unregisterScreenStateReceiver();
        releaseScreenWakeLock();
        if (taskId >= 0) UnlockAccessibilityService.clearArmedTarget(taskId);
        task = null;
        taskId = -1;
        swipeAttempts = 0;
        targetLaunchRequested = false;
        sessionDeadline = 0L;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterScreenStateReceiver();
        releaseScreenWakeLock();
        if (taskId >= 0) UnlockAccessibilityService.clearArmedTarget(taskId);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
