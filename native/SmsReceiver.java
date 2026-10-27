package com.dairy.orderapp;

import android.content.*;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.*;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())) return;
        SmsMessage[] ms = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (ms == null) return;
        long tm = 0;
        Map<String, StringBuilder> by = new LinkedHashMap<>();   // long SMS arrive in parts
        for (SmsMessage m : ms) {
            if (m == null || m.getOriginatingAddress() == null) continue;
            if (tm == 0) tm = m.getTimestampMillis();
            by.computeIfAbsent(m.getOriginatingAddress(), k -> new StringBuilder()).append(m.getMessageBody());
        }
        for (Map.Entry<String, StringBuilder> e : by.entrySet())
            if (MsgGate.numOk(c, e.getKey())) MsgGate.deliver(c, "SMS", e.getKey(), e.getKey(), e.getValue().toString(), tm == 0 ? System.currentTimeMillis() : tm);
    }
}
