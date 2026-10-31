package com.dairy.orderapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;

/**
 * Keeps GKhata alive only during business hours (default 16:00-22:00, changeable from the app).
 * Inside the window: a small foreground service runs, so the phone does not kill the app and calls are never missed.
 * Outside the window: the service is stopped and the phone behaves normally.
 * An alarm wakes this class at each window boundary; boot / app update / clock change re-apply it.
 */
public final class KeepAlive {
    static final String ACTION_TICK = "com.dairy.orderapp.KEEP_TICK";

    private KeepAlive() {}

    static boolean inWindow(int from, int to, int hour) {
        if (from == to) return false;
        return from < to ? (hour >= from && hour < to) : (hour >= from || hour < to);
    }

    /** Next moment the window opens or closes. */
    static long nextBoundary(int from, int to, long now) {
        long best = Long.MAX_VALUE;
        for (int day = 0; day <= 1; day++) {
            for (int h : new int[]{from, to}) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(now);
                c.add(Calendar.DAY_OF_YEAR, day);
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                long x = c.getTimeInMillis();
                if (x > now + 1000 && x < best) best = x;
            }
        }
        return best;
    }

    private static PendingIntent tick(Context a) {
        Intent i = new Intent(a, KeepAliveReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(a, 41, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Start / stop the service for the current time and schedule the next boundary. Safe to call any time. */
    public static void apply(Context ctx) {
        try {
            Context a = ctx.getApplicationContext();
            SharedPreferences sp = a.getSharedPreferences("co_keep", 0);
            boolean on = sp.getBoolean("on", true);
            int from = sp.getInt("from", 16), to = sp.getInt("to", 22);
            AlarmManager am = (AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = tick(a);

            if (!on || from == to) {
                am.cancel(pi);
                stop(a);
                return;
            }
            long now = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            if (inWindow(from, to, c.get(Calendar.HOUR_OF_DAY))) start(a); else stop(a);

            long when = nextBoundary(from, to, now);
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                else am.set(AlarmManager.RTC_WAKEUP, when, pi);
            } catch (SecurityException e) {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Throwable ignored) {}
    }

    private static void start(Context a) {
        try {
            Intent i = new Intent(a, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
        } catch (Throwable ignored) {}      // Android may refuse a background start; the next trigger / app open retries
    }

    private static void stop(Context a) {
        try { a.stopService(new Intent(a, KeepAliveService.class)); } catch (Throwable ignored) {}
    }
}
