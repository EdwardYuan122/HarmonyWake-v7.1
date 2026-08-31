package com.oai.harmonywake;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WakeTask {

    public static final int ALL_DAYS_MASK = 0x7F;

    public int id, hour, minute, screenMinutes;
    public String packageName, appName;
    public boolean enabled;
    /** Calendar.SUNDAY=1 对应 bit0，... Calendar.SATURDAY=7 对应 bit6。 */
    public int daysMask;
    public List<AutomationStep> steps;

    public WakeTask(int id, int hour, int minute, String packageName, String appName,
                    int screenMinutes, boolean enabled) {
        this(id, hour, minute, packageName, appName, screenMinutes, enabled,
                ALL_DAYS_MASK, new ArrayList<>());
    }

    public WakeTask(int id, int hour, int minute, String packageName, String appName,
                    int screenMinutes, boolean enabled, int daysMask,
                    List<AutomationStep> steps) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.packageName = packageName;
        this.appName = appName;
        this.screenMinutes = screenMinutes;
        this.enabled = enabled;
        this.daysMask = daysMask == 0 ? ALL_DAYS_MASK : (daysMask & ALL_DAYS_MASK);
        this.steps = steps == null ? new ArrayList<>() : steps;
    }

    public boolean runsOnCalendarDay(int calendarDayOfWeek) {
        if (calendarDayOfWeek < 1 || calendarDayOfWeek > 7) return false;
        return (daysMask & (1 << (calendarDayOfWeek - 1))) != 0;
    }

    public WakeTask copy() {
        List<AutomationStep> copied = new ArrayList<>();
        if (steps != null) {
            for (AutomationStep step : steps) {
                if (step != null) copied.add(step.copy());
            }
        }
        return new WakeTask(id, hour, minute, packageName, appName,
                screenMinutes, enabled, daysMask, copied);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("packageName", packageName == null ? "" : packageName);
        o.put("appName", appName == null ? "" : appName);
        o.put("screenMinutes", screenMinutes);
        o.put("enabled", enabled);
        o.put("daysMask", daysMask);

        JSONArray actions = new JSONArray();
        if (steps != null) {
            for (AutomationStep step : steps) {
                if (step != null) actions.put(step.toJson());
            }
        }
        o.put("steps", actions);
        return o;
    }

    public static WakeTask fromJson(JSONObject o) throws JSONException {
        List<AutomationStep> steps = new ArrayList<>();
        JSONArray actions = o.optJSONArray("steps");
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                AutomationStep step = AutomationStep.fromJson(actions.optJSONObject(i));
                if (step != null && step.type != null && !step.type.isEmpty()) steps.add(step);
            }
        }

        return new WakeTask(
                o.getInt("id"),
                o.getInt("hour"),
                o.getInt("minute"),
                o.optString("packageName", ""),
                o.optString("appName", ""),
                o.optInt("screenMinutes", 10),
                o.optBoolean("enabled", true),
                o.optInt("daysMask", ALL_DAYS_MASK),
                steps
        );
    }
}
