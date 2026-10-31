package com.dairy.orderapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

/** Small silent "GKhata is running" notification that keeps the process alive during business hours. */
public class KeepAliveService extends Service {
    private static final int ID = 7701;
    private static final String CH = "co_keep";
    private static final int TYPE_SPECIAL_USE = 1 << 30;     // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (Android 14+)

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(new NotificationChannel(CH, "GKhata चलिरहेको", NotificationManager.IMPORTANCE_LOW));

            SharedPreferences sp = getSharedPreferences("co_keep", 0);
            String when = sp.getInt("from", 16) + ":00 – " + sp.getInt("to", 22) + ":00";

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("GKhata चलिरहेको छ")
                    .setContentText("कल आउँदा Order खोल्न तयार · " + when)
                    .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE);
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null)
                b.setContentIntent(PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            Notification n = b.build();

            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, TYPE_SPECIAL_USE);
            else startForeground(ID, n);
        } catch (Throwable e) {
            stopSelf();
        }
        return START_STICKY;
    }
}
