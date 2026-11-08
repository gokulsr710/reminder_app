package com.daybell.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Schedules one system alarm per task: its next un-done, un-rung occurrence. */
final class Scheduler {
    private Scheduler() {}

    static final String ACTION_FIRE = "com.daybell.app.FIRE";

    static boolean occursOn(JSONObject t, LocalDate day) {
        LocalDate start;
        try { start = LocalDate.parse(t.optString("date")); } catch (Exception e) { return false; }
        if (day.equals(start)) return true;
        String rep = t.optString("repeat", "none");
        if (day.isBefore(start)) return false;
        int wd = day.getDayOfWeek().getValue(); // 1 = Monday
        switch (rep) {
            case "daily": return true;
            case "weekdays": return wd <= 5;
            case "weekly": return wd == start.getDayOfWeek().getValue();
            default: return false;
        }
    }

    static boolean isDone(JSONObject t, LocalDate day) {
        String rep = t.optString("repeat", "none");
        if (!rep.isEmpty() && !"none".equals(rep)) {
            JSONObject dd = t.optJSONObject("doneDates");
            return dd != null && dd.optBoolean(day.toString(), false);
        }
        return t.optBoolean("done", false);
    }

    static long dueMillis(JSONObject t, LocalDate day) {
        LocalTime time;
        try { time = LocalTime.parse(t.optString("time")); } catch (Exception e) { return -1; }
        return day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Returns {triggerMillis, day} or null. */
    static Object[] next(Context c, JSONObject t) {
        String id = t.optString("id");
        long now = System.currentTimeMillis();
        JSONObject fired = Store.obj(c, "fired");
        JSONObject snooze = Store.obj(c, "snooze").optJSONObject(id);
        int lead = t.optInt("lead", 0);
        LocalDate today = LocalDate.now();
        LocalDate start;
        try { start = LocalDate.parse(t.optString("date")); } catch (Exception e) { return null; }
        if (start.isBefore(today.minusDays(1))) start = today.minusDays(1);
        for (int i = 0; i < 800; i++) {
            LocalDate d = start.plusDays(i);
            if (!occursOn(t, d) || isDone(t, d)) continue;
            String ds = d.toString();
            long trig;
            if (snooze != null && ds.equals(snooze.optString("day"))) {
                trig = snooze.optLong("until");
            } else {
                if (fired.has(id + "|" + ds)) continue;
                long due = dueMillis(t, d);
                if (due < 0) return null;
                trig = due - lead * 60_000L;
            }
            if (trig <= now) continue;
            return new Object[]{trig, ds};
        }
        return null;
    }

    private static PendingIntent firePi(Context c, String id, String day, boolean test, int flags) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("daybell://alarm/" + Uri.encode(id)))
                .putExtra("id", id).putExtra("day", day).putExtra("test", test);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent showPi(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void set(Context c, long when, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi(c)), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static synchronized void rescheduleAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        JSONArray prev = Store.arr(c, "scheduled");
        for (int i = 0; i < prev.length(); i++) {
            PendingIntent old = firePi(c, prev.optString(i), "", false, PendingIntent.FLAG_NO_CREATE);
            if (old != null) { am.cancel(old); old.cancel(); }
        }
        JSONArray now = new JSONArray();
        JSONArray tasks = Store.state(c).optJSONArray("tasks");
        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject t = tasks.optJSONObject(i);
                if (t == null) continue;
                Object[] n = next(c, t);
                if (n == null) continue;
                String id = t.optString("id");
                set(c, (Long) n[0], firePi(c, id, (String) n[1], false, PendingIntent.FLAG_UPDATE_CURRENT));
                now.put(id);
            }
        }
        Store.put(c, "scheduled", now);
        pruneFired(c);
    }

    static void scheduleTest(Context c) {
        set(c, System.currentTimeMillis() + 10_000L, firePi(c, "__test", LocalDate.now().toString(), true, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    static synchronized void markFired(Context c, String id, String day) {
        try {
            JSONObject fired = Store.obj(c, "fired");
            fired.put(id + "|" + day, System.currentTimeMillis());
            Store.put(c, "fired", fired);
            JSONObject sn = Store.obj(c, "snooze");
            sn.remove(id);
            Store.put(c, "snooze", sn);
        } catch (Exception ignored) {}
    }

    static synchronized void snooze(Context c, String id, String day, int minutes) {
        try {
            JSONObject sn = Store.obj(c, "snooze");
            sn.put(id, new JSONObject().put("day", day).put("until", System.currentTimeMillis() + minutes * 60_000L));
            Store.put(c, "snooze", sn);
        } catch (Exception ignored) {}
    }

    private static void pruneFired(Context c) {
        JSONObject fired = Store.obj(c, "fired");
        long cutoff = System.currentTimeMillis() - 3L * 24 * 3600_000L;
        boolean changed = false;
        for (Iterator<String> it = fired.keys(); it.hasNext(); ) {
            String k = it.next();
            if (fired.optLong(k) < cutoff) { it.remove(); changed = true; }
        }
        if (changed) Store.put(c, "fired", fired);
    }
}
