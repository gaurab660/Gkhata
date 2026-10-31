package com.dairy.orderapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone boot, app update, clock/time-zone change and the window-boundary alarm all just re-apply the schedule. */
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { KeepAlive.apply(c); } catch (Throwable ignored) {}
    }
}
