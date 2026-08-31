package com.oai.harmonywake;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WakeDebugLog {

    private static final String PREF = "wake_debug";
    private static final String KEY = "log";
    private static final int MAX_CHARS = 12000;

    private WakeDebugLog() {
    }

    static synchronized void add(Context context, String message) {
        if (context == null || message == null) return;
        try {
            String old = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    .format(new Date());
            String next = time + "  " + message + "\n" + old;
            if (next.length() > MAX_CHARS) {
                next = next.substring(0, MAX_CHARS);
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, next)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static String get(Context context) {
        try {
            String value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            return value == null || value.trim().isEmpty()
                    ? "还没有执行日志。请先运行一次“10秒锁屏测试”。"
                    : value;
        } catch (Exception ignored) {
            return "读取执行日志失败。";
        }
    }

    static void clear(Context context) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY)
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
