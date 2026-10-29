package com.dairy.orderapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GKhata main activity - hardened so that no function can freeze the app.
 *
 *  1. HANG-PROOFING: file I/O on a background thread; debug StrictMode + watchdog; Back button timeout.
 *  2. WEBVIEW CRASH RECOVERY: onRenderProcessGone handled, activity rebuilt, loop guard.
 *  3. MEMORY: low-memory signal forwarded to the web app ('gkLowMemory').
 *  4. DIAGNOSTICS: last uncaught exception saved to files/last_crash.txt.
 *  5. PRIVACY: Recents thumbnail hidden (Android 13+); optional FLAG_SECURE via setSecureScreen().
 *  6. SHARED AUDIO ("Share > GKhata"): size-capped, storage-checked, atomic, unique name.
 *
 * NOTE: BridgeActivity lifecycle methods (onStart/onResume/onPause/onStop/onDestroy) are PUBLIC,
 * so every override below must also be public (protected causes a compile error).
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "GKhata";

    private static final long MAX_SHARED_BYTES = 30L * 1024 * 1024;
    private static final long FREE_SPACE_MARGIN = 10L * 1024 * 1024;
    private static final long STALE_MS = 60L * 60 * 1000;
    private static final String SHARED_DIR = "shared";

    private static final String PREFS = "gk_native";
    private static final String KEY_SECURE = "secure_screen";

    private static final long BACK_TIMEOUT_MS = 1500;
    private static final long WATCHDOG_PERIOD_MS = 4000;            // debug only
    private static final int  TRIM_RUNNING_LOW = 10;
    private static final int  MAX_RECOVERIES = 3;
    private static final long RECOVERY_WINDOW_MS = 60_000;

    // Survive activity re-creation.
    private static long firstRecoveryAt = 0;
    private static int recoveries = 0;
    private static boolean crashLoggerInstalled = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gk-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private boolean pendingRecover = false;
    private volatile long lastTick = 0;
    private Thread watchdog;

    /** Incoming file exceeds MAX_SHARED_BYTES. */
    private static final class TooLargeException extends IOException {
        TooLargeException() { super("too large"); }
    }
    /** Not enough free storage to copy the incoming file. */
    private static final class NoSpaceException extends IOException {
        NoSpaceException() { super("no space"); }
    }

    // ================================================================= lifecycle

    @Override public void onCreate(Bundle b) {
        if (isDebuggable()) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build());
        }
        installCrashLogger();

        registerPlugin(CallOrderPlugin.class);
        registerPlugin(BackupPlugin.class);
        super.onCreate(b);

        applyPrivacy();
        if (!isDebuggable()) {
            try { WebView.setWebContentsDebuggingEnabled(false); } catch (Throwable ignored) {}
        }
        installWebViewRecovery();
        installBackHandler();
        if (isDebuggable()) startWatchdog();

        runIo(this::cleanupStaleShared);
        if (b == null) handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleShare(i);
    }

    @Override public void onResume() {
        super.onResume();
        CallBus.fg = true;
        if (pendingRecover) {
            pendingRecover = false;
            recoverWebView();
        }
    }

    @Override public void onPause() {
        super.onPause();
        CallBus.fg = false;
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_RUNNING_LOW) notifyWeb("gkLowMemory", String.valueOf(level));
    }

    @Override public void onDestroy() {
        stopWatchdog();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        CallBus.fg = false;
        if (isFinishing()) deleteAllShared();
        super.onDestroy();
    }

    // ================================================================= WebView crash recovery

    private void installWebViewRecovery() {
        try {
            Bridge br = getBridge();
            if (br == null) return;
            br.setWebViewClient(new BridgeWebViewClient(br) {
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    boolean crashed = Build.VERSION.SDK_INT >= 26 && detail != null && detail.didCrash();
                    Log.e(TAG, "WebView renderer gone, crashed=" + crashed);
                    main.post(() -> {
                        if (CallBus.fg) recoverWebView();
                        else pendingRecover = true;
                    });
                    return true;                                    // MUST return true, otherwise the whole app dies
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "could not install WebView recovery", t);
        }
    }

    private void recoverWebView() {
        long now = SystemClock.elapsedRealtime();
        if (now - firstRecoveryAt > RECOVERY_WINDOW_MS) { firstRecoveryAt = now; recoveries = 0; }
        if (++recoveries > MAX_RECOVERIES) {
            Toast.makeText(this, "एपमा समस्या आयो, कृपया एप फेरि खोल्नुहोस्", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Toast.makeText(this, "एप ताजा गर्दै...", Toast.LENGTH_SHORT).show();
        recreate();
    }

    // ================================================================= back button

    private void installBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                WebView w = getBridge() != null ? getBridge().getWebView() : null;
                if (w == null) { finish(); return; }

                final AtomicBoolean answered = new AtomicBoolean(false);
                final Runnable timeout = () -> {
                    if (answered.compareAndSet(false, true)) {
                        Log.w(TAG, "web page did not answer Back in time, closing");
                        finish();
                    }
                };
                main.postDelayed(timeout, BACK_TIMEOUT_MS);
                try {
                    w.evaluateJavascript(
                        "(function(){try{return window.__gkBack?(window.__gkBack()||''):'exit'}catch(e){return 'exit'}})()",
                        v -> {
                            if (!answered.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            String r = v == null ? "null" : v.replace("\"", "").trim();
                            switch (r) {
                                case "exit":
                                case "null":
                                    finish();
                                    break;
                                case "min":
                                    moveTaskToBack(true);
                                    break;
                                default:                // "" = handled inside the web app
                                    break;
                            }
                        });
                } catch (Throwable t) {
                    main.removeCallbacks(timeout);
                    finish();
                }
            }
        });
    }

    // ================================================================= privacy

    private void applyPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) {
            try { setRecentsScreenshotEnabled(false); } catch (Throwable ignored) {}
        }
        applySecure(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SECURE, false));
    }

    /** Turn FLAG_SECURE on/off and remember the choice. Safe to call from any thread. */
    public void setSecureScreen(boolean on) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SECURE, on).apply();
        runOnUiThread(() -> applySecure(on));
    }

    private void applySecure(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ================================================================= shared audio

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        if ((i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;

        final Uri u = Build.VERSION.SDK_INT >= 33
                ? i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null || !"content".equals(u.getScheme())) return;
        if (isOwnProvider(u)) return;

        i.setAction(null);
        final String intentType = i.getType();

        runIo(() -> {
            String type = resolveAudioType(intentType, u);
            if (type == null) return;
            copyShared(u, extensionFor(type));
        });
    }

    private void copyShared(Uri u, String ext) {
        File dir = new File(getCacheDir(), SHARED_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, "incoming.tmp");
        File out = new File(dir, "co_shared_" + System.currentTimeMillis() + "." + ext);
        try {
            long declared = declaredSize(u);
            if (declared > MAX_SHARED_BYTES) throw new TooLargeException();
            long need = (declared > 0 ? declared : 1024 * 1024) + FREE_SPACE_MARGIN;
            if (dir.getUsableSpace() < need) throw new NoSpaceException();

            long total = 0;
            try (InputStream in = getContentResolver().openInputStream(u);
                 OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("no stream");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    total += n;
                    if (total > MAX_SHARED_BYTES) throw new TooLargeException();
                    o.write(buf, 0, n);
                }
            }
            if (total == 0) throw new IOException("empty");
            if (!tmp.renameTo(out)) throw new IOException("rename failed");

            final String newPath = out.getAbsolutePath();
            final String name = "shared." + ext;
            main.post(() -> {
                String old = CallBus.sharedPath;
                CallBus.sharedPath = newPath;
                CallBus.sharedName = name;
                if (old != null && !old.equals(newPath)) runIo(() -> new File(old).delete());
                notifyWeb("gkSharedReady", "ok");
            });
        } catch (TooLargeException e) {
            tmp.delete(); out.delete();
            toast("रेकर्डिङ धेरै ठूलो छ (अधिकतम 30 MB)");
            notifyWeb("gkSharedError", "too_large");
        } catch (NoSpaceException e) {
            tmp.delete(); out.delete();
            toast("फोनमा खाली ठाउँ पुगेन");
            notifyWeb("gkSharedError", "no_space");
        } catch (Exception e) {
            tmp.delete(); out.delete();
            toast("रेकर्डिङ लिन सकिएन, फेरि प्रयास गर्नुहोस्");
            notifyWeb("gkSharedError", "copy_failed");
        }
    }

    private boolean isOwnProvider(Uri u) {
        String a = u.getAuthority();
        return a != null && a.startsWith(getPackageName());
    }

    private String resolveAudioType(String intentType, Uri u) {
        if (intentType != null && intentType.startsWith("audio/")) return intentType;
        boolean vague = intentType == null || intentType.equals("*/*") || intentType.equals("application/octet-stream");
        if (!vague) return null;
        try {
            String t = getContentResolver().getType(u);
            return (t != null && t.startsWith("audio/")) ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extensionFor(String mime) {
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("ogg") || m.contains("opus")) return "ogg";
        if (m.contains("wav")) return "wav";
        if (m.contains("amr")) return "amr";
        if (m.contains("3gpp") || m.contains("3gp")) return "3gp";
        if (m.contains("aac")) return "aac";
        if (m.contains("webm")) return "webm";
        return "m4a";
    }

    private long declaredSize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ================================================================= cleanup, feedback, helpers

    private void cleanupStaleShared() {
        File legacy = new File(getCacheDir(), "co_shared.m4a");
        if (legacy.exists()) legacy.delete();
        File[] files = new File(getCacheDir(), SHARED_DIR).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > STALE_MS) f.delete();
        }
    }

    private void deleteAllShared() {
        File[] files = new File(getCacheDir(), SHARED_DIR).listFiles();
        if (files != null) for (File f : files) f.delete();
        CallBus.sharedPath = null;
    }

    private void runIo(Runnable r) {
        try {
            io.execute(() -> {
                try { r.run(); } catch (Throwable t) { Log.e(TAG, "io task failed", t); }
            });
        } catch (RejectedExecutionException ignored) {
            // activity already destroyed
        }
    }

    private void toast(String msg) {
        main.post(() -> {
            if (!isFinishing() && !isDestroyed()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private void notifyWeb(String event, String reason) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                WebView w = getBridge() != null ? getBridge().getWebView() : null;
                if (w == null) return;
                w.evaluateJavascript("try{window.dispatchEvent(new CustomEvent('" + event
                        + "',{detail:{reason:'" + reason + "'}}))}catch(e){}", null);
            } catch (Throwable ignored) {}
        });
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // ================================================================= diagnostics

    private void installCrashLogger() {
        if (crashLoggerInstalled) return;
        crashLoggerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final File f = new File(getFilesDir(), "last_crash.txt");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, false))) {
                pw.println(new Date());
                pw.println("thread=" + t.getName());
                e.printStackTrace(pw);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    private void startWatchdog() {
        lastTick = SystemClock.uptimeMillis();
        final Thread mainThread = Looper.getMainLooper().getThread();
        watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final long posted = SystemClock.uptimeMillis();
                main.post(() -> lastTick = SystemClock.uptimeMillis());
                try { Thread.sleep(WATCHDOG_PERIOD_MS); } catch (InterruptedException e) { return; }
                if (lastTick < posted) {
                    StringBuilder sb = new StringBuilder("MAIN THREAD BLOCKED >" + WATCHDOG_PERIOD_MS + "ms at:\n");
                    for (StackTraceElement el : mainThread.getStackTrace()) sb.append("  at ").append(el).append('\n');
                    Log.w(TAG, sb.toString());
                }
            }
        }, "gk-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void stopWatchdog() {
        if (watchdog != null) { watchdog.interrupt(); watchdog = null; }
    }
}
