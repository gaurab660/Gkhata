package com.dairy.orderapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating "new order message" card shown at the TOP of the screen while GKhata is not in front
 * (the call bar lives at the bottom, so the two never overlap).
 *
 *   [WhatsApp · Order सन्देश]
 *   Customer name
 *   message text (max 4 lines)
 *   +2 अरू सन्देश पर्खिरहेका
 *   [ पछि हेर्छु ]  [ Order टिप्नुहोस् ]
 *
 * "Order टिप्नुहोस्" brings the app to front; the app then opens the draft for the oldest waiting
 * message and moves to the next one after each save. "पछि हेर्छु" only hides the card — the messages
 * stay in the app's queue, so no order is ever lost.
 * Needs "Display over other apps" (already asked in "फोन सेटअप जाँच"). Without it show() returns false
 * and the caller falls back to a normal notification.
 */
public final class MsgOverlay {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final int MAX_WAIT_MS = 10 * 60 * 1000;     // card hides itself after 10 min (message stays in the app)

    private static final List<String[]> ITEMS = new ArrayList<>();   // {src, from, text}
    private static Context app;
    private static WindowManager wm;
    private static View view;
    private static boolean sound = true, dingPending = false;

    private MsgOverlay() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences("co_ring_list", 0).getBoolean("pop", true);
    }

    /** @return true if the card is on screen (caller then skips the plain notification). */
    public static boolean show(Context c, String src, String from, String text) {
        try {
            app = c.getApplicationContext();
            if (!enabled(app) || !Settings.canDrawOverlays(app)) return false;
            final String[] it = {src, nameOf(from), text};
            H.post(() -> {
                try {
                    ITEMS.add(it);
                    while (ITEMS.size() > 20) ITEMS.remove(0);
                    dingPending = true;
                    tryRender();
                } catch (Throwable ignored) {}
            });
            return true;
        } catch (Throwable e) { return false; }
    }

    // SMS arrive with the number as sender: show the saved customer name instead
    private static String nameOf(String from) {
        try {
            String d = from.replaceAll("\\D", "");
            if (d.length() < 10 || d.length() < from.replaceAll("\\s", "").length() - 3) return from;
            String t = d.substring(d.length() - 10);
            SharedPreferences sp = app.getSharedPreferences("co_ring_list", 0);
            String[] n = sp.getString("l", "").split(","), nm = sp.getString("rn", "").split("\\|", -1);
            for (int i = 0; i < n.length && i < nm.length; i++) if (n[i].equals(t) && !nm[i].trim().isEmpty()) return nm[i].trim();
        } catch (Throwable ignored) {}
        return from;
    }

    // Never draw over a call: while the phone rings / is in a call (or GKhata's own call bar is up) the card waits,
    // then appears by itself 2 s after the call ends.
    private static boolean callBusy() {
        try {
            if (CallOverlay.active()) return true;
            return ((android.telephony.TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE)).getCallState()
                    != android.telephony.TelephonyManager.CALL_STATE_IDLE;
        } catch (Throwable e) { return false; }
    }

    private static final Runnable RETRY = MsgOverlay::tryRender;

    private static void tryRender() {
        H.removeCallbacks(RETRY);
        if (ITEMS.isEmpty()) return;
        if (CallBus.fg) { ITEMS.clear(); removeView(); return; }          // app is in front: it shows the draft itself
        if (callBusy()) { H.postDelayed(RETRY, 2000); return; }
        render();
        if (dingPending) { dingPending = false; ding(); }
    }

    /** App came to front / user acted: remove the card. */
    public static void hide(Context c) {
        try {
            if (c != null) app = c.getApplicationContext();
            H.post(MsgOverlay::remove);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ window
    private static void render() {
        removeView();
        if (app == null || ITEMS.isEmpty() || !Settings.canDrawOverlays(app)) return;
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                app.getResources().getDisplayMetrics().widthPixels - 2 * dp(10),
                ViewGroup.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(36);                                          // just below the status bar
        view = card();
        wm.addView(view, lp);
        H.removeCallbacks(EXPIRE);
        H.postDelayed(EXPIRE, MAX_WAIT_MS);
    }

    private static final Runnable EXPIRE = MsgOverlay::remove;

    private static void remove() {
        H.removeCallbacks(RETRY);
        ITEMS.clear();
        removeView();
    }

    private static void removeView() {
        H.removeCallbacks(EXPIRE);
        try { if (view != null && wm != null) wm.removeView(view); } catch (Throwable ignored) {}
        view = null;
    }

    // ------------------------------------------------------------------ view
    private static View card() {
        String[] last = ITEMS.get(ITEMS.size() - 1);
        // all waiting lines of the same customer + source are shown together (customers often send one item per message)
        StringBuilder body = new StringBuilder();
        for (String[] x : ITEMS) {
            if (x[1].equals(last[1]) && x[0].equals(last[0])) {
                if (body.length() > 0) body.append('\n');
                body.append(x[2]);
            }
        }
        int others = 0;
        for (String[] x : ITEMS) if (!(x[1].equals(last[1]) && x[0].equals(last[0]))) others++;

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(0xF5202124, 20));
        box.setClickable(true);                                   // tapping the card itself also opens the order
        box.setOnClickListener(v -> { remove(); openApp(); });

        TextView tag = text(last[0] + "  ·  नयाँ Order सन्देश", 12, 0xFF81C995, true);
        TextView who = text(last[1], 20, 0xFFFFFFFF, true);
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        TextView msg = text(body.toString(), 16, 0xFFE8EAED, false);
        msg.setMaxLines(4);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setPadding(0, dp(4), 0, 0);
        box.addView(tag);
        box.addView(who);
        box.addView(msg);

        if (others > 0) {
            TextView more = text("+" + others + " अरू Customer को सन्देश पर्खिरहेका", 13, 0xFFFDD663, false);
            more.setPadding(0, dp(6), 0, 0);
            box.addView(more);
        }

        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        TextView later = button("पछि हेर्छु", 0xFF3C4043);
        TextView open = button("Order टिप्नुहोस्", 0xFF0F6E56);
        later.setOnClickListener(v -> remove());
        open.setOnClickListener(v -> { remove(); openApp(); });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 1f), b = new LinearLayout.LayoutParams(0, dp(50), 1.4f);
        a.rightMargin = dp(6); b.leftMargin = dp(6);
        row.addView(later, a);
        row.addView(open, b);
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    // ------------------------------------------------------------------ actions / helpers
    private static void openApp() {
        try {
            Intent in = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (in == null) return;
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(in);
        } catch (Throwable ignored) {}
    }

    private static void ding() {
        if (!sound) return;
        try {
            Ringtone r = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Throwable ignored) {}
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

    private static TextView button(String label, int color) {
        TextView t = text(label, 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinHeight(dp(50));
        t.setBackground(round(color, 14));
        t.setClickable(true);
        t.setContentDescription(label);
        return t;
    }
}
