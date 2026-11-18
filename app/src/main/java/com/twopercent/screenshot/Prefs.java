package com.twopercent.screenshot;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around SharedPreferences. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int i(Context c, String k, int d) { return sp(c).getInt(k, d); }
    static void putI(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    static String s(Context c, String k, String d) { return sp(c).getString(k, d); }
    static void putS(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }
    static boolean b(Context c, String k, boolean d) { return sp(c).getBoolean(k, d); }
    static void putB(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    // Keys
    static final String THEME = "theme";
    static final String DELAY = "delay";            // seconds before capture
    static final String MAX_FRAMES = "max_frames";
    static final String CROP_TOP = "crop_top";      // px of fixed header to ignore when stitching
    static final String CROP_BOTTOM = "crop_bottom";// px of fixed footer to ignore when stitching
    static final String FORMAT_PNG = "format_png";
    static final String QUALITY = "quality";
    static final String FOLDER = "folder";          // subfolder under Pictures
    static final String PREFIX = "prefix";
    static final String LINE_AUTO = "line_auto";    // open LINE share right after capture
    static final String UPLOAD_URL = "upload_url";
    static final String UPLOAD_TOKEN = "upload_token";
    static final String UPLOAD_AUTO = "upload_auto";
    static final String NOTIF_BAR = "notif_bar";     // controls in the top notification area (default on)
    static final String HORIZONTAL = "horizontal";   // scroll sideways
    static final String REVERSE = "reverse";         // auto-record swipe direction: false = up/left, true = down/right
    static final String TIMER_SEC = "timer_sec";     // seconds between timed shots
    static final String FRAME_COLOR = "frame_color";
    static final String REGION_TOP = "region_top";
    static final String REGION_BOTTOM = "region_bottom";
    static final String BUBBLE = "bubble";          // show floating panel
    static final String PREVIEW = "preview";        // show preview popup after capture
}
