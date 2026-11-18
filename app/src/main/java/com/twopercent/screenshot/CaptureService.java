package com.twopercent.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Floating panel + screen capture via MediaProjection. */
public class CaptureService extends Service {
    static final String ACTION_START = "com.twopercent.screenshot.START";
    static final String ACTION_QUIT = "com.twopercent.screenshot.QUIT";
    static final String ACTION_SHARE_LINE = "com.twopercent.screenshot.SHARE_LINE";
    static final String ACTION_SHOT = "com.twopercent.screenshot.SHOT";
    static final String ACTION_SCROLL = "com.twopercent.screenshot.SCROLL";
    static final String ACTION_SEC_CAPTURE = "com.twopercent.screenshot.SEC_CAPTURE";
    static final String ACTION_SEC_FINISH = "com.twopercent.screenshot.SEC_FINISH";
    static final String ACTION_SEC_CANCEL = "com.twopercent.screenshot.SEC_CANCEL";
    static final String ACTION_SHOW_PANEL = "com.twopercent.screenshot.SHOW_PANEL";
    static final String LINE_PACKAGE = "jp.naver.line.android";

    private static final String CH_RUN = "running";
    private static final String CH_QUIET = "running_quiet";
    private static final String CH_RESULT = "results";
    private static final int NOTIF_RUN = 1;

    static volatile boolean running = false;

    private MediaProjection projection;
    private boolean horizontal = false;
    private boolean timerRunning = false;
    private volatile boolean pauseOnly = false;
    private boolean panelHidden = false;
    private TextView timerBtn, dirBtn, revBtn;
    private VirtualDisplay display;
    private ImageReader reader;
    private int w, h, dpi;
    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy = false;
    private volatile boolean stopAuto = false;
    private volatile boolean cancelled = false;
    private Bitmap last;

    // floating panel
    private LinearLayout overlay, bodyMain, bodySection;
    private TextView headerTv, statusTv;
    private boolean collapsed = false;
    private WindowManager.LayoutParams lp;

    // scroll-area frame
    private boolean sectionMode = false;
    private Stitcher section;
    private int sectionCount = 0;
    private int regionTop, regionBottom;   // start / end along the scroll axis
    private FrameView frameView;
    private TextView topHandle, botHandle;
    private WindowManager.LayoutParams frameLp, topLp, botLp;

    // preview popup
    private View previewView;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String a = intent.getAction();
        if (ACTION_QUIT.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(a) && projection == null) {
            startCapture(intent);
            return START_NOT_STICKY;
        }
        if (projection == null) return START_NOT_STICKY;

        if (ACTION_SHOT.equals(a)) singleShot(700);
        else if (ACTION_SCROLL.equals(a)) enterSection();
        else if (ACTION_SEC_CAPTURE.equals(a)) captureSection(700);
        else if (ACTION_SEC_FINISH.equals(a)) finishSection();
        else if (ACTION_SEC_CANCEL.equals(a)) cancelSection();
        else if (ACTION_SHOW_PANEL.equals(a)) { panelHidden = false; ensureOverlay(); setOverlayVisible(true); }
        else if (ACTION_SHARE_LINE.equals(a)) {
            Uri u = intent.getData();
            if (u != null) shareToLine(u);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        createChannels();
        startForeground(NOTIF_RUN, buildRunNotification(0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new StopCallback(), main);
            setupDisplay();
            if (Prefs.b(this, Prefs.BUBBLE, true)) ensureOverlay();
            running = true;
        } catch (Exception e) {
            Toast.makeText(this, "Could not start capture: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (projection == null || wm == null) return;
        main.post(this::onRotate);
    }

    private void onRotate() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        if (dm.widthPixels == w && dm.heightPixels == h) return;
        boolean wasSection = sectionMode;
        if (wasSection) {
            cancelled = true;
            stopAuto = true;
            if (!busy) exitSection();
        }
        w = dm.widthPixels;
        h = dm.heightPixels;
        dpi = dm.densityDpi;
        ImageReader old = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display.resize(w, h, dpi);
        display.setSurface(reader.getSurface());
        if (old != null) old.close();
        last = null;
        if (overlay != null) {
            int ox = lp.x, oy = lp.y;
            removeView(overlay);
            overlay = null;
            ensureOverlay();
            lp.x = Math.max(0, Math.min(ox, w - dp(60)));
            lp.y = Math.max(0, Math.min(oy, h - dp(120)));
            wm.updateViewLayout(overlay, lp);
            if (panelHidden) overlay.setVisibility(View.GONE);
        }
        if (wasSection) toastLong("Screen rotated. Scroll shot cancelled, start it again.");
    }

    @Override
    public void onDestroy() {
        running = false;
        removeView(previewView);
        removeSectionViews();
        removeView(overlay);
        overlay = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void removeView(View v) {
        if (v != null && wm != null) {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- setup

    private void setupDisplay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        w = dm.widthPixels;
        h = dm.heightPixels;
        dpi = dm.densityDpi;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("2pct-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------------------------------------------------------------- floating panel

    private void ensureOverlay() {
        if (overlay != null) return;
        int accent = AppTheme.accent(this);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        final boolean land = w > h;
        overlay.setPadding(dp(10), dp(6), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(AppTheme.panelBg(this));
        bg.setStroke(dp(2), accent);
        bg.setCornerRadius(dp(18));
        overlay.setBackground(bg);

        // header: title (tap = collapse, drag = move) and a close X
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerTv = new TextView(this);
        headerTv.setText("☰  2%ScreenShot");
        headerTv.setTextSize(14);
        headerTv.setTypeface(null, android.graphics.Typeface.BOLD);
        headerTv.setTextColor(accent);
        headerTv.setPadding(dp(4), dp(8), dp(12), dp(8));
        header.addView(headerTv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextSize(18);
        closeX.setTypeface(null, android.graphics.Typeface.BOLD);
        closeX.setTextColor(accent);
        closeX.setGravity(Gravity.CENTER);
        header.addView(closeX, new LinearLayout.LayoutParams(dp(40), dp(40)));
        overlay.addView(header);

        // main buttons
        bodyMain = new LinearLayout(this);
        bodyMain.setOrientation(LinearLayout.VERTICAL);
        TextView shot = pill("📷  Screenshot", true);
        TextView scroll = pill("⇕  Scroll shot", true);
        TextView settings = pill("⚙  Settings", false);
        TextView quit = pill("Quit", false);
        arrange(bodyMain, shot, scroll, settings, quit);
        overlay.addView(bodyMain);

        // scroll-shot buttons
        bodySection = new LinearLayout(this);
        bodySection.setOrientation(LinearLayout.VERTICAL);
        bodySection.setVisibility(View.GONE);
        TextView cap = pill("●  Capture section", true);
        timerBtn = pill("●  Auto-record", true);
        dirBtn = pill(dirLabel(), false);
        revBtn = pill(revLabel(), false);
        TextView fin = pill("✓  Done / Finish", true);
        TextView cancel = pill("Cancel", false);
        arrange(bodySection, cap, timerBtn, revBtn, fin, cancel);
        overlay.addView(bodySection);

        statusTv = new TextView(this);
        statusTv.setText("Ready");
        statusTv.setTextSize(12);
        statusTv.setTextColor(AppTheme.transparent(this) ? 0xFFFFFFFF : AppTheme.sub(this));
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, dp(8), 0, 0);
        overlay.addView(statusTv, new LinearLayout.LayoutParams(land ? dp(306) : dp(180), -2));

        shot.setOnClickListener(v -> singleShot(0));
        scroll.setOnClickListener(v -> enterSection());
        settings.setOnClickListener(v -> openMainApp());
        quit.setOnClickListener(v -> stopSelf());
        cap.setOnClickListener(v -> captureSection(0));
        timerBtn.setOnClickListener(v -> toggleTimer());
        dirBtn.setOnClickListener(v -> toggleDirection());
        revBtn.setOnClickListener(v -> {
            if (timerRunning) return;
            Prefs.putB(this, Prefs.REVERSE, !Prefs.b(this, Prefs.REVERSE, false));
            revBtn.setText(revLabel());
        });
        fin.setOnClickListener(v -> finishSection());
        cancel.setOnClickListener(v -> cancelSection());
        closeX.setOnClickListener(v -> {
            if (sectionMode) { toast("Finish or cancel the scroll shot first"); return; }
            panelHidden = true;
            setOverlayVisible(false);
            toast("Panel hidden. Tap the 2%ScreenShot notification to bring it back.");
        });

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = h / 3;
        headerTv.setOnTouchListener(new DragListener());
        wm.addView(overlay, lp);
    }

    private LinearLayout.LayoutParams rowLp() {
        boolean land = w > h;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(land ? dp(150) : dp(180), land ? dp(40) : dp(46));
        p.topMargin = dp(6);
        return p;
    }

    /** One column in portrait, two columns in landscape so the panel fits the screen. */
    private void arrange(LinearLayout container, TextView... btns) {
        if (w <= h) {
            for (TextView b : btns) container.addView(b, rowLp());
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < btns.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
            }
            LinearLayout.LayoutParams p = rowLp();
            p.leftMargin = (i % 2 == 0) ? 0 : dp(6);
            row.addView(btns[i], p);
        }
    }

    private String dirLabel() {
        return horizontalMode() ? "Direction: ↔ sideways" : "Direction: ↕ up/down";
    }

    private String revLabel() {
        boolean r = Prefs.b(this, Prefs.REVERSE, false);
        if (horizontalMode()) return r ? "I scroll: \u25B6 right" : "I scroll: \u25C0 left";
        return r ? "I scroll: \u25BC down" : "I scroll: \u25B2 up";
    }

    private boolean horizontalMode() { return false; }   // long shots always stitch up/down

    private TextView pill(String label, boolean filled) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(w > h ? 13 : 15);
        t.setSingleLine(true);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        if (filled) {
            g.setColor(AppTheme.accent(this));
            t.setTextColor(AppTheme.onAccent(this));
        } else {
            g.setColor(AppTheme.transparent(this) ? 0x66000000 : AppTheme.bg(this));
            g.setStroke(dp(1), AppTheme.accent(this));
            t.setTextColor(AppTheme.text(this));
        }
        t.setBackground(g);
        return t;
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        View body = sectionMode ? bodySection : bodyMain;
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        statusTv.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        headerTv.setText(collapsed ? "☰  2%" : "☰  2%ScreenShot");
    }

    private void setStatus(String s) {
        main.post(() -> { if (statusTv != null) statusTv.setText(s); });
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void toastLong(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private void setOverlayVisible(boolean visible) {
        main.post(() -> {
            int vis = visible ? View.VISIBLE : View.GONE;
            if (overlay != null) overlay.setVisibility(vis);
            if (frameView != null) frameView.setVisibility(vis);
            if (topHandle != null) topHandle.setVisibility(vis);
            if (botHandle != null) botHandle.setVisibility(vis);
        });
    }

    private void openMainApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("Open 2%ScreenShot from your app list"); }
    }

    // ---------------------------------------------------------------- scroll area (sections)

    /** Colored frame that marks the scroll area. */
    private final class FrameView extends View {
        boolean flash = false;
        private final Paint dim = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        FrameView(Context c) {
            super(c);
            color = AppTheme.frameColor(c);
            dim.setColor(0x55000000);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(4));
            border.setColor(color);
            fill.setColor((0x66 << 24) | (color & 0xFFFFFF));
            txt.setTextSize(dp(12));
            txt.setFakeBoldText(true);
            txt.setColor(lum(color) > 0.6 ? 0xFF000000 : 0xFFFFFFFF);
            tag.setColor(color);
        }

        @Override protected void onDraw(Canvas c) {
            int fw = getWidth(), fh = getHeight();
            float s = dp(2);
            float lx, ly;
            if (horizontal) {
                c.drawRect(0, 0, regionTop, fh, dim);
                c.drawRect(regionBottom, 0, fw, fh, dim);
                if (flash) c.drawRect(regionTop, 0, regionBottom, fh, fill);
                c.drawRect(regionTop, s, regionBottom, fh - s, border);
                lx = regionTop + dp(10);
                ly = dp(48);
            } else {
                c.drawRect(0, 0, fw, regionTop, dim);
                c.drawRect(0, regionBottom, fw, fh, dim);
                if (flash) c.drawRect(0, regionTop, fw, regionBottom, fill);
                c.drawRect(s, regionTop, fw - s, regionBottom, border);
                lx = dp(10);
                ly = regionTop + dp(10);
            }
            String label = sectionCount == 0 ? "SCROLL AREA"
                    : (flash ? "SECTION " + sectionCount + " SAVED" : "SECTION " + sectionCount + " DONE");
            float tw = txt.measureText(label);
            RectF r = new RectF(lx, ly, lx + tw + dp(18), ly + dp(26));
            c.drawRoundRect(r, dp(13), dp(13), tag);
            c.drawText(label, r.left + dp(9), r.top + dp(17), txt);
        }
    }

    private static double lum(int c) {
        return (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) / 255.0;
    }

    // ---- auto-record indicator: coloured bars outside the captured area + a Finish chip

    private static final int REC_GREEN = 0xFF22DD44, REC_RED = 0xFFFF2D2D, REC_AMBER = 0xFFFFB300;
    private View recBars;
    private TextView recChip;
    private int recColor = REC_GREEN, recLine, recMargin;
    private boolean recRev = false;
    private long recHoldUntil = 0;

    private final class RecBarsView extends View {
        private final Paint pt = new Paint();
        RecBarsView(Context c) { super(c); }
        private void arrow(Canvas c, float cx, float cy, float deg, int color) {
            float a = dp(22);
            Path p = new Path();
            p.moveTo(0, -a);
            p.lineTo(a * 0.9f, 0);
            p.lineTo(a * 0.38f, 0);
            p.lineTo(a * 0.38f, a);
            p.lineTo(-a * 0.38f, a);
            p.lineTo(-a * 0.38f, 0);
            p.lineTo(-a * 0.9f, 0);
            p.close();
            c.save();
            c.translate(cx, cy);
            c.rotate(deg);
            pt.setStyle(Paint.Style.FILL_AND_STROKE);
            pt.setStrokeWidth(dp(4));
            pt.setColor(0xFF000000);
            c.drawPath(p, pt);
            pt.setStrokeWidth(0);
            pt.setColor(color);
            c.drawPath(p, pt);
            c.restore();
        }

        private void thumb(Canvas c, float cx, float cy) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextSize(dp(40));
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText("\uD83D\uDC4D", cx, cy + dp(14), t);
        }

        /** Guide icons in the strip outside the captured area: which way to scroll, and a thumbs up after a good match. */
        private void guide(Canvas c, int fw, int fh) {
            float[] xs, ys;
            if (horizontal) {
                float x = recRev ? (recLine + recMargin + fw) / 2f : (recLine - recMargin) / 2f;
                xs = new float[]{x};
                ys = new float[]{fh * 0.5f};
            } else {
                float y = recRev ? (recLine + recMargin + fh) / 2f : (recLine - recMargin) / 2f;
                xs = new float[]{fw / 2f};
                ys = new float[]{y};
            }
            // content moves up (vertical) or left (sideways) while the user swipes the same way
            float fwd = horizontal ? (recRev ? 90 : -90) : (recRev ? 180 : 0);
            for (int i = 0; i < xs.length; i++) {
                if (recColor == REC_RED) thumb(c, xs[i], ys[i]);
                else if (recColor == REC_AMBER) arrow(c, xs[i], ys[i], fwd + 180, REC_AMBER);
                else arrow(c, xs[i], ys[i], fwd, REC_GREEN);
            }
        }

        @Override protected void onDraw(Canvas c) {
            int fw = getWidth(), fh = getHeight(), bw = recMargin;
            pt.setStyle(Paint.Style.FILL);
            // thumbs up (good match) means this is the right area, so the outline stays green; amber still shows a failed match
            pt.setColor(recColor == REC_RED ? REC_GREEN : recColor);
            if (horizontal) {
                c.drawRect(0, 0, fw, bw, pt);
                c.drawRect(0, fh - bw, fw, fh, pt);
                if (recRev) c.drawRect(recLine, 0, recLine + bw, fh, pt);
                else c.drawRect(recLine - bw, 0, recLine, fh, pt);
            } else {
                c.drawRect(0, 0, bw, fh, pt);
                c.drawRect(fw - bw, 0, fw, fh, pt);
                if (recRev) c.drawRect(0, recLine, fw, recLine + bw, pt);
                else c.drawRect(0, recLine - bw, fw, recLine, pt);
            }
            guide(c, fw, fh);
        }
    }

    private int recStitchTop, recStitchFooter;

    /** Works out where the indicator strip sits and which rows of the stitched frame are usable. */
    private void computeRecGeom() {
        recMargin = dp(8);
        recRev = Prefs.b(this, Prefs.REVERSE, false);
        int ext = horizontal ? dp(130) : dp(44);
        int len = horizontal ? w : h;
        int need = recMargin + ext + recMargin;
        if (recRev) {
            recLine = Math.min(regionBottom, len - need);
            recStitchTop = len - recLine;
            recStitchFooter = regionTop;
        } else {
            recLine = Math.max(regionTop, need);
            recStitchTop = recLine;
            recStitchFooter = len - regionBottom;
        }
    }

    /** Turns a screen frame into the "content moves up" form the stitcher expects, minus the indicator margins. */
    private Bitmap toStitch(Bitmap f) {
        if (horizontal) f = rotate(f, recRev ? -90 : 90);
        else if (recRev) f = rotate(f, 180);
        int m = recMargin;
        return Bitmap.createBitmap(f, m, 0, f.getWidth() - 2 * m, f.getHeight());
    }

    private void showRecordUi() {
        main.post(() -> {
            hideRecordUiNow();
            computeRecGeom();
            recColor = REC_GREEN;
            recBars = new RecBarsView(this);
            WindowManager.LayoutParams bl = new WindowManager.LayoutParams(w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            bl.gravity = Gravity.TOP | Gravity.START;
            bl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(recBars, bl);

            recChip = new TextView(this);
            recChip.setText("\u25A0  Finish");
            recChip.setTextSize(15);
            recChip.setTypeface(null, android.graphics.Typeface.BOLD);
            recChip.setTextColor(0xFFFFFFFF);
            recChip.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xEE000000);
            g.setCornerRadius(dp(22));
            g.setStroke(dp(2), 0xFFFFFFFF);
            recChip.setBackground(g);
            recChip.setOnClickListener(v -> finishSection());
            WindowManager.LayoutParams cl = new WindowManager.LayoutParams(dp(130), dp(44),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            cl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            if (horizontal) {
                cl.gravity = Gravity.TOP | (recRev ? Gravity.END : Gravity.START);
                cl.x = recMargin;
                cl.y = recMargin + dp(4);
            } else {
                cl.gravity = (recRev ? Gravity.BOTTOM : Gravity.TOP) | Gravity.START;
                cl.x = recMargin + dp(6);
                cl.y = recMargin;
            }
            wm.addView(recChip, cl);
        });
    }

    private void hideRecordUiNow() {
        if (recBars != null) { try { wm.removeView(recBars); } catch (Exception ignored) { } recBars = null; }
        if (recChip != null) { try { wm.removeView(recChip); } catch (Exception ignored) { } recChip = null; }
    }

    private void hideRecordUi() { main.post(this::hideRecordUiNow); }

    private void setRecColor(int c) {
        if (c == recColor) return;
        recColor = c;
        main.post(() -> { if (recBars != null) recBars.invalidate(); });
    }

    private final class HandleListener implements View.OnTouchListener {
        private final boolean top;
        private float startRaw;
        private int startVal;
        HandleListener(boolean top) { this.top = top; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRaw = horizontal ? e.getRawX() : e.getRawY();
                    startVal = top ? regionTop : regionBottom;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int ny = startVal + (int) ((horizontal ? e.getRawX() : e.getRawY()) - startRaw);
                    int len = horizontal ? w : h;
                    int gap = (int) (len * 0.2f);
                    if (top) regionTop = Math.max(0, Math.min(ny, regionBottom - gap));
                    else regionBottom = Math.min(len, Math.max(ny, regionTop + gap));
                    positionHandles();
                    if (frameView != null) frameView.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    Prefs.putI(CaptureService.this, rkey(true), regionTop);
                    Prefs.putI(CaptureService.this, rkey(false), regionBottom);
                    return true;
                default:
                    return false;
            }
        }
    }

    private TextView handle(boolean top) {
        String label = horizontal ? (top ? "◀  left edge" : "right edge  ▶")
                : (top ? "▲  top edge" : "▼  bottom edge");
        TextView t = pill(label, true);
        t.setTextSize(13);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnTouchListener(new HandleListener(top));
        return t;
    }

    private WindowManager.LayoutParams handleLp() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(130), dp(36),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = w - dp(140);
        return p;
    }

    private void placeHandle(WindowManager.LayoutParams p, int edge) {
        if (horizontal) {
            p.x = Math.max(0, Math.min(edge - dp(65), w - dp(130)));
            p.y = h - dp(110);
        } else {
            p.x = w - dp(140);
            p.y = edge - dp(18);
        }
    }

    private void positionHandles() {
        if (topHandle != null) { placeHandle(topLp, regionTop); wm.updateViewLayout(topHandle, topLp); }
        if (botHandle != null) { placeHandle(botLp, regionBottom); wm.updateViewLayout(botHandle, botLp); }
    }

    private void addHandles() {
        topHandle = handle(true);
        botHandle = handle(false);
        topLp = handleLp();
        botLp = handleLp();
        placeHandle(topLp, regionTop);
        placeHandle(botLp, regionBottom);
        wm.addView(topHandle, topLp);
        wm.addView(botHandle, botLp);
    }

    private String rkey(boolean start) {
        return (start ? "rs_" : "re_") + (w > h ? "L" : "P") + (horizontal ? "H" : "V");
    }

    private void loadRegion() {
        int len = horizontal ? w : h;
        int rs = Prefs.i(this, rkey(true), -1);
        int re = Prefs.i(this, rkey(false), -1);
        if (rs < 0 || re <= rs || re > len) {
            rs = (int) (len * (horizontal ? 0.05f : 0.10f));
            re = (int) (len * (horizontal ? 0.95f : 0.93f));
        }
        regionTop = rs;
        regionBottom = re;
    }

    private void toggleDirection() {
        if (!sectionMode || busy || section != null) {
            toast("Pick the direction before the first section");
            return;
        }
        Prefs.putB(this, Prefs.HORIZONTAL, !horizontalMode());
        horizontal = horizontalMode();
        dirBtn.setText(dirLabel());
        if (revBtn != null) revBtn.setText(revLabel());
        removeView(topHandle); topHandle = null;
        removeView(botHandle); botHandle = null;
        loadRegion();
        addHandles();
        wm.removeView(overlay);
        wm.addView(overlay, lp);
        if (frameView != null) frameView.invalidate();
        statusTv.setText(horizontal
                ? "Drag the ◀ ▶ handles to fit the scroll area, then Capture section"
                : "Drag the ▲ ▼ handles to fit the scroll area, then Capture section");
    }

    private void removeSectionViews() {
        hideRecordUiNow();
        removeView(frameView); frameView = null;
        removeView(topHandle); topHandle = null;
        removeView(botHandle); botHandle = null;
    }

    private void enterSection() {
        if (busy || sectionMode) return;
        ensureOverlay();
        setOverlayVisible(true);
        sectionMode = true;
        section = null;
        recRev = false;
        sectionCount = 0;
        stopAuto = false;
        cancelled = false;

        horizontal = horizontalMode();
        loadRegion();
        if (dirBtn != null) dirBtn.setText(dirLabel());
        if (revBtn != null) revBtn.setText(revLabel());
        if (timerBtn != null) timerBtn.setText("●  Auto-record");

        frameView = new FrameView(this);
        frameLp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        frameLp.gravity = Gravity.TOP | Gravity.START;
        frameLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wm.addView(frameView, frameLp);

        addHandles();

        // keep the control panel above the frame
        wm.removeView(overlay);
        wm.addView(overlay, lp);

        bodyMain.setVisibility(View.GONE);
        bodySection.setVisibility(View.VISIBLE);
        if (collapsed) toggleCollapsed();
        statusTv.setText(horizontal
                ? "Drag the ◀ ▶ handles to fit the scroll area, then tap Capture section"
                : "Drag the ▲ ▼ handles to fit the scroll area, then tap Capture section");
        updateRunNotification(1);
    }

    private void exitSection() {
        sectionMode = false;
        section = null;
        sectionCount = 0;
        removeSectionViews();
        if (bodyMain != null) bodyMain.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (bodySection != null) bodySection.setVisibility(View.GONE);
        if (statusTv != null) statusTv.setText("Ready");
        updateRunNotification(0);
    }

    private void cancelSection() {
        if (!sectionMode) return;
        if (busy) { cancelled = true; pauseOnly = false; stopAuto = true; return; }
        exitSection();
        toast("Scroll shot cancelled");
    }

    private void lockRegion() {
        main.post(() -> {
            removeView(topHandle); topHandle = null;
            removeView(botHandle); botHandle = null;
        });
    }

    private void sectionReady(String what) {
        String msg = what + ". Scroll to the next part (keep a little overlap), then tap Capture section.";
        setStatus(msg);
        toastLong(msg);
        main.post(() -> {
            if (overlay != null) overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (frameView != null) {
                frameView.flash = true;
                frameView.invalidate();
                main.postDelayed(() -> {
                    if (frameView != null) { frameView.flash = false; frameView.invalidate(); }
                }, 700);
            }
        });
    }

    private static final int FIRST = 10;
    private static final int MAX_TOTAL_PX = 30000;

    private Bitmap rotate(Bitmap b, float deg) {
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    /** Captures one section (worker thread). Returns FIRST, Stitcher.OK/NO_CHANGE/FAIL, or -1 if no frame. */
    private int captureOnce() {
        setOverlayVisible(false);
        sleep(220);
        Bitmap stale = grabNew();               // drop any frame that still shows our panel
        if (stale != null) last = stale;
        sleep(140);
        Bitmap f = grabWait();
        if (f == null) { sleep(500); f = grabWait(); }
        if (f == null) { setOverlayVisible(true); return -1; }
        // wait until the screen has stopped moving (scroll momentum / animations) before using the frame
        long settleEnd = SystemClock.uptimeMillis() + 2500;
        while (SystemClock.uptimeMillis() < settleEnd) {
            sleep(180);
            Bitmap g = grabNew();
            if (g == null) break;            // nothing new arrived: screen is still
            last = g;
            boolean still = Stitcher.same(f, g);
            f = g;
            if (still) break;
        }
        if (horizontal) f = rotate(f, 90);      // sideways scrolling becomes up/down scrolling
        int r;
        if (section == null) {
            section = horizontal ? new Stitcher(h, w, regionTop, w - regionBottom)
                    : new Stitcher(w, h, regionTop, h - regionBottom);
            section.addFirst(f);
            sectionCount = 1;
            lockRegion();
            r = FIRST;
        } else {
            r = section.addNext(f, -1);
            if (r == Stitcher.OK) sectionCount++;
        }
        setOverlayVisible(true);
        return r;
    }

    private Bitmap finishBitmap() {
        Bitmap out = section.finish();
        if (horizontal) return rotate(out, recRev ? 90 : -90);
        return recRev ? rotate(out, 180) : out;
    }

    private void captureSection(long extraDelayMs) {
        if (!sectionMode || busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                setStatus("Capturing...");
                sleep(extraDelayMs);
                int r = captureOnce();
                if (r == -1) { setStatus("Screen not ready yet. Wait a second and tap Capture again."); return; }
                if (r == FIRST) sectionReady("Section 1 saved");
                else if (r == Stitcher.OK) sectionReady("Section " + sectionCount + " saved");
                else if (r == Stitcher.NO_CHANGE) {
                    String m = "Nothing moved. Scroll first, then tap Capture section.";
                    setStatus(m);
                    toastLong(m);
                } else {
                    String m = "Couldn't line it up (match " + section.lastScore() + "). Scroll back a bit so part of the last screen is still visible, then capture again.";
                    setStatus(m);
                    toastLong(m);
                }
            } finally {
                busy = false;
                setOverlayVisible(true);
            }
        });
    }

    private void toggleTimer() {
        if (!sectionMode) return;
        if (timerRunning) {                 // second tap = stop the timer, keep the sections
            pauseOnly = true;
            stopAuto = true;
            return;
        }
        if (busy) return;
        busy = true;
        timerRunning = true;
        stopAuto = false;
        cancelled = false;
        pauseOnly = false;
        main.post(() -> { if (timerBtn != null) timerBtn.setText("■  Stop recording"); });
        final int secs = Math.max(3, Math.min(20, Prefs.i(this, Prefs.TIMER_SEC, 5)));
        worker.execute(() -> {
            try {
                // 1) countdown so the user can line up the first screen
                for (int s = secs; s > 0 && !stopAuto; s--) {
                    setStatus((section == null ? "Line up the first screen. " : "Get ready. ")
                            + "Recording starts in " + s);
                    sleep(1000);
                }
                if (!stopAuto) {
                    // 2) record continuously: grab frames while the user scrolls slowly, stitch each
                    //    one onto the previous (small moves match reliably).
                    toastLong("Recording. Scroll slowly. Green = ready, thumbs up = shot taken, amber = no match. Tap Finish when done.");
                    setOverlayVisible(false);
                    sleep(350);
                    Bitmap stale = grabNew();
                    if (stale != null) last = stale;
                    // the first frame is taken with every overlay hidden so the header is clean
                    Bitmap f0 = grabWait();
                    if (f0 == null) { sleep(500); f0 = grabWait(); }
                    computeRecGeom();
                    showRecordUi();
                    sleep(250);
                    if (f0 != null) {
                        f0 = toStitch(f0);
                        section = new Stitcher(f0.getWidth(), f0.getHeight(), recStitchTop, recStitchFooter);
                        section.addFirst(f0);
                        sectionCount = 1;
                        recHoldUntil = SystemClock.uptimeMillis() + 650;
                        setRecColor(REC_RED);
                    }
                    if (section == null) {
                        toast("Couldn't read the screen. Try again.");
                        hideRecordUi();
                        stopAuto = true;
                    }
                    while (!stopAuto) {
                        long now = SystemClock.uptimeMillis();
                        if (recColor != REC_GREEN && now > recHoldUntil) setRecColor(REC_GREEN);
                        Bitmap f = grabNew();
                        if (f == null) { sleep(40); continue; }
                        last = f;
                        f = toStitch(f);
                        int r = section.addNext(f, -1);
                        if (r == Stitcher.OK) {
                            sectionCount++;
                            recHoldUntil = SystemClock.uptimeMillis() + 650;
                            setRecColor(REC_RED);
                        } else if (r == Stitcher.FAIL) {
                            recHoldUntil = SystemClock.uptimeMillis() + 500;
                            setRecColor(REC_AMBER);
                        }
                        if (section.totalHeight() > MAX_TOTAL_PX) {
                            toastLong("Reached the maximum image length. Saving.");
                            break;
                        }
                        sleep(60);
                    }
                    hideRecordUi();
                }
                setOverlayVisible(true);
                if (cancelled) {
                    toast("Scroll shot cancelled");
                } else if (!pauseOnly) {
                    if (section == null) { toast("Nothing captured yet"); }
                    else {
                        setStatus("Stitching...");
                        finishAndSave(finishBitmap());
                    }
                }
            } catch (OutOfMemoryError oom) {
                toast("Out of memory. Lower max screens in settings.");
            } finally {
                busy = false;
                timerRunning = false;
                boolean keep = pauseOnly && !cancelled;
                pauseOnly = false;
                if (keep) {
                    main.post(() -> { if (timerBtn != null) timerBtn.setText("●  Auto-record"); });
                    setStatus("Recording stopped. Capture sections yourself or tap Done");
                } else {
                    main.post(this::exitSection);
                }
                setOverlayVisible(true);
            }
        });
    }

    private void finishSection() {
        if (!sectionMode) return;
        if (busy) { pauseOnly = false; stopAuto = true; return; }   // timer / auto loop finishes and saves
        if (section == null) { toast("Nothing captured yet"); return; }
        busy = true;
        worker.execute(() -> {
            try {
                setStatus("Stitching...");
                setOverlayVisible(false);
                Bitmap out = finishBitmap();
                setOverlayVisible(true);
                finishAndSave(out);
            } catch (OutOfMemoryError oom) {
                toast("Out of memory. Lower max screens in settings.");
            } finally {
                busy = false;
                main.post(this::exitSection);
                setOverlayVisible(true);
            }
        });
    }

    // -------------------------------------------------------------- single capture

    private void singleShot(long extraDelayMs) {
        if (busy || sectionMode) return;
        busy = true;
        worker.execute(() -> {
            try {
                setStatus("Capturing...");
                setOverlayVisible(false);
                sleep(Prefs.i(this, Prefs.DELAY, 0) * 1000L + 300 + extraDelayMs);
                Bitmap f = grabWait();
                setOverlayVisible(true);
                if (f == null) { toast("No frame captured, try again"); return; }
                finishAndSave(f);
            } finally {
                busy = false;
                setStatus("Ready");
                setOverlayVisible(true);
            }
        });
    }

    private void finishAndSave(Bitmap bmp) {
        try {
            Uri u = ImageSaver.save(this, bmp);
            toast("Saved " + bmp.getWidth() + "x" + bmp.getHeight());
            postResult(u);
            if (Prefs.b(this, Prefs.PREVIEW, true)) {
                final Bitmap thumb = makeThumb(bmp);
                final int bw = bmp.getWidth(), bh = bmp.getHeight();
                main.post(() -> showPreview(thumb, u, bw, bh));
            }
            if (Prefs.b(this, Prefs.UPLOAD_AUTO, false)) {
                boolean ok = ImageSaver.upload(this, u);
                toast(ok ? "Uploaded" : "Upload failed");
            }
            if (Prefs.b(this, Prefs.LINE_AUTO, false)) shareToLine(u);
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private Bitmap grabNew() {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            Image.Plane p = img.getPlanes()[0];
            int rs = p.getRowStride(), ps = p.getPixelStride();
            Bitmap full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(p.getBuffer());
            if (full.getWidth() == w) return full;
            Bitmap c = Bitmap.createBitmap(full, 0, 0, w, h);
            full.recycle();
            return c;
        } catch (Exception e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    /** Newest frame; if the screen is static no new image arrives, so fall back to the last one. */
    private Bitmap grabWait() {
        long end = SystemClock.uptimeMillis() + 900;
        while (SystemClock.uptimeMillis() < end) {
            Bitmap b = grabNew();
            if (b != null) { last = b; return b; }
            sleep(60);
        }
        return last;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // -------------------------------------------------------------- LINE / notifications

    private void openAny(Uri u) {
        Intent i = new Intent(this, ShareActivity.class).setData(u).putExtra("mode", "view")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void shareAny(Uri u) {
        Intent i = new Intent(this, ShareActivity.class).setData(u)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void shareToLine(Uri u) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage(LINE_PACKAGE);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            shareAny(u);
        }
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Capture controls",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_QUIET, "Capture running (quiet)",
                NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_RESULT, "Saved screenshots",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private PendingIntent svc(String action, Uri data, int req) {
        Intent i = new Intent(this, CaptureService.class).setAction(action);
        if (data != null) i.setData(data);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** mode 0 = idle, 1 = scroll shot in progress. */
    private Notification buildRunNotification(int mode) {
        boolean bar = Prefs.b(this, Prefs.NOTIF_BAR, true);
        Notification.Builder nb = new Notification.Builder(this, bar ? CH_RUN : CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("2%ScreenShot")
                .setContentText(mode == 1 ? "Scroll shot: set the area, capture each section, then finish"
                        : "Ready. Tap to show the panel, or use the buttons")
                .setContentIntent(svc(ACTION_SHOW_PANEL, null, 7))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (!bar) {
            nb.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", svc(ACTION_QUIT, null, 2));
        } else if (mode == 1) {
            nb.addAction(android.R.drawable.ic_menu_camera, "Capture", svc(ACTION_SEC_CAPTURE, null, 8));
            nb.addAction(android.R.drawable.ic_menu_save, "Finish", svc(ACTION_SEC_FINISH, null, 9));
            nb.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", svc(ACTION_SEC_CANCEL, null, 10));
        } else {
            nb.addAction(android.R.drawable.ic_menu_camera, "Screenshot", svc(ACTION_SHOT, null, 5));
            nb.addAction(android.R.drawable.ic_menu_sort_by_size, "Scroll shot", svc(ACTION_SCROLL, null, 6));
            nb.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", svc(ACTION_QUIT, null, 2));
        }
        return nb.build();
    }

    private void updateRunNotification(int mode) {
        getSystemService(NotificationManager.class).notify(NOTIF_RUN, buildRunNotification(mode));
    }

    private void postResult(Uri u) {
        Intent view = new Intent(this, ShareActivity.class).setData(u).putExtra("mode", "view")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(this, 3, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_RESULT)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Screenshot saved")
                .setContentText("Tap to open")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_share, "Share to LINE", svc(ACTION_SHARE_LINE, u, 4))
                .build();
        getSystemService(NotificationManager.class).notify((int) (System.currentTimeMillis() % 100000) + 10, n);
    }

    // -------------------------------------------------------------- preview popup

    private Bitmap makeThumb(Bitmap src) {
        int tw = Math.min(src.getWidth(), (int) (w * 0.8f));
        int th = (int) ((long) src.getHeight() * tw / src.getWidth());
        long px = (long) tw * th;
        if (px > 8_000_000L) {
            double f = Math.sqrt(8_000_000d / px);
            tw = Math.max(1, (int) (tw * f));
            th = Math.max(1, (int) (th * f));
        }
        return Bitmap.createScaledBitmap(src, tw, th, true);
    }

    private void showPreview(Bitmap thumb, Uri uri, int bw, int bh) {
        dismissPreview();
        int accent = AppTheme.accent(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(AppTheme.panelBg(this));
        bg.setStroke(dp(2), accent);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("✓ Saved  " + bw + " x " + bh);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(accent);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextSize(20);
        x.setTypeface(null, android.graphics.Typeface.BOLD);
        x.setTextColor(accent);
        x.setGravity(Gravity.CENTER);
        head.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(head);

        ScrollView sv = new ScrollView(this);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        sv.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        int maxH = (int) (h * 0.5f);
        int imgH = (int) ((long) thumb.getHeight() * (int) (w * 0.8f) / Math.max(1, thumb.getWidth()));
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(maxH, Math.max(dp(120), imgH))));

        TextView open = pill("Open", true);
        TextView folder = pill("Open folder", true);
        TextView line = pill("LINE", true);
        TextView close = pill("Close", false);
        TextView shareBtn = pill("Share…", true);
        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(open, btnLp());
        row1.addView(folder, btnLp());
        row2.addView(line, btnLp());
        row2.addView(shareBtn, btnLp());
        row2.addView(close, btnLp());
        box.addView(row1);
        box.addView(row2);

        TextView off = new TextView(this);
        off.setText("Turn off this popup");
        off.setTextSize(13);
        off.setTextColor(AppTheme.transparent(this) ? 0xFFFFFFFF : AppTheme.sub(this));
        off.setGravity(Gravity.CENTER);
        off.setPadding(0, dp(12), 0, dp(4));
        off.setPaintFlags(off.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        box.addView(off);

        open.setOnClickListener(v -> {
            dismissPreview();
            openAny(uri);
        });
        folder.setOnClickListener(v -> { dismissPreview(); ImageSaver.openFolder(this); });
        line.setOnClickListener(v -> { dismissPreview(); shareToLine(uri); });
        shareBtn.setOnClickListener(v -> { dismissPreview(); shareAny(uri); });
        close.setOnClickListener(v -> dismissPreview());
        x.setOnClickListener(v -> dismissPreview());
        off.setOnClickListener(v -> {
            Prefs.putB(this, Prefs.PREVIEW, false);
            dismissPreview();
            toast("Preview popup off. Turn it back on in the app settings.");
        });

        WindowManager.LayoutParams pl = new WindowManager.LayoutParams(
                (int) (w * 0.9f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        pl.gravity = Gravity.CENTER;
        previewView = box;
        wm.addView(box, pl);
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p.topMargin = dp(8);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        return p;
    }

    private void dismissPreview() {
        removeView(previewView);
        previewView = null;
    }

    // -------------------------------------------------------------- small helper classes

    private final class StopCallback extends MediaProjection.Callback {
        @Override public void onStop() { main.post(new Runnable() { public void run() { stopSelf(); } }); }
    }

    private final class DragListener implements View.OnTouchListener {
        private int sx, sy;
        private float tx, ty;
        private boolean moved;
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - tx) > dp(6) || Math.abs(e.getRawY() - ty) > dp(6)) moved = true;
                    if (moved) {
                        lp.x = sx + (int) (e.getRawX() - tx);
                        lp.y = sy + (int) (e.getRawY() - ty);
                        wm.updateViewLayout(overlay, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) toggleCollapsed();
                    return true;
                default:
                    return false;
            }
        }
    }
}
