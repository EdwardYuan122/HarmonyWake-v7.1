package com.oai.harmonywake;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout list;
    private TextView reliabilityStatus;
    private List<WakeTask> tasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tasks = TaskStore.load(this);
        try {
            AlarmScheduler.rescheduleAll(this);
        } catch (Exception ignored) {
        }
        updateReliabilityStatus();
        render();
    }

    private TextView text(String value, int sp) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setPadding(0, 12, 0, 12);
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 36);
        scroll.addView(root);

        root.addView(text("Harmony Wake", 28));
        root.addView(text(
                "v7.1：按星期定时唤醒、执行自动化步骤，并在全部操作完成约 60 秒后自动返回桌面。",
                15
        ));

        reliabilityStatus = text("", 15);
        root.addView(reliabilityStatus);

        Button accessibility = button("① 开启自动上划 / 自动化权限");
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(accessibility);

        Button huawei = button("② 华为自启动 / 后台运行设置");
        huawei.setOnClickListener(v -> openHuaweiStartupSettings());
        root.addView(huawei);

        Button battery = button("③ 关闭 Harmony Wake 电池优化");
        battery.setOnClickListener(v -> openBatteryOptimizationSettings());
        root.addView(battery);

        Button details = button("④ Harmony Wake 应用详情");
        details.setOnClickListener(v -> openAppDetails());
        root.addView(details);

        Button logs = button("⑤ 查看最近执行日志");
        logs.setOnClickListener(v -> showLogDialog());
        root.addView(logs);

        root.addView(text(
                "自动化说明：\n"
                        + "• 已验证成功的 v6 亮屏与非安全上划解锁链路保持不变。\n"
                        + "• v7 可按周一～周日自由选择执行日期。\n"
                        + "• 打开目标 App 后，可继续执行：等待、点击文字、点击坐标、滑动、返回、主页、输入文字、等待文字出现。\n"
                        + "• 全部操作执行完成后约 60 秒自动返回桌面；这是退出前台，不是强制停止目标 App。\n"
                        + "• “点击文字/等待文字”需要无障碍服务在自动化执行期间读取当前界面的控件树；Harmony Wake 不保存界面内容。\n"
                        + "• 不要在“输入文字”步骤里保存密码、PIN、验证码或支付凭据。\n"
                        + "• 检测到 PIN、密码或图案锁时仍不会尝试绕过认证。",
                14
        ));

        Button add = button("＋ 添加唤醒任务");
        add.setOnClickListener(v -> editTask(null));
        root.addView(add);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
    }

    private void showLogDialog() {
        final String log = WakeDebugLog.get(this);
        new AlertDialog.Builder(this)
                .setTitle("Harmony Wake 最近执行日志")
                .setMessage(log)
                .setPositiveButton("关闭", null)
                .setNegativeButton("复制全部", (dialog, which) -> copyLog(log))
                .setNeutralButton("清空", (dialog, which) -> WakeDebugLog.clear(this))
                .show();
    }

    private void copyLog(String log) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Harmony Wake 日志", log));
                Toast.makeText(this, "执行日志已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "复制日志失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateReliabilityStatus() {
        StringBuilder s = new StringBuilder("运行保障状态\n");
        s.append("✓ 系统闹钟模式：已启用\n");
        s.append("✓ 手机重启后：自动恢复任务\n");
        s.append(isAccessibilityServiceEnabled()
                ? "✓ 自动上划 / 自动化权限：已开启\n"
                : "⚠ 自动上划 / 自动化权限：未开启\n");
        s.append(isIgnoringBatteryOptimizations()
                ? "✓ 电池优化：已关闭\n"
                : "⚠ 电池优化：建议关闭\n");
        s.append("⚠ 华为自启动：请在系统设置中确认");
        reliabilityStatus.setText(s.toString());
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabled == null || enabled.trim().isEmpty()) return false;

            ComponentName target = new ComponentName(this, UnlockAccessibilityService.class);
            String[] parts = enabled.split(":");
            for (String part : parts) {
                ComponentName c = ComponentName.unflattenFromString(part);
                if (target.equals(c)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        try {
            Toast.makeText(
                    this,
                    "请找到“Harmony Wake 自动上划”并开启。v7 自动化步骤也使用同一服务。",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {
            openAppDetails();
        }
    }

    private void openHuaweiStartupSettings() {
        String[][] components = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"}
        };
        for (String[] c : components) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                startActivity(i);
                Toast.makeText(
                        this,
                        "请关闭自动管理，并允许自启动、关联启动和后台活动。",
                        Toast.LENGTH_LONG
                ).show();
                return;
            } catch (Exception ignored) {
            }
        }
        openAppDetails();
    }

    private void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            openAppDetails();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void render() {
        list.removeAllViews();
        if (tasks.isEmpty()) {
            list.addView(text("还没有任务。", 17));
            return;
        }

        tasks.sort((a, b) -> (a.hour * 60 + a.minute) - (b.hour * 60 + b.minute));

        for (WakeTask task : new ArrayList<>(tasks)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(12, 18, 12, 18);

            card.addView(text(String.format(
                    Locale.getDefault(), "%02d:%02d", task.hour, task.minute), 26));

            String appName = task.appName != null && !task.appName.isEmpty()
                    ? task.appName
                    : (task.packageName == null || task.packageName.isEmpty()
                    ? "未选择" : task.packageName);

            int stepCount = task.steps == null ? 0 : task.steps.size();
            card.addView(text(
                    "执行日：" + formatDays(task.daysMask)
                            + "\n目标 App：" + appName
                            + "\n亮屏：" + task.screenMinutes + " 分钟"
                            + "\n额外操作：" + stepCount + " 步"
                            + "\n完成后：约60秒返回桌面",
                    16
            ));

            Switch enabled = new Switch(this);
            enabled.setText("启用任务");
            enabled.setChecked(task.enabled);
            enabled.setOnCheckedChangeListener((buttonView, checked) -> {
                task.enabled = checked;
                persist();
            });
            card.addView(enabled);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button edit = button("编辑");
            edit.setOnClickListener(v -> editTask(task));
            row.addView(edit);

            Button test = button("10秒锁屏测试");
            test.setOnClickListener(v -> {
                AlarmScheduler.scheduleTest(this, task, 10000L);
                Toast.makeText(
                        this,
                        "10 秒后走完整链路；测试不受星期限制。请现在锁屏。",
                        Toast.LENGTH_LONG
                ).show();
            });
            row.addView(test);

            Button delete = button("删除");
            delete.setOnClickListener(v -> {
                AlarmScheduler.cancel(this, task.id);
                tasks.remove(task);
                persist();
                render();
            });
            row.addView(delete);

            card.addView(row);
            list.addView(card);
        }
    }

    private void editTask(WakeTask existing) {
        final WakeTask draft = existing == null
                ? new WakeTask(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                7, 30, "", "", 10, true,
                WakeTask.ALL_DAYS_MASK, new ArrayList<>())
                : existing.copy();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 12, 36, 0);

        Button time = button(String.format(
                Locale.getDefault(), "时间：%02d:%02d", draft.hour, draft.minute));
        time.setOnClickListener(v -> new TimePickerDialog(
                this,
                (view, hour, minute) -> {
                    draft.hour = hour;
                    draft.minute = minute;
                    time.setText(String.format(
                            Locale.getDefault(), "时间：%02d:%02d", hour, minute));
                },
                draft.hour,
                draft.minute,
                true
        ).show());
        box.addView(time);

        Button days = button("执行日：" + formatDays(draft.daysMask));
        days.setOnClickListener(v -> chooseDays(draft, days));
        box.addView(days);

        Button app = button(
                draft.appName == null || draft.appName.isEmpty()
                        ? "选择目标 App"
                        : "目标 App：" + draft.appName
        );
        app.setOnClickListener(v -> chooseApp(draft, app));
        box.addView(app);

        Button steps = button(stepButtonText(draft));
        steps.setOnClickListener(v -> showStepManager(draft, steps));
        box.addView(steps);

        EditText minutes = new EditText(this);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setHint("亮屏分钟数，例如 10");
        minutes.setText(String.valueOf(draft.screenMinutes));
        box.addView(minutes);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加任务" : "编辑任务")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        draft.screenMinutes = Math.max(
                                1,
                                Integer.parseInt(minutes.getText().toString().trim())
                        );
                    } catch (Exception ignored) {
                        draft.screenMinutes = 10;
                    }
                    if (draft.daysMask == 0) draft.daysMask = WakeTask.ALL_DAYS_MASK;

                    if (existing == null) {
                        tasks.add(draft);
                    } else {
                        int index = tasks.indexOf(existing);
                        if (index >= 0) tasks.set(index, draft);
                    }
                    persist();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseDays(WakeTask draft, Button daysButton) {
        final String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        final int[] calendarDays = {
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        };
        final boolean[] checked = new boolean[7];
        for (int i = 0; i < checked.length; i++) {
            checked[i] = draft.runsOnCalendarDay(calendarDays[i]);
        }

        new AlertDialog.Builder(this)
                .setTitle("选择每周执行日")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("确定", (dialog, which) -> {
                    int mask = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) mask |= 1 << (calendarDays[i] - 1);
                    }
                    if (mask == 0) {
                        Toast.makeText(this, "至少选择一天；已保留原设置", Toast.LENGTH_LONG).show();
                        return;
                    }
                    draft.daysMask = mask;
                    daysButton.setText("执行日：" + formatDays(mask));
                })
                .setNeutralButton("周一至周五", (dialog, which) -> {
                    draft.daysMask = 62;
                    daysButton.setText("执行日：周一至周五");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatDays(int mask) {
        mask &= WakeTask.ALL_DAYS_MASK;
        if (mask == WakeTask.ALL_DAYS_MASK) return "每天";
        if (mask == 62) return "周一至周五";
        if (mask == 65) return "周六、周日";

        String[] labels = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        StringBuilder out = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            if ((mask & (1 << (day - 1))) == 0) continue;
            if (out.length() > 0) out.append("、");
            out.append(labels[day - 1]);
        }
        return out.length() == 0 ? "未选择" : out.toString();
    }

    private String stepButtonText(WakeTask draft) {
        int count = draft.steps == null ? 0 : draft.steps.size();
        return count == 0 ? "操作步骤：仅打开目标 App" : "操作步骤：" + count + " 步（点击编辑）";
    }

    private void showStepManager(WakeTask draft, Button ownerButton) {
        if (draft.steps == null) draft.steps = new ArrayList<>();

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(28, 12, 28, 12);
        scroll.addView(box);

        box.addView(text("固定第 0 步：打开目标 App。以下步骤会在解锁并打开 App 后依次执行。", 14));

        final AlertDialog[] ref = new AlertDialog[1];
        for (int i = 0; i < draft.steps.size(); i++) {
            final int index = i;
            AutomationStep step = draft.steps.get(i);
            box.addView(text((i + 1) + ". " + step.summary(), 16));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button up = button("↑");
            up.setEnabled(i > 0);
            up.setOnClickListener(v -> {
                if (index > 0) {
                    AutomationStep item = draft.steps.remove(index);
                    draft.steps.add(index - 1, item);
                }
                if (ref[0] != null) ref[0].dismiss();
                showStepManager(draft, ownerButton);
            });
            row.addView(up);

            Button down = button("↓");
            down.setEnabled(i < draft.steps.size() - 1);
            down.setOnClickListener(v -> {
                if (index < draft.steps.size() - 1) {
                    AutomationStep item = draft.steps.remove(index);
                    draft.steps.add(index + 1, item);
                }
                if (ref[0] != null) ref[0].dismiss();
                showStepManager(draft, ownerButton);
            });
            row.addView(down);

            Button delete = button("删除");
            delete.setOnClickListener(v -> {
                draft.steps.remove(index);
                ownerButton.setText(stepButtonText(draft));
                if (ref[0] != null) ref[0].dismiss();
                showStepManager(draft, ownerButton);
            });
            row.addView(delete);
            box.addView(row);
        }

        Button add = button("＋ 添加操作步骤");
        add.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            chooseStepType(draft, ownerButton);
        });
        box.addView(add);

        ref[0] = new AlertDialog.Builder(this)
                .setTitle("自动化操作步骤")
                .setView(scroll)
                .setPositiveButton("完成", (dialog, which) -> ownerButton.setText(stepButtonText(draft)))
                .create();
        ref[0].show();
    }

    private void chooseStepType(WakeTask draft, Button ownerButton) {
        String[] types = {
                "等待",
                "点击文字",
                "点击坐标（屏幕百分比）",
                "上滑",
                "下滑",
                "左滑",
                "右滑",
                "返回",
                "主页",
                "输入文字",
                "等待文字出现"
        };
        new AlertDialog.Builder(this)
                .setTitle("添加操作步骤")
                .setItems(types, (dialog, which) -> {
                    switch (which) {
                        case 0: promptWaitStep(draft, ownerButton); break;
                        case 1: promptTextStep(draft, ownerButton, AutomationStep.CLICK_TEXT, "要点击的文字"); break;
                        case 2: promptTapStep(draft, ownerButton); break;
                        case 3: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.SWIPE, "UP", 0, 0, 550L)); break;
                        case 4: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.SWIPE, "DOWN", 0, 0, 550L)); break;
                        case 5: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.SWIPE, "LEFT", 0, 0, 550L)); break;
                        case 6: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.SWIPE, "RIGHT", 0, 0, 550L)); break;
                        case 7: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.BACK, "", 0, 0, 0L)); break;
                        case 8: addSimpleStep(draft, ownerButton, new AutomationStep(AutomationStep.HOME, "", 0, 0, 0L)); break;
                        case 9: promptTextStep(draft, ownerButton, AutomationStep.INPUT_TEXT, "要输入的文字（不要保存密码/PIN/验证码）"); break;
                        case 10: promptTextStep(draft, ownerButton, AutomationStep.WAIT_TEXT, "等待出现的文字"); break;
                        default: showStepManager(draft, ownerButton); break;
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> showStepManager(draft, ownerButton))
                .show();
    }

    private void promptWaitStep(WakeTask draft, Button ownerButton) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("等待秒数，例如 2");
        input.setText("2");
        new AlertDialog.Builder(this)
                .setTitle("等待")
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    double seconds = 2.0;
                    try { seconds = Double.parseDouble(input.getText().toString().trim()); }
                    catch (Exception ignored) { }
                    long ms = (long) (Math.max(0.1, Math.min(120.0, seconds)) * 1000L);
                    draft.steps.add(new AutomationStep(AutomationStep.WAIT, "", 0, 0, ms));
                    ownerButton.setText(stepButtonText(draft));
                    showStepManager(draft, ownerButton);
                })
                .setNegativeButton("取消", (dialog, which) -> showStepManager(draft, ownerButton))
                .show();
    }

    private void promptTextStep(WakeTask draft, Button ownerButton, String type, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle(hint)
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String value = input.getText().toString();
                    if (value.trim().isEmpty()) {
                        Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
                    } else {
                        draft.steps.add(new AutomationStep(type, value, 0, 0, 0L));
                    }
                    ownerButton.setText(stepButtonText(draft));
                    showStepManager(draft, ownerButton);
                })
                .setNegativeButton("取消", (dialog, which) -> showStepManager(draft, ownerButton))
                .show();
    }

    private void promptTapStep(WakeTask draft, Button ownerButton) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(28, 0, 28, 0);

        EditText x = new EditText(this);
        x.setInputType(InputType.TYPE_CLASS_NUMBER);
        x.setHint("X 百分比 0-100，例如 50");
        x.setText("50");
        box.addView(x);

        EditText y = new EditText(this);
        y.setInputType(InputType.TYPE_CLASS_NUMBER);
        y.setHint("Y 百分比 0-100，例如 70");
        y.setText("50");
        box.addView(y);

        new AlertDialog.Builder(this)
                .setTitle("点击坐标（屏幕百分比）")
                .setView(box)
                .setPositiveButton("添加", (dialog, which) -> {
                    int xp = parsePercent(x.getText().toString(), 50);
                    int yp = parsePercent(y.getText().toString(), 50);
                    draft.steps.add(new AutomationStep(AutomationStep.TAP_PERCENT, "", xp, yp, 0L));
                    ownerButton.setText(stepButtonText(draft));
                    showStepManager(draft, ownerButton);
                })
                .setNegativeButton("取消", (dialog, which) -> showStepManager(draft, ownerButton))
                .show();
    }

    private int parsePercent(String value, int fallback) {
        try { return Math.max(0, Math.min(100, Integer.parseInt(value.trim()))); }
        catch (Exception ignored) { return fallback; }
    }

    private void addSimpleStep(WakeTask draft, Button ownerButton, AutomationStep step) {
        draft.steps.add(step);
        ownerButton.setText(stepButtonText(draft));
        showStepManager(draft, ownerButton);
    }

    private void chooseApp(WakeTask draft, Button appButton) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<ApplicationInfo> launchable = new ArrayList<>();

        for (ApplicationInfo app : installed) {
            if (!app.packageName.equals(getPackageName())
                    && pm.getLaunchIntentForPackage(app.packageName) != null) {
                launchable.add(app);
            }
        }

        launchable.sort(Comparator.comparing(
                app -> pm.getApplicationLabel(app).toString().toLowerCase(Locale.getDefault())
        ));

        String[] labels = new String[launchable.size()];
        for (int i = 0; i < launchable.size(); i++) {
            labels[i] = pm.getApplicationLabel(launchable.get(i)).toString();
        }

        new AlertDialog.Builder(this)
                .setTitle("选择目标 App")
                .setItems(labels, (dialog, which) -> {
                    ApplicationInfo app = launchable.get(which);
                    draft.packageName = app.packageName;
                    draft.appName = pm.getApplicationLabel(app).toString();
                    appButton.setText("目标 App：" + draft.appName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void persist() {
        TaskStore.save(this, tasks);
        for (WakeTask task : tasks) {
            AlarmScheduler.schedule(this, task);
        }
    }
}
