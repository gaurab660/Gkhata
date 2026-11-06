package com.dairy.orderapp;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;

public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent i) {
        try { handle(ctx, i); } catch (Throwable ignored) {}
    }

    private void handle(final Context ctx, Intent i) {
        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(i.getAction())) {      // we are dialling a customer: open the order screen now
            String dialed = i.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (dialed != null) ringIn(ctx, dialed, false);
            return;
        }
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(i.getAction())) return;
        String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
        String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        final SharedPreferences sp = ctx.getSharedPreferences("co_state", 0);
        String prev = sp.getString("s", "IDLE");
        if (st == null) return;
        boolean ring = TelephonyManager.EXTRA_STATE_RINGING.equals(st);
        // RINGING is often broadcast twice: first without the number, then with it
        boolean newNum = ring && num != null && !num.isEmpty() && !num.equals(sp.getString("ring_num", ""));
        if (st.equals(prev) && !newNum) return;
        SharedPreferences.Editor e = sp.edit().putString("s", st);
        if (!TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
            if (prev.equals("IDLE")) e.putLong("t0", System.currentTimeMillis());
            if (newNum) e.putString("ring_num", num);
            e.apply();
            if (newNum) ringIn(ctx, num, true);
            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)) CallOverlay.onOffhook(ctx);   // answered: switch the floating bar to "on call"
            return;
        }
        e.remove("ring_num").remove("trig_num").apply();
        CallOverlay.hide(ctx);      // call finished / rejected / missed: remove the floating bar
        if (CallBus.plugin != null) CallBus.plugin.onCallIdle();   // go back to the previous screen if the order was already saved
        if (prev.equals("IDLE")) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                // the call log row can appear a little late: up to 4 tries, ~6.5 s in total (broadcast limit is 10 s)
                for (int k = 0; k < 4; k++) {
                    if (CallLogReader.process(ctx.getApplicationContext(), sp.getLong("t0", 0))) break;
                    Thread.sleep(1500);
                }
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }

    // ---- saved-customer numbers kept in memory (a HashSet lookup, no string search / disk read per call) ----
    private static volatile java.util.HashSet<String> RS;
    static void setRing(String csv) {
        java.util.HashSet<String> h = new java.util.HashSet<>();
        if (csv != null) for (String x : csv.split(",")) if (!x.isEmpty()) h.add(x);
        RS = h;
    }
    static boolean isRing(Context c, String t) {
        java.util.HashSet<String> h = RS;
        if (h == null) { setRing(c.getSharedPreferences("co_ring_list", 0).getString("l", "")); h = RS; }
        return h.contains(t);
    }

    // Call detected (by CallScreening, PHONE_STATE or NEW_OUTGOING_CALL - whichever is first): open the order screen at once,
    // only for saved customers. Other numbers do nothing at all. The same call is never handled twice.
    static void ringIn(Context c, String num, boolean incoming) {
        String d = num.replaceAll("\\D", "");
        String t = d.length() > 10 ? d.substring(d.length() - 10) : d;
        long now = System.currentTimeMillis();
        if (t.length() < 10 || !isRing(c, t) || !MsgGate.inWindow(c, now)) return;
        SharedPreferences st = c.getSharedPreferences("co_state", 0);
        if (t.equals(st.getString("trig_num", "")) && now - st.getLong("trig_ts", 0) < 60000) return;   // already handled by another detector
        st.edit().putString("trig_num", t).putLong("trig_ts", now).apply();
        Context a = c.getApplicationContext();
        Context cc = a != null ? a : c;
        new CallReceiver().openFor(cc, num, incoming);
    }

    private void openFor(Context c, String num, boolean incoming) {
        try {
            JSONObject o = new JSONObject().put("number", num).put("ts", System.currentTimeMillis()).put("bg", !CallBus.fg).put("out", !incoming);   // bg = app was NOT on screen when the call came
            c.getSharedPreferences("co_ring_list", 0).edit().putString("ring", o.toString()).apply();
            if (CallBus.plugin != null) CallBus.plugin.ring(o);
        } catch (Exception ignored) {}
        c.getSharedPreferences("co_state", 0).edit().putLong("opened", System.currentTimeMillis()).apply();
        if (incoming) CallOverlay.showRinging(c, num);   // floating Answer / Decline bar that never gets hidden by the order screen
        if (CallBus.fg) return;
        Intent in = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("co_ring", true);
        if (Settings.canDrawOverlays(c)) {          // allowed to start an activity from the background
            try { c.startActivity(in); return; } catch (Exception ignored) {}   // fall back to the notification below
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);   // fallback
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("co_ring", "Incoming customer", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(c, 8, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(78, new NotificationCompat.Builder(c, "co_ring").setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Customer को फोन: " + num).setContentText("Order टिप्न थिच्नुहोस्")
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true).setAutoCancel(true).setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)       // number hidden on the lock screen
                .setPublicVersion(new NotificationCompat.Builder(c, "co_ring").setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("GKhata").setContentText("Customer को फोन").build())
                .build());
    }
}
