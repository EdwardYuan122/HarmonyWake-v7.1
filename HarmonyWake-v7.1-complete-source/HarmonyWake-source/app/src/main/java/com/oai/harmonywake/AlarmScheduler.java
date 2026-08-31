package com.oai.harmonywake;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

public class AlarmScheduler {

    private static final String ACTION_DAILY = "com.oai.harmonywake.ACTION_DAILY_ALARM";
    private static final String ACTION_TEST = "com.oai.harmonywake.ACTION_TEST_ALARM";

    public static void schedule(Context context, WakeTask task) {
        if (context == null || task == null) return;

        cancelDaily(context, task.id);
        if (!task.enabled || task.daysMask == 0) return;

        long next = findNextTrigger(task);
        if (next <= 0L) return;
        scheduleAlarmClock(context, task, next, false);
    }

    /** 10 秒锁屏测试使用独立 PendingIntent，不会覆盖正式任务，也不受执行星期限制。 */
    public static void scheduleTest(Context context, WakeTask task, long delayMillis) {
        if (context == null || task == null) return;
        long triggerAt = System.currentTimeMillis() + Math.max(3000L, delayMillis);
        scheduleAlarmClock(context, task, triggerAt, true);
    }

    private static long findNextTrigger(WakeTask task) {
        long now = System.currentTimeMillis();
        Calendar base = Calendar.getInstance();

        // 最多扫描未来 8 天，确保跨周也能找到下一次允许执行的日期。
        for (int offset = 0; offset <= 8; offset++) {
            Calendar candidate = (Calendar) base.clone();
            candidate.add(Calendar.DAY_OF_YEAR, offset);
            candidate.set(Calendar.HOUR_OF_DAY, task.hour);
            candidate.set(Calendar.MINUTE, task.minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);

            if (!task.runsOnCalendarDay(candidate.get(Calendar.DAY_OF_WEEK))) continue;
            if (candidate.getTimeInMillis() <= now) continue;
            return candidate.getTimeInMillis();
        }
        return -1L;
    }

    private static void scheduleAlarmClock(
            Context context,
            WakeTask task,
            long triggerAtMillis,
            boolean test
    ) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent alarmIntent = new Intent(context, AlarmReceiver.class);
        alarmIntent.setAction(test ? ACTION_TEST : ACTION_DAILY);
        alarmIntent.putExtra("task_id", task.id);
        alarmIntent.putExtra("is_test", test);

        int requestCode = test ? testRequestCode(task.id) : task.id;
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                requestCode,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                showRequestCode(task.id, test),
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.setAlarmClock(
                new AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                operation
        );
    }

    public static void cancel(Context context, int taskId) {
        if (context == null) return;
        cancelDaily(context, taskId);

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent testBroadcast = PendingIntent.getBroadcast(
                context,
                testRequestCode(taskId),
                new Intent(context, AlarmReceiver.class).setAction(ACTION_TEST),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (testBroadcast != null) {
            alarmManager.cancel(testBroadcast);
            testBroadcast.cancel();
        }

        // 清理 v4/v5 可能遗留的 Activity PendingIntent。
        PendingIntent oldActivity = PendingIntent.getActivity(
                context,
                taskId,
                new Intent(context, WakeActivity.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (oldActivity != null) {
            alarmManager.cancel(oldActivity);
            oldActivity.cancel();
        }
    }

    private static void cancelDaily(Context context, int taskId) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        PendingIntent dailyBroadcast = PendingIntent.getBroadcast(
                context,
                taskId,
                new Intent(context, AlarmReceiver.class).setAction(ACTION_DAILY),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (dailyBroadcast != null) {
            alarmManager.cancel(dailyBroadcast);
            dailyBroadcast.cancel();
        }
    }

    public static void rescheduleAll(Context context) {
        List<WakeTask> tasks = TaskStore.load(context);
        for (WakeTask task : tasks) {
            if (task != null && task.enabled) schedule(context, task);
        }
    }

    private static int testRequestCode(int taskId) {
        return taskId ^ 0x5A5A5A5A;
    }

    private static int showRequestCode(int taskId, boolean test) {
        return taskId ^ (test ? 0x13572468 : 0x24681357);
    }
}
