package com.twopercent.screenshot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int bg = AppTheme.bg(this), surface = AppTheme.surface(this), accent = AppTheme.accent(this);
        int text = AppTheme.text(this), sub = AppTheme.sub(this);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(32));
        sv.addView(root);

        String ver = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName + " (build " + pi.getLongVersionCode() + ")";
        } catch (Exception ignored) { }

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(140), dp(140));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        logo.setLayoutParams(llp);
        root.addView(logo);

        TextView title = tv("2%ScreenShot v" + ver.split(" ")[0], 28, accent, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView v = tv("Version " + ver + " \u2022 Android 10+", 14, sub, false);
        v.setGravity(Gravity.CENTER);
        root.addView(v);

        root.addView(card(surface, text,
                "What it does",
                "A floating screenshot bubble made for Marvel Contest of Champions. "
                        + "Take a normal screenshot, or a long scrolling screenshot of rosters, "
                        + "Abyss paths, quest maps and profiles. You scroll the game yourself; the app never touches it."));
        root.addView(card(surface, text,
                "How to use",
                "1. Tap Start in the app and allow screen capture.\n"
                        + "2. Open MCOC. The floating panel (or the notification drop-down) has Screenshot and Scroll shot.\n"
                        + "3. Scroll shot: a themed border marks the scroll area. Drag the \u25B2 \u25BC handles to fit it "
                        + "(leave out fixed headers and footers), then tap Capture section.\n"
                        + "4. The border flashes when a section is saved. Scroll to the next part (keep a little overlap) "
                        + "and tap Capture section again. Tap Finish & save to stitch them.\n"
                        + "   Timer shots: it counts down and takes each shot for you while you scroll. Tap Done when finished.\n"
                        + "   Works in landscape too, and in sideways (horizontal) lists: use the Direction button.\n"
                        + "5. Tap the panel header to collapse it. Settings on the panel opens this app from inside MCOC.\n"
                        + "6. Screenshots are saved in Pictures, in the folder you pick in settings."));
        root.addView(card(surface, text,
                "Privacy",
                "Everything stays on your phone. Screenshots only leave the device if you "
                        + "share them to LINE or turn on the optional upload URL. The app never taps, "
                        + "swipes or controls the game; it only takes pictures of your screen."));
        root.addView(card(surface, text,
                "Not affiliated",
                "This is an unofficial fan tool. Marvel Contest of Champions is a trademark of "
                        + "its owners. 2%ScreenShot is not affiliated with Kabam, Marvel or LINE."));
        root.addView(card(surface, text,
                "Credits",
                "Made by KRANK • 2% Projects"));

        Button game = btn("Open Contest of Champions website", accent);
        game.setOnClickListener(x -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://playcontestofchampions.com/"))));
        root.addView(game);

        Button back = btn("Back", surface);
        back.setTextColor(text);
        back.setOnClickListener(x -> finish());
        root.addView(back);

        setContentView(sv);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView tv(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private View card(int surface, int text, String head, String body) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(surface);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        c.addView(tv(head, 16, AppTheme.accent(this), true));
        TextView t = tv(body, 14, text, false);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t);
        return c;
    }

    private Button btn(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(AppTheme.onAccent(this));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }
}
