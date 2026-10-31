package com.dairy.orderapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating call controls that always stay on top of the order screen
 * (needs "Display over other apps", the same permission the app already asks for).
 *
 *  RINGING : card at the bottom   -> customer name / number + Decline + Answer
 *  ON CALL : small draggable pill -> timer + Order (bring the app to front) + End call
 *
 * Answer / End use TelecomManager (ANSWER_PHONE_CALLS). If that permission or the API is missing,
 * the buttons tell the user to use the phone's own button, so nothing breaks.
 */
public final class CallOverlay {
    private static final int NONE = 0, RING = 1, LIVE = 2;
    private static final String PERM_ANSWER = "android.permission.ANSWER_PHONE_CALLS";
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static Context app;
    private static WindowManager wm;
    private static View view;
    private static WindowManager.LayoutParams lp;
    private static TextView timer;
    private static int mode = NONE;
    private static long shownAt = 0, liveAt = 0;
    private static String num = "";

    private CallOverlay() {}

    // ---------------------------------------------------------------- public entry points
    public static void showRinging(Context c, String number) {
        try {
            app = c.getApplicationContext();
            if (!Settings.canDrawOverlays(app)) return;
            num = number == null ? "" : number;
            H.post(() -> { try { render(RING); } catch (Throwable ignored) {} });
        } catch (Throwable ignored) {}
    }

    /** Call got answered (from our button or from the phone itself). */
    public static void onOffhook(Context c) {
        try {
            app = c.getApplicationContext();
            H.post(() -> { try { if (mode == RING) render(LIVE); } catch (Throwable ignored) {} });
        } catch (Throwable ignored) {}
    }

    /** Call ended / rejected / missed. */
    public static void hide(Context c) {
        try {
            app = c.getApplicationContext();
            H.post(CallOverlay::remove);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- window handling
    private static void render(int m) {
        remove();
        if (app == null || !Settings.canDrawOverlays(app)) return;
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        long now = System.currentTimeMillis();

        if (m == RING) {
            lp = new WindowManager.LayoutParams(dm.widthPixels - 2 * dp(12), ViewGroup.LayoutParams.WRAP_CONTENT,
                    type, flags, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(12);
            view = ringView();
            shownAt = now;
        } else {
            lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    type, flags, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            SharedPreferences sp = app.getSharedPreferences("co_overlay", 0);
            lp.x = sp.getInt("x", Math.max(0, dm.widthPixels - dp(230)));
            lp.y = sp.getInt("y", (int) (dm.heightPixels * 0.30f));
            view = livePill();
            liveAt = now;
            shownAt = now;
        }
        mode = m;
        wm.addView(view, lp);
        H.removeCallbacks(TICK);
        H.postDelayed(TICK, 1000);
    }

    private static void remove() {
        H.removeCallbacks(TICK);
        try { if (view != null && wm != null) wm.removeView(view); } catch (Throwable ignored) {}
        view = null; timer = null; mode = NONE;
    }

    // Keeps the timer running and removes the overlay by itself if the end-of-call broadcast was ever missed.
    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (mode == NONE) return;
            long now = System.currentTimeMillis();
            int s = callState();
            if (s == TelephonyManager.CALL_STATE_IDLE && now - shownAt > 3000) { remove(); return; }
            if (s == TelephonyManager.CALL_STATE_OFFHOOK && mode == RING) { render(LIVE); return; }
            if (now - shownAt > 6L * 3600 * 1000) { remove(); return; }       // safety net
            if (mode == LIVE && timer != null) timer.setText("●  " + fmt((now - liveAt) / 1000));
            H.postDelayed(this, 1000);
        }
    };

    @SuppressLint("MissingPermission")
    private static int callState() {
        try {
            return ((TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE)).getCallState();
        } catch (Throwable e) { return -1; }
    }

    // ---------------------------------------------------------------- views
    private static View ringView() {
        String name = nameFor(num);
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(0xF2202124, 20));

        box.addView(text("आएको कल", 12, 0xFF9AA0A6, false));
        box.addView(text(name.isEmpty() ? num : name, 20, 0xFFFFFFFF, true));
        if (!name.isEmpty()) box.addView(text(num, 14, 0xFFBDC1C6, false));

        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        TextView no = button("काट्नुहोस्", 0xFFD93025, 52);
        TextView yes = button("उठाउनुहोस्", 0xFF1E8E3E, 52);
        no.setOnClickListener(v -> endCall());
        yes.setOnClickListener(v -> answer());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(52), 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(52), 1f);
        a.rightMargin = dp(6); b.leftMargin = dp(6);
        row.addView(no, a); row.addView(yes, b);
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static View livePill() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(round(0xF2202124, 30));

        timer = text("●  00:00", 15, 0xFFFFFFFF, true);
        timer.setPadding(dp(12), dp(10), dp(10), dp(10));
        timer.setContentDescription("चलिरहेको कल। सार्न तान्नुहोस्");
        drag(timer);

        TextView order = button("Order", 0xFF0F6E56, 44);
        TextView end = button("काट्नुहोस्", 0xFFD93025, 44);
        order.setOnClickListener(v -> openApp());
        end.setOnClickListener(v -> endCall());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p1.leftMargin = dp(4); p2.leftMargin = dp(6);
        box.addView(timer);
        box.addView(order, p1);
        box.addView(end, p2);
        return box;
    }

    private static void drag(View handle) {
        final int[] st = new int[4];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = lp.x; st[1] = lp.y; st[2] = (int) e.getRawX(); st[3] = (int) e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    DisplayMetrics dm = app.getResources().getDisplayMetrics();
                    int w = view == null ? 0 : view.getWidth(), h = view == null ? 0 : view.getHeight();
                    lp.x = Math.max(0, Math.min(dm.widthPixels - w, st[0] + (int) e.getRawX() - st[2]));
                    lp.y = Math.max(0, Math.min(dm.heightPixels - h - dp(72), st[1] + (int) e.getRawY() - st[3]));
                    try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    app.getSharedPreferences("co_overlay", 0).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    return true;
                default:
                    return false;
            }
        });
    }

    // ---------------------------------------------------------------- actions
    @SuppressLint("MissingPermission")
    private static void answer() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && app.checkSelfPermission(PERM_ANSWER) == PackageManager.PERMISSION_GRANTED) {
                ((TelecomManager) app.getSystemService(Context.TELECOM_SERVICE)).acceptRingingCall();
                return;
            }
        } catch (Throwable ignored) {}
        toast("कल उठाउन फोनकै बटन थिच्नुहोस् (वा 'फोन सेटअप जाँच' मा अनुमति दिनुहोस्)");
    }

    // also used to decline a ringing call
    @SuppressLint("MissingPermission")
    private static void endCall() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && app.checkSelfPermission(PERM_ANSWER) == PackageManager.PERMISSION_GRANTED) {
                if (((TelecomManager) app.getSystemService(Context.TELECOM_SERVICE)).endCall()) return;
            }
        } catch (Throwable ignored) {}
        toast("कल काट्न फोनकै बटन थिच्नुहोस् (वा 'फोन सेटअप जाँच' मा अनुमति दिनुहोस्)");
    }

    private static void openApp() {
        try {
            Intent in = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (in == null) return;
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(in);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- helpers
    private static String nameFor(String number) {
        try {
            String d = number.replaceAll("\\D", ""), t = d.length() > 10 ? d.substring(d.length() - 10) : d;
            SharedPreferences sp = app.getSharedPreferences("co_ring_list", 0);
            String[] n = sp.getString("l", "").split(","), nm = sp.getString("rn", "").split("\\|", -1);
            for (int i = 0; i < n.length && i < nm.length; i++) if (n[i].equals(t)) return nm[i].trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private static String fmt(long s) {
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static void toast(String m) {
        try { Toast.makeText(app, m, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    private static int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(app);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static TextView button(String label, int color, int heightDp) {
        TextView t = text(label, 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setMinHeight(dp(heightDp));
        t.setBackground(round(color, 14));
        t.setClickable(true);
        t.setContentDescription(label);
        return t;
    }
}
