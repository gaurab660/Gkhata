package com.dairy.orderapp;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bill PDF: save to Downloads/GKhata and share to WhatsApp etc.
 * (Android WebView ignores <a download> and navigator.share(files), so the web page calls this plugin.)
 * put()      : PDF is sent from the page in chunks and collected in cache/bills/ (no permission needed)
 * share()    : opens the Android share sheet for that file (FileProvider)
 * saveCopy() : copies it to Downloads/GKhata (Android 10+: MediaStore, no permission; Android 7-9: app's own Download folder)
 * Only names like Bill-Name-2083-07.pdf are accepted (no path tricks).
 */
@CapacitorPlugin(name = "GkFile")
public class FilePlugin extends Plugin {
    private static final long MAX = 40L * 1024 * 1024;

    private static boolean okName(String n) {
        return n != null && n.matches("[A-Za-z0-9_.\\-]{1,100}\\.pdf") && !n.contains("..");
    }

    private File dir() {
        File d = new File(getContext().getCacheDir(), "bills");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @PluginMethod
    public void put(PluginCall call) {
        String name = call.getString("name"), b64 = call.getString("b64");
        boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        if (!okName(name) || b64 == null) { call.reject("bad args"); return; }
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            File f = new File(dir(), name);
            if (!append) {
                File[] old = dir().listFiles(); // remove leftovers older than 1 hour
                if (old != null) for (File o : old) if (System.currentTimeMillis() - o.lastModified() > 3600_000L) o.delete();
                f.delete();
            }
            if (f.length() + data.length > MAX) throw new IOException("too large");
            try (FileOutputStream o = new FileOutputStream(f, append)) { o.write(data); }
            call.resolve();
        } catch (Exception e) {
            call.reject("put failed: " + e.getClass().getSimpleName());
        }
    }

    @PluginMethod
    public void share(PluginCall call) {
        String name = call.getString("name"), text = call.getString("text");
        if (!okName(name)) { call.reject("bad args"); return; }
        try {
            File f = new File(dir(), name);
            if (!f.exists()) { call.reject("not found"); return; }
            Uri u = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".gkshare", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, u);
            if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
            i.setClipData(ClipData.newRawUri(name, u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(i, "Bill");
            ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().runOnUiThread(() -> {
                try { getActivity().startActivity(ch); } catch (Exception ignored) { }
            });
            call.resolve();
        } catch (Exception e) {
            call.reject("share failed: " + e.getClass().getSimpleName());
        }
    }

    @PluginMethod
    public void saveCopy(PluginCall call) {
        String name = call.getString("name");
        if (!okName(name)) { call.reject("bad args"); return; }
        try {
            File src = new File(dir(), name);
            if (!src.exists()) { call.reject("not found"); return; }
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GKhata");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IOException("insert failed");
                try (InputStream in = new FileInputStream(src); OutputStream o = cr.openOutputStream(u)) {
                    if (o == null) throw new IOException("no stream");
                    copy(in, o);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, done, null, null);
                where = "Download/GKhata";
            } else {
                File base = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (base == null) base = getContext().getFilesDir();
                if (!base.exists()) base.mkdirs();
                File dst = new File(base, name);
                try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) { copy(in, o); }
                where = dst.getAbsolutePath();
            }
            JSObject r = new JSObject();
            r.put("where", where);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("save failed: " + e.getClass().getSimpleName());
        }
    }

    private static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) o.write(b, 0, n);
    }
}
