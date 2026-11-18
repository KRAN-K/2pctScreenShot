package com.twopercent.screenshot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Dark "share with" picker (the system share sheet cannot be recoloured). */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Uri uri = getIntent().getData();
        if (uri == null) { finish(); return; }

        final boolean view = "view".equals(getIntent().getStringExtra("mode"));
        final Intent send;
        if (view) {
            send = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*");
        } else {
            send = new Intent(Intent.ACTION_SEND);
            send.setType("image/*");
            send.putExtra(Intent.EXTRA_STREAM, uri);
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        int bg = 0xFF05070A, card = 0xFF11161D, txt = 0xFFE6EDF3, sub = 0xFF8B949E;
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xE6000000));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(40), dp(20), dp(40));
        root.setOnClickListener(v -> finish());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), 0xFF30363D);
        box.setBackground(gd);
        box.setClickable(true);

        TextView title = new TextView(this);
        title.setText(view ? "Open screenshot with…" : "Share screenshot with…");
        title.setTextColor(txt);
        title.setTextSize(18);
        title.setPadding(dp(4), 0, 0, dp(10));
        box.addView(title);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(send, 0);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final ResolveInfo ri : apps) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(card);
            rg.setCornerRadius(dp(12));
            row.setBackground(rg);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView tv = new TextView(this);
            tv.setText(ri.loadLabel(pm));
            tv.setTextColor(txt);
            tv.setTextSize(16);
            tv.setPadding(dp(14), 0, 0, 0);
            row.addView(tv);
            row.setOnClickListener(v -> {
                Intent go = new Intent(send);
                go.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                try { startActivity(go); } catch (Exception ignored) { }
                finish();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
        if (list.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText("No apps found to share with.");
            none.setTextColor(sub);
            list.addView(none);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(16);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xFF30363D);
        cg.setCornerRadius(dp(12));
        cancel.setBackground(cg);
        cancel.setOnClickListener(v -> finish());
        box.addView(cancel, new LinearLayout.LayoutParams(-1, -2));

        root.addView(box, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
