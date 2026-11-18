package com.twopercent.screenshot;

import android.content.Context;

/** Color palettes. Index is stored in Prefs.THEME. */
final class AppTheme {
    private AppTheme() {}

    static final String[] NAMES = {
            "Midnight", "Dark Red", "Orange & Black", "Marvel Blue", "Light", "Transparent"
    };

    // bg, surface, accent, text, subtext
    private static final int[][] C = {
            {0xFF0D1117, 0xFF161B22, 0xFF58A6FF, 0xFFE6EDF3, 0xFF8B949E},
            {0xFF140606, 0xFF230B0B, 0xFFE53935, 0xFFFFEAEA, 0xFFC59A9A},
            {0xFF0A0A0A, 0xFF1A1A1A, 0xFFFF8C1A, 0xFFFFF3E6, 0xFFBFA382},
            {0xFF07142B, 0xFF0F2447, 0xFF2D8CFF, 0xFFEAF2FF, 0xFF8FAAD1},
            {0xFFF5F5F7, 0xFFFFFFFF, 0xFFD32F2F, 0xFF1B1B1F, 0xFF6B6B73},
            // Transparent: floating panel/popups are see-through, app screens stay dark
            {0xFF0A0A0A, 0xFF1A1A1A, 0xFF39FF14, 0xFFFFFFFF, 0xFFB5B5B5},
    };

    static final String[] FRAME_NAMES = {
            "Match theme", "Neon green", "Cyan", "Magenta", "Yellow", "Red", "Orange"
    };
    private static final int[] FRAME_COLORS = {
            0, 0xFF39FF14, 0xFF00E5FF, 0xFFFF2BD6, 0xFFFFE600, 0xFFFF3B30, 0xFFFF8C1A
    };

    static boolean transparent(Context c) { return Prefs.i(c, Prefs.THEME, 2) == 5; }

    /** Background for floating panel and popups (see-through in the Transparent theme). */
    static int panelBg(Context c) { return transparent(c) ? 0x55000000 : surface(c); }

    /** Color of the scroll-area border. */
    static int frameColor(Context c) {
        int i = Prefs.i(c, Prefs.FRAME_COLOR, 0);
        if (i <= 0 || i >= FRAME_COLORS.length) return accent(c);
        return FRAME_COLORS[i];
    }

    private static int[] p(Context c) {
        int i = Prefs.i(c, Prefs.THEME, 2);
        if (i < 0 || i >= C.length) i = 0;
        return C[i];
    }

    static int bg(Context c) { return p(c)[0]; }
    static int surface(Context c) { return p(c)[1]; }
    static int accent(Context c) { return p(c)[2]; }
    static int text(Context c) { return p(c)[3]; }
    static int sub(Context c) { return p(c)[4]; }
    /** Readable text color on top of the accent. */
    static int onAccent(Context c) {
        int a = accent(c);
        double lum = (0.299 * ((a >> 16) & 255) + 0.587 * ((a >> 8) & 255) + 0.114 * (a & 255)) / 255.0;
        return lum > 0.6 ? 0xFF000000 : 0xFFFFFFFF;
    }
}
