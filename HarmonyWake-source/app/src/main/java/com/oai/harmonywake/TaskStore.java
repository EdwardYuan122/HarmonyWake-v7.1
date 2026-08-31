package com.oai.harmonywake;

import android.content.Context;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class TaskStore {
    private static final String PREF="wake_tasks", KEY="tasks";
    public static List<WakeTask> load(Context c){
        List<WakeTask> r=new ArrayList<>();
        String raw=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]");
        try{ JSONArray a=new JSONArray(raw); for(int i=0;i<a.length();i++) r.add(WakeTask.fromJson(a.getJSONObject(i))); }
        catch(Exception ignored){} return r;
    }
    public static void save(Context c,List<WakeTask> ts){
        JSONArray a=new JSONArray(); try{ for(WakeTask t:ts)a.put(t.toJson()); }catch(Exception ignored){}
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply();
    }
    public static WakeTask find(Context c,int id){ for(WakeTask t:load(c)) if(t.id==id)return t; return null; }
}
