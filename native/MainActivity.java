package com.dairy.orderapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import java.io.*;

public class MainActivity extends BridgeActivity {
    // A recording shared from another app is copied to the cache; refuse anything bigger than this
    // (the web app itself already limits what it sends to the speech service to 24 MB).
    private static final long MAX_SHARED_BYTES = 30L * 1024 * 1024;

    @Override public void onCreate(Bundle b) {
        registerPlugin(CallOrderPlugin.class);
        registerPlugin(BackupPlugin.class);
        super.onCreate(b);
        // Do not show the ledger in the "recent apps" thumbnail (normal screenshots still work). Android 13+.
        if (Build.VERSION.SDK_INT >= 33) {
            try { setRecentsScreenshotEnabled(false); } catch (Throwable ignored) {}
        }
        share(getIntent());
        // Back button: one step back inside the app (closes popup / goes to previous screen);
        // the app leaves only when the web app says 'exit' (second press within 2 s on the home screen).
        // (@capacitor/app is not installed, so the JS 'backButton' listener never fires without this.)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                WebView w = getBridge() != null ? getBridge().getWebView() : null;
                if (w == null) { finish(); return; }
                w.evaluateJavascript("(function(){try{return window.__gkBack?(window.__gkBack()||''):'exit'}catch(e){return 'exit'}})()", v -> {
                    if (v == null) { finish(); return; }
                    if (v.contains("exit")) finish();
                    else if (v.contains("min")) moveTaskToBack(true);
                });
            }
        });
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); share(i); }
    @Override protected void onResume() { super.onResume(); CallBus.fg = true; }
    @Override protected void onPause() { super.onPause(); CallBus.fg = false; }

    // Accepts a recording shared from the Phone/Recorder app ("Share > GKhata")
    private void share(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction()) || i.getType() == null || !i.getType().startsWith("audio/")) return;
        File out = new File(getCacheDir(), "co_shared.m4a");
        try {
            Uri u = Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null || !"content".equals(u.getScheme())) return;     // only content:// from another app
            long total = 0;
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(out)) {
                if (in == null) throw new IOException("no stream");
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > MAX_SHARED_BYTES) throw new IOException("too large");
                    o.write(b, 0, n);
                }
            }
            CallBus.sharedPath = out.getAbsolutePath(); CallBus.sharedName = "shared.m4a";
        } catch (Exception ignored) {
            out.delete();                                                  // never leave a partial / oversized file behind
        }
    }
}
