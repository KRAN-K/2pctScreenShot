package com.twopercent.screenshot;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one tall image out of successive frames taken while the screen scrolls up.
 *
 * For every new frame it finds how many pixels the content moved (by matching a
 * horizontal band against the previous frame) and appends only the newly revealed rows.
 */
final class Stitcher {
    static final int OK = 0, NO_CHANGE = 1, FAIL = 2;

    private static final int STEP = 4;      // horizontal sampling step
    private static final int BAND_H = 48;   // rows in the matching band
    private static final double MAX_AVG_DIFF = 24.0;

    private final int w, h, cropTop, contentBottom;
    private final List<Bitmap> strips = new ArrayList<>();
    private Bitmap prev;
    private byte[][] prevL;

    Stitcher(int w, int h, int cropTop, int cropBottom) {
        this.w = w;
        this.h = h;
        this.cropTop = Math.max(0, Math.min(cropTop, h / 3));
        this.contentBottom = h - Math.max(0, Math.min(cropBottom, h / 3));
    }

    int strips() { return strips.size(); }

    void addFirst(Bitmap f) {
        strips.add(Bitmap.createBitmap(f, 0, 0, w, contentBottom));
        prev = f;
        prevL = luma(f);
    }

    private double lastScore = -1;
    private double lastFrac = -1;

    /** Match quality of the last attempt (lower score is better), for on-screen hints. */
    int lastScore() { return (int) Math.round(lastScore); }

    /** @param nominal fallback scroll distance in px if matching fails (<=0: just report FAIL) */
    int addNext(Bitmap f, int nominal) {
        byte[][] nl = luma(f);
        if (meanDiff(nl, prevL, cropTop, contentBottom) < 1.0) return NO_CHANGE;

        int span = contentBottom - cropTop;
        // Try several bands. Bands near the top cope with big scrolls, lower ones with small scrolls.
        double[] fracs = {0.04, 0.12, 0.25, 0.40};
        int bestD = -1;
        double bestAvg = Double.MAX_VALUE;
        int cols = nl[0].length;
        int rows = (BAND_H + 1) / 2;
        for (double fr : fracs) {
            int bandTop = cropTop + (int) (span * fr);
            int maxD = contentBottom - bandTop - BAND_H;
            if (maxD < 1) continue;
            if (variance(nl, bandTop, BAND_H) < 30) continue;   // flat band, cannot be matched reliably
            long bestCost = Long.MAX_VALUE;
            int d0 = -1;
            for (int d = 1; d <= maxD; d++) {
                long cost = 0;
                boolean complete = true;
                for (int r = 0; r < BAND_H; r += 2) {
                    byte[] a1 = nl[bandTop + r];
                    byte[] b1 = prevL[bandTop + r + d];
                    for (int c = 0; c < cols; c++) {
                        cost += Math.abs((a1[c] & 255) - (b1[c] & 255));
                    }
                    if (cost >= bestCost) { complete = false; break; }
                }
                if (complete) { bestCost = cost; d0 = d; }
            }
            if (d0 > 0) {
                double avg = (double) bestCost / (rows * cols);
                if (avg < bestAvg) { bestAvg = avg; bestD = d0; }
            }
        }
        lastScore = bestAvg == Double.MAX_VALUE ? 99 : bestAvg;

        boolean ok = false;
        if (bestD > 0 && bestAvg <= MAX_AVG_DIFF) {
            // verify: most of the overlapping area must really match (animated bits may differ)
            lastFrac = matchFraction(nl, prevL, cropTop, contentBottom - bestD, bestD);
            ok = lastFrac >= 0.5;
        }

        int d;
        if (ok) {
            d = bestD;
        } else if (nominal > 0) {
            d = Math.min(nominal, span);
        } else {
            return FAIL;
        }

        strips.add(Bitmap.createBitmap(f, 0, contentBottom - d, w, d));
        prev = f;
        prevL = nl;
        return OK;
    }

    /** Joins everything into one bitmap. Footer (cropBottom area) of the last frame is appended. */
    Bitmap finish() {
        int total = 0;
        for (Bitmap s : strips) total += s.getHeight();
        int footer = h - contentBottom;
        total += footer;
        Bitmap out = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        int y = 0;
        for (Bitmap s : strips) {
            cv.drawBitmap(s, 0, y, null);
            y += s.getHeight();
            s.recycle();
        }
        strips.clear();
        if (footer > 0 && prev != null) {
            Bitmap f = Bitmap.createBitmap(prev, 0, contentBottom, w, footer);
            cv.drawBitmap(f, 0, y, null);
            f.recycle();
        }
        return out;
    }

    // ---- helpers ----

    private byte[][] luma(Bitmap b) {
        int[] row = new int[w];
        byte[][] out = new byte[h][(w + STEP - 1) / STEP];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0, j = 0; x < w; x += STEP, j++) {
                int p = row[x];
                out[y][j] = (byte) ((((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100);
            }
        }
        return out;
    }

    private static double meanDiff(byte[][] a, byte[][] b, int from, int to) {
        long sum = 0;
        long n = 0;
        for (int y = from; y < to; y += 3) {
            byte[] ra = a[y], rb = b[y];
            for (int c = 0; c < ra.length; c += 2) {
                sum += Math.abs((ra[c] & 255) - (rb[c] & 255));
                n++;
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    /** Fraction of sampled pixels where new[y] is close to prev[y + d], y in [from, to). */
    private static double matchFraction(byte[][] nw, byte[][] pv, int from, int to, int d) {
        long good = 0, n = 0;
        for (int y = from; y < to; y += 3) {
            byte[] ra = nw[y], rb = pv[y + d];
            for (int c = 0; c < ra.length; c += 2) {
                if (Math.abs((ra[c] & 255) - (rb[c] & 255)) <= 28) good++;
                n++;
            }
        }
        return n == 0 ? 1.0 : (double) good / n;
    }

    private static double variance(byte[][] a, int top, int rows) {
        double sum = 0, sq = 0;
        long n = 0;
        for (int y = top; y < top + rows; y += 2) {
            for (int c = 0; c < a[y].length; c += 2) {
                int v = a[y][c] & 255;
                sum += v;
                sq += (double) v * v;
                n++;
            }
        }
        if (n == 0) return 0;
        double m = sum / n;
        return sq / n - m * m;
    }
}
