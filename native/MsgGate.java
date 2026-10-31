package com.dairy.orderapp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import androidx.core.app.NotificationCompat;
import org.json.*;

// Decides whether an incoming SMS/WhatsApp message belongs to a saved customer. Everything else is ignored and never stored.
public class MsgGate {
    static String tail(String s) {
        String d = s == null ? "" : s.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("co_ring_list", 0); }
    // Order-taking window (default 15:00 -> 01:00 next day). Returns {start,end} of the window that contains ts, else null.
    static long[] window(Context c, long ts) {
        int f = sp(c).getInt("wf", 15), t = sp(c).getInt("wt", 1);
        for (int off = 0; off >= -1; off--) {
            java.util.Calendar a = java.util.Calendar.getInstance(); a.setTimeInMillis(ts);
            a.set(java.util.Calendar.HOUR_OF_DAY, f); a.set(java.util.Calendar.MINUTE, 0); a.set(java.util.Calendar.SECOND, 0); a.set(java.util.Calendar.MILLISECOND, 0);
            a.add(java.util.Calendar.DAY_OF_YEAR, off);
            java.util.Calendar b = (java.util.Calendar) a.clone();
            b.set(java.util.Calendar.HOUR_OF_DAY, t); b.add(java.util.Calendar.DAY_OF_YEAR, t <= f ? 1 : 0);
            if (ts >= a.getTimeInMillis() && ts < b.getTimeInMillis()) return new long[]{a.getTimeInMillis(), b.getTimeInMillis()};
        }
        return null;
    }
    static boolean inWindow(Context c, long ts) { return window(c, ts) != null; }

    static boolean numOk(Context c, String n) {
        String t = tail(n);
        return t.length() == 10 && ("," + sp(c).getString("all", "") + ",").contains("," + t + ",");
    }
    // STRICT identification. A message is accepted only when its sender resolves to a phone number that is saved
    // in the customer list. Names alone are never trusted.
    //  - SMS: the sender number itself (last 10 digits) must be in the list.
    //  - WhatsApp: the title is either a number (unsaved contact) -> same check, or a contact name -> the name must
    //    belong to exactly ONE phone contact (duplicate names are rejected) and that contact must own a listed number.
    static boolean knownTitle(Context c, String title) { return !numberFor(c, title).isEmpty(); }

    static String numberFor(Context c, String title) {
        if (title == null || title.trim().isEmpty()) return "";
        String d = title.replaceAll("[^0-9]", "");
        if (d.length() >= 10 && d.length() >= title.replaceAll("\\s", "").length() - 3)      // title is a phone number
            return numOk(c, title) ? title : "";
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
        String hit = "";
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (Cursor q = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?", new String[]{title.trim()}, null)) {
            while (q != null && q.moveToNext()) {
                ids.add(q.getString(1));
                if (hit.isEmpty() && numOk(c, q.getString(0))) hit = q.getString(0);
            }
        } catch (Exception ignored) { return ""; }
        return ids.size() == 1 ? hit : "";            // two different contacts with the same name -> cannot tell who wrote
    }

    static void deliver(Context c, String src, String from, String number, String text, long mt) {
        long now = System.currentTimeMillis();
        long[] w = window(c, now);
        // only messages that arrived inside the current order window and after the day's accounts were closed
        if (w == null || mt < w[0] || mt >= w[1] || mt < sp(c).getLong("mf", 0)) return;
        try {
            JSONObject o = new JSONObject().put("src", src).put("from", from).put("number", number)
                    .put("text", text).put("ts", now).put("mt", mt);
            SharedPreferences p = c.getSharedPreferences("co_msgs", 0);
            JSONArray a = new JSONArray(p.getString("l", "[]")), b = new JSONArray();
            for (int k = Math.max(0, a.length() - 29); k < a.length(); k++) b.put(a.get(k));
            b.put(o);
            p.edit().putString("l", b.toString()).apply();
            if (CallBus.plugin != null) CallBus.plugin.msg(o);
            if (CallBus.fg) return;
            if (MsgOverlay.show(c, src, from, text)) return;     // floating card on top; plain notification only if it cannot be shown
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel("co_msg", "Customer messages", NotificationManager.IMPORTANCE_HIGH));
            Intent in = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 9, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(79, new NotificationCompat.Builder(c, "co_msg").setSmallIcon(android.R.drawable.sym_action_email)
                    .setContentTitle(from + " (" + src + ") — Order सन्देश").setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)       // customer name + message hidden on the lock screen
                    .setPublicVersion(new NotificationCompat.Builder(c, "co_msg").setSmallIcon(android.R.drawable.sym_action_email).setContentTitle("GKhata").setContentText("नयाँ Order सन्देश").build())
                    .build());
        } catch (Exception ignored) {}
    }
}
