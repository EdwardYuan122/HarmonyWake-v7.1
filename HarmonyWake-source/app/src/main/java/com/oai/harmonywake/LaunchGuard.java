package com.oai.harmonywake;

final class LaunchGuard {

    private static int lastTaskId = Integer.MIN_VALUE;
    private static long lastLaunchAt = 0L;

    private LaunchGuard() {
    }

    static synchronized boolean claim(int taskId) {
        long now = System.currentTimeMillis();
        if (taskId == lastTaskId && now - lastLaunchAt < 6000L) {
            return false;
        }
        lastTaskId = taskId;
        lastLaunchAt = now;
        return true;
    }

    static synchronized void release(int taskId) {
        if (taskId == lastTaskId) {
            lastTaskId = Integer.MIN_VALUE;
            lastLaunchAt = 0L;
        }
    }
}
