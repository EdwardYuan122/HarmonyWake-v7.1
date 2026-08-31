package com.oai.harmonywake;

import org.json.JSONException;
import org.json.JSONObject;

public class AutomationStep {

    public static final String WAIT = "wait";
    public static final String CLICK_TEXT = "click_text";
    public static final String TAP_PERCENT = "tap_percent";
    public static final String SWIPE = "swipe";
    public static final String BACK = "back";
    public static final String HOME = "home";
    public static final String INPUT_TEXT = "input_text";
    public static final String WAIT_TEXT = "wait_text";

    public String type;
    public String value;
    public int xPercent;
    public int yPercent;
    public long durationMs;

    public AutomationStep(String type, String value, int xPercent, int yPercent, long durationMs) {
        this.type = type == null ? "" : type;
        this.value = value == null ? "" : value;
        this.xPercent = xPercent;
        this.yPercent = yPercent;
        this.durationMs = durationMs;
    }

    public AutomationStep copy() {
        return new AutomationStep(type, value, xPercent, yPercent, durationMs);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("value", value);
        o.put("xPercent", xPercent);
        o.put("yPercent", yPercent);
        o.put("durationMs", durationMs);
        return o;
    }

    public static AutomationStep fromJson(JSONObject o) {
        if (o == null) return null;
        return new AutomationStep(
                o.optString("type", ""),
                o.optString("value", ""),
                o.optInt("xPercent", 50),
                o.optInt("yPercent", 50),
                o.optLong("durationMs", 500L)
        );
    }

    public String summary() {
        switch (type) {
            case WAIT:
                return "等待 " + Math.max(0L, durationMs) + " ms";
            case CLICK_TEXT:
                return "点击文字：“" + value + "”";
            case TAP_PERCENT:
                return "点击坐标：X " + xPercent + "% / Y " + yPercent + "%";
            case SWIPE:
                return "滑动：" + directionLabel(value);
            case BACK:
                return "返回";
            case HOME:
                return "主页";
            case INPUT_TEXT:
                return "输入文字：“" + value + "”";
            case WAIT_TEXT:
                return "等待文字出现：“" + value + "”";
            default:
                return "未知步骤";
        }
    }

    private String directionLabel(String direction) {
        if ("UP".equals(direction)) return "上滑";
        if ("DOWN".equals(direction)) return "下滑";
        if ("LEFT".equals(direction)) return "左滑";
        if ("RIGHT".equals(direction)) return "右滑";
        return direction == null ? "" : direction;
    }
}
