package com.dairy.orderapp;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Automatic backups -> Documents/GKhata-Backup/ (visible in the Files app).
 * Android 10+ : MediaStore (no permission needed, file survives uninstall).
 * Android 7-9 : app's own external folder (no permission needed, removed on uninstall).
 * Only file names of the form gkhata-YYYY-MM-DD.gkb are accepted (no path tricks).
 */
@CapacitorPlugin(name = "GkBackup")
public class BackupPlugin extends Plugin {
    private static final String DIR = "GKhata-Backup";
    private static final String REL = "Documents/" + DIR + "/";
    private static final long MAX_READ = 60L * 1024 * 1024;

    private static boolean okName(String n) {
        return n != null && n.matches("gkhata-\\d{4}-\\d{2}-\\d{2}(-\\d{6})?\\.gkb");
    }

    private File legacyDir() {
        File base = getContext().getExternalFilesDir(null);
        if (base == null) base = getContext().getFilesDir();
        File d = new File(base, "Documents/" + DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private Uri filesUri() {
        return MediaStore.Files.getContentUri("external");
    }

    private Uri find(String name) {
        ContentResolver cr = getContext().getContentResolver();
        try (Cursor c = cr.query(filesUri(), new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{name, REL}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(filesUri(), c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private String shown(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private Uri insertExact(ContentResolver cr, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, REL);
        Uri u = cr.insert(filesUri(), v);
        if (u == null) throw new IOException("insert failed");
        if (!name.equals(shown(cr, u))) { // same name already exists (e.g. made by an earlier install): Android auto-renamed it
            try { cr.delete(u, null, null); } catch (Exception ignored) { }
            return null;
        }
        return u;
    }

    @PluginMethod
    public void write(PluginCall call) {
        String name = call.getString("name"), b64 = call.getString("b64");
        boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        if (!okName(name) || b64 == null) { call.reject("bad args"); return; }
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContext().getContentResolver();
                Uri u = find(name);
                if (u == null) {
                    if (append) throw new IOException("file lost");
                    u = insertExact(cr, name);
                    if (u == null) { // use a time-stamped name instead
                        String base = name.replaceAll("-\\d{6}\\.gkb$", ".gkb");
                        name = base.replace(".gkb", "-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".gkb");
                        u = insertExact(cr, name);
                        if (u == null) throw new IOException("name clash");
                    }
                    append = false;
                }
                try (OutputStream o = cr.openOutputStream(u, append ? "wa" : "wt")) {
                    if (o == null) throw new IOException("no stream");
                    o.write(data);
                }
            } else {
                try (FileOutputStream o = new FileOutputStream(new File(legacyDir(), name), append)) {
                    o.write(data);
                }
            }
            JSObject res = new JSObject();
            res.put("name", name);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("write failed: " + e.getClass().getSimpleName());
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        try {
            JSArray arr = new JSArray();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContext().getContentResolver();
                try (Cursor c = cr.query(filesUri(),
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{REL}, null)) {
                    while (c != null && c.moveToNext()) {
                        JSObject o = new JSObject();
                        o.put("name", c.getString(0));
                        o.put("size", c.getLong(1));
                        arr.put(o);
                    }
                }
            } else {
                File[] fs = legacyDir().listFiles();
                if (fs != null) for (File f : fs) {
                    JSObject o = new JSObject();
                    o.put("name", f.getName());
                    o.put("size", f.length());
                    arr.put(o);
                }
            }
            JSObject r = new JSObject();
            r.put("files", arr);
            r.put("path", Build.VERSION.SDK_INT >= 29 ? "Documents/" + DIR : legacyDir().getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("list failed: " + e.getClass().getSimpleName());
        }
    }

    @PluginMethod
    public void read(PluginCall call) {
        String name = call.getString("name");
        if (!okName(name)) { call.reject("bad args"); return; }
        try {
            InputStream in;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = find(name);
                if (u == null) { call.reject("not found"); return; }
                in = getContext().getContentResolver().openInputStream(u);
            } else {
                File f = new File(legacyDir(), name);
                if (!f.exists()) { call.reject("not found"); return; }
                in = new FileInputStream(f);
            }
            if (in == null) { call.reject("no stream"); return; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream i = in) {
                byte[] b = new byte[65536];
                int n;
                long total = 0;
                while ((n = i.read(b)) != -1) {
                    total += n;
                    if (total > MAX_READ) throw new IOException("too large");
                    bo.write(b, 0, n);
                }
            }
            JSObject r = new JSObject();
            r.put("b64", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("read failed: " + e.getClass().getSimpleName());
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String name = call.getString("name");
        if (!okName(name)) { call.reject("bad args"); return; }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = find(name);
                if (u != null) getContext().getContentResolver().delete(u, null, null);
            } else {
                new File(legacyDir(), name).delete();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("remove failed: " + e.getClass().getSimpleName());
        }
    }
}
