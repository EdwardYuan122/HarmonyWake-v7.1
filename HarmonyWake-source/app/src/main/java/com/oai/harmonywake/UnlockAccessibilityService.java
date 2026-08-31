package com.oai.harmonywake;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class UnlockAccessibilityService extends AccessibilityService {

    private static volatile UnlockAccessibilityService instance;
    private static volatile int armedTaskId = -1;
    private static volatile String armedPackageName;
    private static volatile long armedDeadline = 0L;
    private static final List<AutomationStep> armedSteps = new ArrayList<>();
    private static volatile boolean targetLaunched = false;
    private static volatile boolean automationRunning = false;
    private static volatile int automationIndex = 0;
    private static volatile boolean automationStartScheduled = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler exitHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoExitRunnable;
    private static final long AUTO_EXIT_DELAY_MS = 60000L;

    public static boolean isReady() {
        return instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static synchronized void armTaskAutomation(WakeTask task, long deadlineMillis) {
        if (task == null) return;
        armedTaskId = task.id;
        armedPackageName = task.packageName;
        armedDeadline = deadlineMillis;
        armedSteps.clear();
        if (task.steps != null) {
            for (AutomationStep step : task.steps) {
                if (step != null) armedSteps.add(step.copy());
            }
        }
        targetLaunched = false;
        automationRunning = false;
        automationIndex = 0;
        automationStartScheduled = false;

        UnlockAccessibilityService service = instance;
        if (service != null) {
            service.cancelPendingAutoExit();
            service.handler.removeCallbacksAndMessages(null);
            service.maybeLaunchOrStart(null);
        }
    }

    /** v6 兼容入口。 */
    public static void armTargetLaunch(int taskId, String packageName, long deadlineMillis) {
        WakeTask task = instance == null ? null : TaskStore.find(instance, taskId);
        if (task != null) {
            armTaskAutomation(task, deadlineMillis);
            return;
        }
        synchronized (UnlockAccessibilityService.class) {
            armedTaskId = taskId;
            armedPackageName = packageName;
            armedDeadline = deadlineMillis;
            armedSteps.clear();
            targetLaunched = false;
            automationRunning = false;
            automationIndex = 0;
            automationStartScheduled = false;
        }
    }

    public static synchronized void clearArmedTarget(int taskId) {
        if (armedTaskId != taskId) return;
        armedTaskId = -1;
        armedPackageName = null;
        armedDeadline = 0L;
        armedSteps.clear();
        targetLaunched = false;
        automationRunning = false;
        automationIndex = 0;
        automationStartScheduled = false;
        UnlockAccessibilityService service = instance;
        if (service != null) service.handler.removeCallbacksAndMessages(null);
    }

    public static boolean launchArmedTargetNow() {
        UnlockAccessibilityService service = instance;
        return service != null && service.maybeLaunchOrStart(null);
    }

    /** 由 ScreenOnService 直接请求全局上划，不依赖 Activity。 */
    public static boolean performSwipeUnlock(int attempt) {
        UnlockAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        try {
            DisplayMetrics dm = service.getResources().getDisplayMetrics();
            float width = dm.widthPixels;
            float height = dm.heightPixels;

            float startX;
            float startY;
            float endX;
            float endY;
            long duration;

            switch (attempt) {
                case 1:
                    startX = width * 0.50f; startY = height * 0.92f;
                    endX = width * 0.50f; endY = height * 0.12f; duration = 500L; break;
                case 2:
                    startX = width * 0.50f; startY = height * 0.96f;
                    endX = width * 0.50f; endY = height * 0.06f; duration = 760L; break;
                case 3:
                    startX = width * 0.42f; startY = height * 0.90f;
                    endX = width * 0.48f; endY = height * 0.08f; duration = 380L; break;
                case 4:
                    startX = width * 0.58f; startY = height * 0.94f;
                    endX = width * 0.52f; endY = height * 0.05f; duration = 900L; break;
                default:
                    startX = width * 0.50f; startY = height * 0.84f;
                    endX = width * 0.50f; endY = height * 0.18f; duration = 620L; break;
            }

            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 50L, duration))
                    .build();

            WakeDebugLog.add(service, "AccessibilityService dispatchGesture：unlock attempt=" + attempt);
            return service.dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    service.maybeLaunchOrStart(null);
                }
            }, null);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean maybeLaunchOrStart(String activePackage) {
        int taskId = armedTaskId;
        String packageName = armedPackageName;
        long deadline = armedDeadline;

        if (taskId < 0 || packageName == null || packageName.trim().isEmpty()) return false;
        if (deadline > 0L && System.currentTimeMillis() > deadline) {
            WakeDebugLog.add(this, "自动化会话已超时，停止执行");
            clearArmedTarget(taskId);
            return false;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) return false;

        if (packageName.equals(activePackage)) {
            targetLaunched = true;
            scheduleAutomationStart(550L);
            return true;
        }

        if (targetLaunched) {
            scheduleAutomationStart(700L);
            return true;
        }

        // 如果其他兜底入口刚刚启动过 App，不重复 launch，等待窗口事件确认目标 App 已前台。
        if (!LaunchGuard.claim(taskId)) return false;

        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                LaunchGuard.release(taskId);
                WakeDebugLog.add(this, "无法找到目标 App 启动入口：" + packageName);
                return false;
            }
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            startActivity(launchIntent);
            targetLaunched = true;
            WakeDebugLog.add(this, "AccessibilityService startActivity 目标包：" + packageName);
            scheduleAutomationStart(1300L);
            return true;
        } catch (Exception e) {
            LaunchGuard.release(taskId);
            WakeDebugLog.add(this, "AccessibilityService 启动目标 App 失败");
            return false;
        }
    }

    private final Runnable startAutomationRunnable = this::tryStartAutomation;

    private void scheduleAutomationStart(long delay) {
        if (automationRunning || automationStartScheduled || armedTaskId < 0) return;
        automationStartScheduled = true;
        handler.postDelayed(startAutomationRunnable, Math.max(200L, delay));
    }

    private void tryStartAutomation() {
        automationStartScheduled = false;
        if (armedTaskId < 0 || automationRunning) return;
        if (isKeyguardStillLocked()) return;
        if (!isTargetWindowActive()) {
            if (armedDeadline <= 0L || System.currentTimeMillis() < armedDeadline) {
                scheduleAutomationStart(500L);
            }
            return;
        }

        synchronized (UnlockAccessibilityService.class) {
            if (armedSteps.isEmpty()) {
                WakeDebugLog.add(this, "目标 App 已打开；没有额外操作步骤");
                scheduleAutoExitAfterCompletion();
                int doneTask = armedTaskId;
                clearArmedTarget(doneTask);
                return;
            }
            automationRunning = true;
            automationIndex = 0;
        }
        WakeDebugLog.add(this, "开始执行自动化步骤，共 " + armedSteps.size() + " 步");
        runNextAutomationStep();
    }

    private boolean isKeyguardStillLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private boolean isTargetWindowActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || armedPackageName == null) return false;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg != null && armedPackageName.equals(pkg.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void runNextAutomationStep() {
        if (!automationRunning || armedTaskId < 0) return;
        if (armedDeadline > 0 && System.currentTimeMillis() > armedDeadline) {
            WakeDebugLog.add(this, "操作步骤执行超时");
            clearArmedTarget(armedTaskId);
            return;
        }

        final AutomationStep step;
        final int stepNumber;
        synchronized (UnlockAccessibilityService.class) {
            if (automationIndex >= armedSteps.size()) {
                WakeDebugLog.add(this, "全部自动化步骤执行完成");
                scheduleAutoExitAfterCompletion();
                int doneTask = armedTaskId;
                clearArmedTarget(doneTask);
                return;
            }
            step = armedSteps.get(automationIndex).copy();
            stepNumber = automationIndex + 1;
        }

        WakeDebugLog.add(this, "执行步骤 " + stepNumber + "/" + armedSteps.size() + "：" + step.summary());

        switch (step.type) {
            case AutomationStep.WAIT:
                handler.postDelayed(this::advanceAutomation,
                        Math.max(0L, Math.min(step.durationMs, 120000L)));
                break;
            case AutomationStep.CLICK_TEXT:
                retryClickText(step.value, 0);
                break;
            case AutomationStep.WAIT_TEXT:
                retryWaitText(step.value, 0);
                break;
            case AutomationStep.TAP_PERCENT:
                dispatchTap(step.xPercent, step.yPercent);
                break;
            case AutomationStep.SWIPE:
                dispatchAutomationSwipe(step.value, step.durationMs);
                break;
            case AutomationStep.BACK:
                WakeDebugLog.add(this, "全局返回：" + performGlobalAction(GLOBAL_ACTION_BACK));
                handler.postDelayed(this::advanceAutomation, 450L);
                break;
            case AutomationStep.HOME:
                WakeDebugLog.add(this, "全局主页：" + performGlobalAction(GLOBAL_ACTION_HOME));
                handler.postDelayed(this::advanceAutomation, 650L);
                break;
            case AutomationStep.INPUT_TEXT:
                boolean inputOk = setTextOnFocusedField(step.value);
                WakeDebugLog.add(this, "输入文字步骤结果：" + inputOk + "（内容不写入日志）");
                handler.postDelayed(this::advanceAutomation, 450L);
                break;
            default:
                WakeDebugLog.add(this, "未知步骤类型，已跳过：" + step.type);
                handler.postDelayed(this::advanceAutomation, 250L);
                break;
        }
    }


    private void scheduleAutoExitAfterCompletion() {
        cancelPendingAutoExit();
        final String completedPackage = armedPackageName;
        pendingAutoExitRunnable = () -> {
            try {
                // 只在目标 App 仍位于前台时返回桌面，避免用户已经切到别的 App 后被误打断。
                if (!isPackageActive(completedPackage)) {
                    WakeDebugLog.add(this, "自动退出检查：目标 App 已不在前台，无需返回桌面");
                    return;
                }
                boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
                WakeDebugLog.add(this, "自动化完成约 60 秒后返回桌面：" + ok
                        + (completedPackage == null ? "" : "，目标包=" + completedPackage));
            } catch (Exception e) {
                WakeDebugLog.add(this, "自动返回桌面失败");
            } finally {
                pendingAutoExitRunnable = null;
            }
        };
        WakeDebugLog.add(this, "全部操作完成，已安排约 60 秒后自动返回桌面");
        exitHandler.postDelayed(pendingAutoExitRunnable, AUTO_EXIT_DELAY_MS);
    }

    private boolean isPackageActive(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg != null && packageName.equals(pkg.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cancelPendingAutoExit() {
        if (pendingAutoExitRunnable != null) {
            exitHandler.removeCallbacks(pendingAutoExitRunnable);
            pendingAutoExitRunnable = null;
            WakeDebugLog.add(this, "已取消上一任务尚未执行的自动退出计时");
        }
    }

    private void advanceAutomation() {
        if (!automationRunning) return;
        automationIndex++;
        runNextAutomationStep();
    }

    private void retryClickText(String text, int attempt) {
        if (!automationRunning) return;
        if (clickTextOnce(text)) {
            WakeDebugLog.add(this, "点击文字成功：“" + text + "”");
            handler.postDelayed(this::advanceAutomation, 450L);
            return;
        }
        if (attempt < 11) {
            handler.postDelayed(() -> retryClickText(text, attempt + 1), 500L);
        } else {
            WakeDebugLog.add(this, "点击文字超时，未找到可点击控件：“" + text + "”");
            advanceAutomation();
        }
    }

    private void retryWaitText(String text, int attempt) {
        if (!automationRunning) return;
        if (textExists(text)) {
            WakeDebugLog.add(this, "等待文字成功：“" + text + "”");
            advanceAutomation();
            return;
        }
        if (attempt < 19) {
            handler.postDelayed(() -> retryWaitText(text, attempt + 1), 500L);
        } else {
            WakeDebugLog.add(this, "等待文字超时：“" + text + "”");
            advanceAutomation();
        }
    }

    private boolean clickTextOnce(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(query);
            if (nodes == null || nodes.isEmpty()) return false;

            AccessibilityNodeInfo preferred = null;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                CharSequence t = node.getText();
                CharSequence d = node.getContentDescription();
                if ((t != null && query.equals(t.toString()))
                        || (d != null && query.equals(d.toString()))) {
                    preferred = node;
                    break;
                }
                if (preferred == null) preferred = node;
            }
            return clickNodeOrParent(preferred);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 7 && current != null; i++) {
            try {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                current = current.getParent();
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean textExists(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(query);
            return nodes != null && !nodes.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean setTextOnFocusedField(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = null;
        try {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Exception ignored) {
        }
        if (target == null || !target.isEditable()) {
            target = findFirstEditable(root, 0);
        }
        if (target == null) return false;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value == null ? "" : value
            );
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception ignored) {
            return false;
        }
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) return null;
        try {
            if (node.isEditable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo found = findFirstEditable(node.getChild(i), depth + 1);
                if (found != null) return found;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void dispatchTap(int xPercent, int yPercent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            WakeDebugLog.add(this, "点击坐标失败：系统版本不支持手势");
            advanceAutomation();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * clampPercent(xPercent) / 100f;
        float y = dm.heightPixels * clampPercent(yPercent) / 100f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 90L))
                .build();
        boolean accepted = dispatchGesture(gesture, automationGestureCallback("点击坐标"), null);
        if (!accepted) {
            WakeDebugLog.add(this, "点击坐标 dispatchGesture 被系统拒绝");
            handler.postDelayed(this::advanceAutomation, 300L);
        }
    }

    private void dispatchAutomationSwipe(String direction, long requestedDuration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            advanceAutomation();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels;
        float h = dm.heightPixels;
        float sx = w * 0.50f, sy = h * 0.80f, ex = w * 0.50f, ey = h * 0.20f;
        if ("DOWN".equals(direction)) { sy = h * 0.20f; ey = h * 0.80f; }
        else if ("LEFT".equals(direction)) { sx = w * 0.82f; ex = w * 0.18f; sy = ey = h * 0.50f; }
        else if ("RIGHT".equals(direction)) { sx = w * 0.18f; ex = w * 0.82f; sy = ey = h * 0.50f; }

        long duration = requestedDuration <= 0L ? 550L : Math.max(150L, Math.min(2000L, requestedDuration));
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration))
                .build();
        boolean accepted = dispatchGesture(gesture, automationGestureCallback("滑动"), null);
        if (!accepted) {
            WakeDebugLog.add(this, "滑动 dispatchGesture 被系统拒绝");
            handler.postDelayed(this::advanceAutomation, 300L);
        }
    }

    private GestureResultCallback automationGestureCallback(final String label) {
        return new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                WakeDebugLog.add(UnlockAccessibilityService.this, label + "手势完成");
                handler.postDelayed(UnlockAccessibilityService.this::advanceAutomation, 350L);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                WakeDebugLog.add(UnlockAccessibilityService.this, label + "手势被取消");
                handler.postDelayed(UnlockAccessibilityService.this::advanceAutomation, 350L);
            }
        };
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        WakeDebugLog.add(this, "Harmony Wake AccessibilityService 已连接");
        maybeLaunchOrStart(null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        String activePackage = packageName == null ? null : packageName.toString();
        maybeLaunchOrStart(activePackage);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cancelPendingAutoExit();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
