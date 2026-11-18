package com.twopercent.screenshot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 100;

    private LinearLayout root;
    private TextView status;
    private Button startBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        int bg = AppTheme.bg(this);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(32));
        sv.addView(root);

        String ver = "";
        try { ver = " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        TextView title = text("2%ScreenShot" + ver, 28, AppTheme.accent(this), true);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        head.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title);
        root.addView(head);
        root.addView(text("Long screenshots for Contest of Champions", 14, AppTheme.sub(this), false));

        status = text("", 14, AppTheme.text(this), false);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        startBtn = button("Start capture bubble", true);
        startBtn.setOnClickListener(v -> onStartClicked());
        root.addView(startBtn);


        // ---- Capture
        section("Controls");
        addSwitch("Show controls in the top notification bar", Prefs.NOTIF_BAR, true);
        addSwitch("Show floating control panel", Prefs.BUBBLE, true);
        addSwitch("Show preview popup when done", Prefs.PREVIEW, true);
        root.addView(text("The notification drop-down always has Screenshot, Scroll shot and Quit buttons, "
                + "so you can capture without the floating panel. Changes to the panel apply the next time "
                + "you start capture.", 12, AppTheme.sub(this), false));

        section("Capture");
        addNumber("Delay before capture (seconds)", Prefs.DELAY, 0);
        addSwitch("Horizontal scroll (sideways lists)", Prefs.HORIZONTAL, false);
        addNumber("Seconds between timed shots (3-20; more time = slower, safer scrolling)", Prefs.TIMER_SEC, 5);
        addNumber("Max screens per long shot", Prefs.MAX_FRAMES, 20);
        root.addView(text("Scroll shot: the scroll area is set on screen with the \u25B2 \u25BC handles "
                + "when you start a scroll shot.", 12, AppTheme.sub(this), false));

        // ---- Output
        section("Saving");
        addText("Folder (inside Pictures)", Prefs.FOLDER, "MCOC");
        addText("File name prefix", Prefs.PREFIX, "mcoc");
        addSwitch("Save as PNG (off = JPEG)", Prefs.FORMAT_PNG, true);
        addNumber("JPEG quality (40-100)", Prefs.QUALITY, 92);

        // ---- LINE
        section("LINE & upload");
        addSwitch("Open LINE share right after capture", Prefs.LINE_AUTO, false);
        root.addView(text("LINE cannot be sent to a specific chat folder from outside the app. "
                + "The share opens LINE's own picker so you choose the chat or Keep.",
                12, AppTheme.sub(this), false));
        addText("Upload URL (optional, your own server)", Prefs.UPLOAD_URL, "");
        addText("Upload token (optional)", Prefs.UPLOAD_TOKEN, "");
        addSwitch("Upload automatically after capture", Prefs.UPLOAD_AUTO, false);

        // ---- Theme
        section("Theme");
        Button theme = button("Theme: " + AppTheme.NAMES[Math.min(Prefs.i(this, Prefs.THEME, 2),
                AppTheme.NAMES.length - 1)], false);
        theme.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Choose theme")
                .setItems(AppTheme.NAMES, (d, which) -> {
                    Prefs.putI(this, Prefs.THEME, which);
                    recreate();
                }).show());
        root.addView(theme);

        Button fc = button("Scroll area color: " + AppTheme.FRAME_NAMES[Math.min(Prefs.i(this, Prefs.FRAME_COLOR, 0),
                AppTheme.FRAME_NAMES.length - 1)], false);
        fc.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Scroll area color")
                .setItems(AppTheme.FRAME_NAMES, (d, which) -> {
                    Prefs.putI(this, Prefs.FRAME_COLOR, which);
                    recreate();
                }).show());
        root.addView(fc);

        Button folder = button("Open screenshots folder", false);
        folder.setOnClickListener(v -> ImageSaver.openFolder(this));
        root.addView(folder);

        Button about = button("About", false);
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        root.addView(about);

        setContentView(sv);
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean run = CaptureService.running;
        status.setText("Bubble: " + (run ? "running" : "stopped")
                + "\nOverlay permission: " + (overlay ? "\u2713 granted" : "\u2717 needed"));
        startBtn.setText(run ? "Bubble is running (tap to restart)" : "Start capture bubble");
    }

    private void onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\", then tap Start again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_QUIT));
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null) {
                Intent i = new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra("code", res)
                        .putExtra("data", data);
                startForegroundService(i);
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------ view helpers

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private void section(String name) {
        TextView t = text(name.toUpperCase(), 13, AppTheme.accent(this), true);
        t.setPadding(0, dp(24), 0, dp(4));
        root.addView(t);
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (primary) {
            g.setColor(AppTheme.accent(this));
            b.setTextColor(AppTheme.onAccent(this));
        } else {
            g.setColor(AppTheme.surface(this));
            g.setStroke(dp(1), AppTheme.accent(this));
            b.setTextColor(AppTheme.text(this));
        }
        b.setBackground(g);
        b.setTextSize(15);
        b.setSingleLine(false);
        b.setMinHeight(dp(56));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row(String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(AppTheme.surface(this));
        g.setCornerRadius(dp(12));
        r.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        r.addView(text(label, 14, AppTheme.text(this), false));
        root.addView(r);
        return r;
    }

    private static final int ON_GREEN = 0xFF2ECC40;
    private static final int OFF_RED = 0xFFE53935;

    private void addSwitch(String label, String key, boolean def) {
        LinearLayout r = row(label);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        Switch s = new Switch(this);
        boolean on = Prefs.b(this, key, def);
        s.setChecked(on);
        int[][] states = {{android.os.Build.VERSION.SDK_INT >= 0 ? android.R.attr.state_checked : 0}, {}};
        s.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{ON_GREEN, OFF_RED}));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));

        TextView state = new TextView(this);
        state.setTextSize(16);
        state.setTypeface(null, android.graphics.Typeface.BOLD);
        state.setPadding(dp(14), 0, 0, 0);
        paintState(state, on);

        s.setOnCheckedChangeListener((CompoundButton v, boolean isOn) -> {
            Prefs.putB(this, key, isOn);
            paintState(state, isOn);
        });
        line.addView(s);
        line.addView(state);
        r.addView(line);
    }

    private void paintState(TextView t, boolean on) {
        t.setText(on ? "ON" : "OFF");
        t.setTextColor(on ? ON_GREEN : OFF_RED);
    }

    private void addNumber(String label, String key, int def) {
        LinearLayout r = row(label);
        EditText e = edit(String.valueOf(Prefs.i(this, key, def)));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.addTextChangedListener(new IntWatcher(key));
        r.addView(e);
    }

    private void addText(String label, String key, String def) {
        LinearLayout r = row(label);
        EditText e = edit(Prefs.s(this, key, def));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.addTextChangedListener(new StrWatcher(key));
        r.addView(e);
    }

    private EditText edit(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(AppTheme.accent(this));
        e.setGravity(Gravity.START);
        return e;
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        abstract void changed(String s);
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { changed(s.toString()); }
    }

    private final class IntWatcher extends SimpleWatcher {
        private final String key;
        IntWatcher(String k) { key = k; }
        @Override void changed(String s) {
            try { Prefs.putI(MainActivity.this, key, Integer.parseInt(s.trim())); }
            catch (NumberFormatException ignored) { }
        }
    }

    private final class StrWatcher extends SimpleWatcher {
        private final String key;
        StrWatcher(String k) { key = k; }
        @Override void changed(String s) { Prefs.putS(MainActivity.this, key, s); }
    }
}
