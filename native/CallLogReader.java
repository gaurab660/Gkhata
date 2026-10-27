package com.dairy.orderapp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.CallLog;
import androidx.core.app.NotificationCompat;
import org.json.*;

public class CallLogReader {
    // Returns false only when the call is not in the call log YET (Android writes it a moment after the call ends),
    // so the caller may try again. Returns true when handled / nothing more to do.
    static boolean process(Context c, long t0) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return true;
        if (t0 <= 0) t0 = System.currentTimeMillis() - 60000;     // lost start time: look only at the last minute, never at old calls
        try (Cursor q = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE},
                CallLog.Calls.DATE + ">=?", new String[]{String.valueOf(t0 - 5000)},
                CallLog.Calls.DATE + " DESC")) {
            if (q == null || !q.moveToFirst()) return false;
            String num = q.getString(0) == null ? "" : q.getString(0);
            if (num.isEmpty() || !MsgGate.inWindow(c, q.getLong(3))) return true;
            JSONObject o = new JSONObject();
            o.put("number", num).put("type", q.getInt(1)).put("dur", q.getInt(2)).put("ts", q.getLong(3));
            SharedPreferences sp = c.getSharedPreferences("co_pending", 0);
            JSONArray a = new JSONArray(sp.getString("l", "[]"));
            for (int k = 0; k < a.length(); k++) if (a.getJSONObject(k).optLong("ts") == o.optLong("ts")) return true;
            JSONArray keep = new JSONArray();                          // keep at most the newest 50 if the app is not opened for days
            for (int k = Math.max(0, a.length() - 49); k < a.length(); k++) keep.put(a.get(k));
            a = keep;
            a.put(o);
            sp.edit().putString("l", a.toString()).apply();
            if (CallBus.plugin != null) CallBus.plugin.fire(o);
            if (!CallBus.fg) notify(c, num, q.getInt(1));
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    static void notify(Context c, String num, int type) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("co_calls", "Call Order", NotificationManager.IMPORTANCE_HIGH));
        Intent in = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 7, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String t = type == CallLog.Calls.MISSED_TYPE ? "छुटेको कल" : "कल सकियो";
        Notification n = new NotificationCompat.Builder(c, "co_calls").setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(t + ": " + num).setContentText("Order टिप्न थिच्नुहोस्")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)       // number hidden on the lock screen
                .setPublicVersion(new NotificationCompat.Builder(c, "co_calls").setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("GKhata").setContentText("कल सकियो").build())
                .build();
        nm.notify(77, n);
    }
}
