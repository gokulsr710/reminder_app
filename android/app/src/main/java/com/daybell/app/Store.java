package com.daybell.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native copy of the app's tasks and settings, plus alarm bookkeeping. */
final class Store {
    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences("daybell", Context.MODE_PRIVATE);
    }

    static JSONObject obj(Context c, String key) {
        try { return new JSONObject(prefs(c).getString(key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static JSONArray arr(Context c, String key) {
        try { return new JSONArray(prefs(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void put(Context c, String key, Object value) {
        prefs(c).edit().putString(key, value.toString()).commit();
    }

    static JSONObject state(Context c) { return obj(c, "state"); }

    static JSONObject settings(Context c) {
        JSONObject s = state(c).optJSONObject("settings");
        return s != null ? s : new JSONObject();
    }

    static JSONObject findTask(Context c, String id) {
        JSONArray tasks = state(c).optJSONArray("tasks");
        if (tasks == null) return null;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t != null && id.equals(t.optString("id"))) return t;
        }
        return null;
    }

    /** Marks a task done on a day in the native copy and queues the change for the web app. */
    static synchronized void markDone(Context c, String id, String day) {
        try {
            JSONObject st = state(c);
            JSONArray tasks = st.optJSONArray("tasks");
            if (tasks != null) {
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject t = tasks.getJSONObject(i);
                    if (!id.equals(t.optString("id"))) continue;
                    String rep = t.optString("repeat", "none");
                    if (!rep.isEmpty() && !"none".equals(rep)) {
                        JSONObject dd = t.optJSONObject("doneDates");
                        if (dd == null) { dd = new JSONObject(); t.put("doneDates", dd); }
                        dd.put(day, true);
                    } else {
                        t.put("done", true);
                    }
                }
                put(c, "state", st);
            }
            JSONArray pending = arr(c, "pending");
            pending.put(new JSONObject().put("type", "done").put("id", id).put("day", day));
            put(c, "pending", pending);
        } catch (Exception ignored) {}
    }

    static synchronized String takePending(Context c) {
        String s = arr(c, "pending").toString();
        put(c, "pending", "[]");
        return s;
    }
}
