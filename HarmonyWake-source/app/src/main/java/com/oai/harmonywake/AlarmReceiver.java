package com.oai.harmonywake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int taskId = intent == null ? -1 : intent.getIntExtra("task_id", -1);
        boolean isTest = intent != null && intent.getBooleanExtra("is_test", false);

        WakeTask task = TaskStore.find(context, taskId);
        WakeDebugLog.add(context, "AlarmReceiver 收到闹钟：task=" + taskId + ", test=" + isTest);
        if (task == null || !task.enabled) {
            WakeDebugLog.add(context, "任务不存在或已禁用，停止执行");
            return;
        }

        /*
         * v6 的第一关键点：闹钟先进入 BroadcastReceiver，而不是直接启动 Activity。
         * Huawei/HarmonyOS 在锁屏/后台状态下更容易允许系统闹钟广播被投递。
         * 这里先抢一个短时亮屏 WakeLock，再启动前台服务接管后续流程。
         */
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "HarmonyWake:AlarmReceiver"
                );
                lock.setReferenceCounted(false);
                lock.acquire(15000L);
                WakeDebugLog.add(context, "AlarmReceiver 已申请 15 秒亮屏 WakeLock");
            }
        } catch (Exception ignored) {
        }

        WakeDebugLog.add(context, "准备启动 ScreenOnService");
        ScreenOnService.startForTask(context, task.id);

        // 正式每日任务在触发后立即安排下一天；测试任务不修改正式日程。
        if (!isTest) {
            try {
                AlarmScheduler.schedule(context, task);
            } catch (Exception ignored) {
            }
        }
    }
}
