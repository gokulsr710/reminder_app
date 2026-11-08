package com.daybell.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONObject;

/** Rings an alarm: sound, vibration and spoken reminder, with a full-screen alarm page. */
public class AlarmService extends Service {
    static final String ACTION_RING = "com.daybell.app.RING";
    static final String ACTION_DONE = "com.daybell.app.DONE";
    static final String ACTION_SNOOZE = "com.daybell.app.SNOOZE";
    static final String ACTION_DISMISS = "com.daybell.app.DISMISS";
    static final String ACTION_CLOSED = "com.daybell.app.CLOSED";
    static final String CH_ALARM = "alarms";
    static final String CH_MISSED = "missed";
    static final int NOTIF_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady;
    private PowerManager.WakeLock wake;
    private String curId, curDay, curTitle, phrase;
    private boolean curTest, sound, voice, vibrate, ringing;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_RING.equals(action)) {
            ring(intent.getExtras());
        } else if (ACTION_DONE.equals(action)) {
            if (curId != null && !curTest) Store.markDone(this, curId, curDay);
            finishAlarm();
        } else if (ACTION_SNOOZE.equals(action)) {
            if (curId != null && !curTest) { Scheduler.snooze(this, curId, curDay, 5); }
            finishAlarm();
        } else {
            finishAlarm();
        }
        return START_NOT_STICKY;
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH_ALARM, "Task alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings when a task is due");
        ch.setSound(null, null); // the app plays its own alarm sound
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
        NotificationChannel m = new NotificationChannel(CH_MISSED, "Missed reminders", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(m);
    }

    private void ring(Bundle ex) {
        if (ex == null) { stopSelf(); return; }
        stopRinging();
        curId = ex.getString("id", "");
        curDay = ex.getString("day", LocalDate.now().toString());
        curTest = ex.getBoolean("test", false);

        JSONObject s = Store.settings(this);
        sound = s.optBoolean("sound", true);
        voice = s.optBoolean("voice", true);
        vibrate = s.optBoolean("vibrate", true);

        String timeLabel;
        String sub;
        if (curTest) {
            curTitle = "Test reminder";
            timeLabel = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
            sub = "This is how your reminders will ring.";
            phrase = "Daybell reminder. This is a test.";
        } else {
            JSONObject t = Store.findTask(this, curId);
            Scheduler.markFired(this, curId, curDay);
            Scheduler.rescheduleAll(this);
            if (t == null) { stopSelf(); return; }
            curTitle = t.optString("title", "Reminder");
            LocalTime lt;
            try { lt = LocalTime.parse(t.optString("time")); } catch (Exception e) { lt = LocalTime.now(); }
            timeLabel = lt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
            long mins = Math.round((Scheduler.dueMillis(t, LocalDate.parse(curDay)) - System.currentTimeMillis()) / 60000.0);
            if (mins > 0) {
                sub = "Starts in " + mins + (mins == 1 ? " minute" : " minutes");
                phrase = "Reminder. " + curTitle + ". In " + mins + (mins == 1 ? " minute." : " minutes.");
            } else {
                sub = mins == 0 ? "Starting now" : "Was due " + (-mins) + " min ago";
                phrase = "Reminder. " + curTitle + ". It's time now.";
            }
        }

        ensureChannels(this);
        Intent full = new Intent(this, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("title", curTitle).putExtra("time", timeLabel).putExtra("sub", sub).putExtra("test", curTest);
        PendingIntent fullPi = PendingIntent.getActivity(this, 2, full, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(curTitle)
                .setContentText(timeLabel + " · " + sub)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setColor(0xFF1E7356)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .setDeleteIntent(svc(ACTION_DISMISS, 13));
        if (!curTest) {
            b.addAction(new Notification.Action.Builder(null, "Done", svc(ACTION_DONE, 10)).build());
            b.addAction(new Notification.Action.Builder(null, "Snooze 5 min", svc(ACTION_SNOOZE, 11)).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Dismiss", svc(ACTION_DISMISS, 12)).build());

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, b.build());
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daybell:alarm");
        wake.acquire(3 * 60_000L);

        ringing = true;
        if (sound) startSound();
        if (vibrate) startVibration();
        if (voice) startVoice();
        // Stop ringing after 2 minutes and leave a "missed" note.
        handler.postDelayed(this::timeout, curTest ? 20_000L : 120_000L);
    }

    private PendingIntent svc(String action, int code) {
        Intent i = new Intent(this, AlarmService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void startSound() {
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
        }
    }

    private void startVibration() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 600, 400, 600, 1200};
        v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
    }

    private void startVoice() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            ttsReady = true;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setSpeechRate(0.95f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { handler.post(() -> resumeSound()); }
                @Override public void onError(String id) { handler.post(() -> resumeSound()); }
            });
            handler.postDelayed(speakLoop, sound ? 2500 : 300);
        });
    }

    private final Runnable speakLoop = new Runnable() {
        @Override public void run() {
            if (!ringing || !ttsReady) return;
            if (player != null && player.isPlaying()) player.pause();
            tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "daybell");
            handler.postDelayed(this, 15_000L);
        }
    };

    private void resumeSound() {
        if (ringing && player != null && !player.isPlaying()) {
            try { player.start(); } catch (Exception ignored) {}
        }
    }

    private void timeout() {
        if (!ringing) return;
        boolean wasTest = curTest;
        String title = curTitle;
        finishAlarm();
        if (!wasTest) {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification n = new Notification.Builder(this, CH_MISSED)
                    .setSmallIcon(R.drawable.ic_stat_bell)
                    .setContentTitle("Missed reminder")
                    .setContentText(title)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build();
            getSystemService(NotificationManager.class).notify(title.hashCode(), n);
        }
    }

    private void stopRinging() {
        ringing = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; ttsReady = false; }
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.cancel();
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
    }

    private void finishAlarm() {
        stopRinging();
        Scheduler.rescheduleAll(this);
        sendBroadcast(new Intent(ACTION_CLOSED).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        curId = null;
    }

    @Override public void onDestroy() {
        stopRinging();
        super.onDestroy();
    }
}
