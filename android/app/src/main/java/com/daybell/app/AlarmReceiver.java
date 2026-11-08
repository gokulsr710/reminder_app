package com.daybell.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Intent s = new Intent(c, AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtras(intent);
        c.startForegroundService(s);
    }
}
