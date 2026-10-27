package com.dairy.orderapp;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.*;

public class WaListener extends NotificationListenerService {
    private static final Set<String> SEEN = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 300; }
    });

    @Override
    public void onNotificationPosted(StatusBarNotification s) {
        try { handle(s); } catch (Throwable ignored) {}   // a crash here would stop the listener
    }

    @Override
    public void onListenerDisconnected() {
        try { requestRebind(new android.content.ComponentName(this, WaListener.class)); } catch (Throwable ignored) {}
    }

    private void handle(StatusBarNotification s) {
        String pkg = s.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;
        Notification n = s.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null || x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return;   // personal chats only
        CharSequence t0 = x.getCharSequence(Notification.EXTRA_TITLE);
        if (t0 == null) return;
        String title = t0.toString().replaceAll("\\s*\\(\\d+ messages?\\)$", "").trim();
        if (!MsgGate.knownTitle(this, title)) return;                                            // not a saved customer: ignore
        String number = MsgGate.numberFor(this, title);
        Parcelable[] ms = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (ms != null && ms.length > 0) {                                                       // all unread messages of the chat
            for (Parcelable p : ms) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                CharSequence tx = b.getCharSequence("text");
                if (tx == null) continue;
                send(title, number, tx.toString(), b.getLong("time"));
            }
        } else {
            CharSequence tx = x.getCharSequence(Notification.EXTRA_TEXT);
            if (tx != null && !tx.toString().matches("(?i).*\\d+\\s+(new\\s+)?messages?.*")) send(title, number, tx.toString(), s.getPostTime());
        }
    }

    private void send(String title, String number, String text, long time) {
        if (text.trim().isEmpty() || text.matches("(?i)^(photo|voice message|video|document|sticker|audio|gif)$")) return;
        String k = title + "|" + time + "|" + text;
        synchronized (SEEN) { if (!SEEN.add(k)) return; }
        MsgGate.deliver(this, "WhatsApp", title, number, text, time > 0 ? time : System.currentTimeMillis());
    }
}
