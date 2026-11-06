package com.dairy.orderapp;

import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Gets the number of every incoming AND outgoing call the moment Android sets the call up (before the phone rings),
 * so the order screen can open at once for a saved customer. The call itself is never blocked or changed.
 * Needs the "Call screening app" role (asked from the phone-setup check). CallReceiver stays as the fallback and
 * CallReceiver.ringIn() makes sure one call is handled only once.
 */
public class CallScreen extends CallScreeningService {
    @Override
    public void onScreenCall(Call.Details d) {
        try { respondToCall(d, new CallResponse.Builder().build()); } catch (Throwable ignored) {}   // answer first: allow the call untouched
        try {
            Uri h = d.getHandle();
            String num = h == null ? null : h.getSchemeSpecificPart();
            if (num == null || num.isEmpty()) return;
            boolean incoming = Build.VERSION.SDK_INT < 29 || d.getCallDirection() == Call.Details.DIRECTION_INCOMING;
            CallReceiver.ringIn(getApplicationContext(), num, incoming);
        } catch (Throwable ignored) {}
    }
}
