package com.daybell.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen alarm page, shown over the lock screen. */
public class AlarmActivity extends Activity {
    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build(getIntent());
        IntentFilter f = new IntentFilter(AlarmService.ACTION_CLOSED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closer, f);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        build(intent);
    }

    private void build(Intent in) {
        boolean dark = Store.isDark(this);
        int bg = dark ? 0xFF0D0D0D : 0xFFF3F3F1;
        int ink = dark ? 0xFFECECEC : 0xFF141414;
        int muted = dark ? 0xFF999999 : 0xFF666664;
        int accent = dark ? 0xFFECECEC : 0xFF1B1B1B;
        int onAccent = dark ? 0xFF111111 : 0xFFFFFFFF;
        int line = dark ? 0xFF2A2A2A : 0xFFDCDCDA;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(24), dp(72), dp(24), dp(40));
        root.setFitsSystemWindows(true);

        TextView bell = new TextView(this);
        bell.setText("🔔");
        bell.setTextSize(44);
        bell.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(dark ? 0xFF2A2A2A : 0xFFE4E4E2);
        bell.setBackground(circle);
        root.addView(bell, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView time = text(in.getStringExtra("time"), 52, ink, true);
        time.setPadding(0, dp(28), 0, dp(6));
        root.addView(time);
        TextView title = text(in.getStringExtra("title"), 24, ink, true);
        root.addView(title);
        TextView sub = text(in.getStringExtra("sub"), 16, muted, false);
        sub.setPadding(0, dp(8), 0, 0);
        root.addView(sub);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        boolean test = in.getBooleanExtra("test", false);
        if (!test) {
            root.addView(button("Mark as done", accent, onAccent, 0, AlarmService.ACTION_DONE));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(10), 0, 0);
            Button sn = button("Snooze 5 min", 0, ink, line, AlarmService.ACTION_SNOOZE);
            Button di = button("Dismiss", 0, ink, line, AlarmService.ACTION_DISMISS);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f);
            lp.setMarginEnd(dp(10));
            row.addView(sn, lp);
            row.addView(di, new LinearLayout.LayoutParams(0, dp(58), 1f));
            root.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            root.addView(button("Dismiss", accent, onAccent, 0, AlarmService.ACTION_DISMISS));
        }
        setContentView(root);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private Button button(String label, int fill, int textColor, int stroke, String action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(17);
        btn.setTextColor(textColor);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(fill == 0 ? Color.TRANSPARENT : fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        btn.setBackground(d);
        btn.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        btn.setOnClickListener(v -> {
            startService(new Intent(this, AlarmService.class).setAction(action));
            finish();
        });
        return btn;
    }

    @Override public void onBackPressed() {
        // Back keeps the alarm on screen; use a button to stop it.
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(closer); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
