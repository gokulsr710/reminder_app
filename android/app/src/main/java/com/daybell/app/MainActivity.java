package com.daybell.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private FrameLayout root;

    /** Colours the system bars and the page background for the chosen theme. */
    private void applyBars() {
        boolean dark = Store.isDark(this);
        int bg = dark ? 0xFF0E1412 : 0xFFF2F4F0;
        int bar = dark ? 0xFF17201C : 0xFFFFFFFF;
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bar);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(dark ? flags & ~light : flags | light);
        }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyBars();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AlarmService.ensureChannels(this);

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applyBars();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "DaybellNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("app.daybell.local".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.loadDataWithBaseURL("https://app.daybell.local/", page(), "text/html", "utf-8", null);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private String page() {
        String body;
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            body = "<p>Could not load Daybell.</p>";
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<style>body{margin:0;font-size:14px}img{max-width:100%}[hidden]{display:none!important}</style>"
                + "</head><body>" + body + "</body></html>";
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.daybellResume && window.daybellResume()", null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (web != null) web.evaluateJavascript("window.daybellResume && window.daybellResume()", null);
    }

    private JSONObject permissionStatus() {
        JSONObject o = new JSONObject();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            o.put("notifications", nm.areNotificationsEnabled());
            AlarmManager am = getSystemService(AlarmManager.class);
            o.put("exactAlarms", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
            o.put("fullScreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
            PowerManager pm = getSystemService(PowerManager.class);
            o.put("battery", pm.isIgnoringBatteryOptimizations(getPackageName()));
        } catch (Exception ignored) {}
        return o;
    }

    private void openNextPermission() {
        JSONObject st = permissionStatus();
        Uri pkg = Uri.parse("package:" + getPackageName());
        try {
            if (!st.optBoolean("notifications", true)) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                } else {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
            } else if (!st.optBoolean("exactAlarms", true)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
            } else if (!st.optBoolean("fullScreen", true)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg));
            } else if (!st.optBoolean("battery", true)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        }
    }

    /** Called from the page's JavaScript. */
    private class Bridge {
        @JavascriptInterface public void sync(String json) {
            Store.put(MainActivity.this, "state", json);
            Scheduler.rescheduleAll(MainActivity.this);
        }
        @JavascriptInterface public void setTheme(String theme) {
            try {
                JSONObject st = Store.state(MainActivity.this);
                JSONObject s = st.optJSONObject("settings");
                if (s == null) { s = new JSONObject(); st.put("settings", s); }
                s.put("theme", theme);
                Store.put(MainActivity.this, "state", st);
            } catch (Exception ignored) {}
            runOnUiThread(MainActivity.this::applyBars);
        }
        @JavascriptInterface public String takePending() { return Store.takePending(MainActivity.this); }
        @JavascriptInterface public void testAlarm() { Scheduler.scheduleTest(MainActivity.this); }
        @JavascriptInterface public String permissionStatus() { return MainActivity.this.permissionStatus().toString(); }
        @JavascriptInterface public void openPermissions() { runOnUiThread(MainActivity.this::openNextPermission); }
    }
}
