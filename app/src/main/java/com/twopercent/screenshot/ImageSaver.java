package com.twopercent.screenshot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ImageSaver {
    private ImageSaver() {}

    /** Saves to Pictures/<folder>/ via MediaStore (no storage permission needed). */
    static Uri save(Context c, Bitmap bmp) throws IOException {
        boolean png = Prefs.b(c, Prefs.FORMAT_PNG, true);
        int quality = Math.max(40, Math.min(100, Prefs.i(c, Prefs.QUALITY, 92)));
        String folder = clean(Prefs.s(c, Prefs.FOLDER, "MCOC"), "MCOC");
        String prefix = clean(Prefs.s(c, Prefs.PREFIX, "mcoc"), "shot");
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + (png ? ".png" : ".jpg");

        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("MediaStore insert failed");
        try (OutputStream os = cr.openOutputStream(u)) {
            if (os == null) throw new IOException("No output stream");
            bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, os);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(u, v, null, null);
        return u;
    }

    static String clean(String s, String def) {
        if (s == null) return def;
        s = s.replaceAll("[\\\\:*?\"<>|]", "_").replaceAll("^/+|/+$", "").trim();
        return s.isEmpty() ? def : s;
    }

    /** Raw POST of the image bytes to the configured URL (e.g. your own server / bot). */
    static boolean upload(Context c, Uri image) {
        String url = Prefs.s(c, Prefs.UPLOAD_URL, "").trim();
        if (url.isEmpty()) return false;
        HttpURLConnection con = null;
        try {
            ContentResolver cr = c.getContentResolver();
            String mime = cr.getType(image);
            if (mime == null) mime = "application/octet-stream";
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestProperty("Content-Type", mime);
            con.setRequestProperty("X-Filename", "mcoc_" + System.currentTimeMillis()
                    + (mime.contains("png") ? ".png" : ".jpg"));
            String token = Prefs.s(c, Prefs.UPLOAD_TOKEN, "").trim();
            if (!token.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + token);
            con.setChunkedStreamingMode(64 * 1024);
            try (InputStream in = cr.openInputStream(image); OutputStream out = con.getOutputStream()) {
                if (in == null) return false;
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            int code = con.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Opens Pictures/<folder> in the Files app, falling back to the gallery. */
    static void openFolder(Context c) {
        String folder = clean(Prefs.s(c, Prefs.FOLDER, "MCOC"), "MCOC");
        Uri doc = Uri.parse("content://com.android.externalstorage.documents/document/"
                + Uri.encode("primary:Pictures/" + folder));
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(doc, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return;
        } catch (Exception ignored) { }
        try {
            Intent g = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(g);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "Screenshots are in Pictures/" + folder,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
