package com.dairy.orderapp;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import org.json.*;
import java.io.*;

@CapacitorPlugin(name = "CallOrder", permissions = {
        @Permission(strings = {Manifest.permission.CALL_PHONE}, alias = "callphone")
})
public class CallOrderPlugin extends Plugin {
    @Override public void load() { CallBus.plugin = this; }
    @Override protected void handleOnDestroy() { if (CallBus.plugin == this) CallBus.plugin = null; }   // no leaked Activity

    void fire(JSONObject o) {
        try { notifyListeners("callEnded", JSObject.fromJSONObject(o)); } catch (Exception ignored) {}
    }

    void ring(JSONObject o) {
        try { notifyListeners("ringing", JSObject.fromJSONObject(o)); } catch (Exception ignored) {}
    }

    @PluginMethod public void setRingList(PluginCall c) {
        JSArray a = c.getArray("numbers", new JSArray());
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < a.length(); k++) { if (k > 0) sb.append(','); sb.append(a.optString(k)); }
        JSArray nm = c.getArray("names", new JSArray());       // customer names, same order as the numbers (for the floating call bar)
        StringBuilder sn = new StringBuilder();
        for (int k = 0; k < nm.length(); k++) { if (k > 0) sn.append('|'); sn.append(nm.optString(k).replace("|", " ")); }
        getContext().getSharedPreferences("co_ring_list", 0).edit().putString("l", sb.toString()).putString("rn", sn.toString()).apply();
        c.resolve();
    }

    @PluginMethod public void getRing(PluginCall c) {
        SharedPreferences sp = getContext().getSharedPreferences("co_ring_list", 0);
        JSObject r = new JSObject();
        try { String s = sp.getString("ring", null); if (s != null) r = JSObject.fromJSONObject(new JSONObject(s)); } catch (Exception ignored) {}
        sp.edit().remove("ring").apply();
        c.resolve(r);
    }

    void msg(JSONObject o) {
        try { notifyListeners("msgIn", JSObject.fromJSONObject(o)); } catch (Exception ignored) {}
    }

    @PluginMethod public void setCustList(PluginCall c) {
        JSArray a = c.getArray("numbers", new JSArray()), b = c.getArray("names", new JSArray());
        StringBuilder x = new StringBuilder(), y = new StringBuilder();
        for (int k = 0; k < a.length(); k++) { if (k > 0) x.append(','); x.append(a.optString(k)); }
        for (int k = 0; k < b.length(); k++) { if (k > 0) y.append('|'); y.append(b.optString(k).replace("|", " ")); }
        getContext().getSharedPreferences("co_ring_list", 0).edit().putString("all", x.toString()).putString("names", y.toString())
                .putInt("wf", c.getInt("from", 15)).putInt("wt", c.getInt("to", 1)).putLong("mf", c.getData().optLong("msgFrom", 0)).apply();
        c.resolve();
    }

    @PluginMethod public void getMsgs(PluginCall c) {
        SharedPreferences sp = getContext().getSharedPreferences("co_msgs", 0);
        JSObject r = new JSObject();
        try { r.put("items", new JSArray(sp.getString("l", "[]"))); } catch (Exception e) { r.put("items", new JSArray()); }
        sp.edit().remove("l").apply();
        c.resolve(r);
    }

    private javax.crypto.SecretKey key() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("co_k")) {
            javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore");
            g.init(new android.security.keystore.KeyGenParameterSpec.Builder("co_k",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build());
            g.generateKey();
        }
        return (javax.crypto.SecretKey) ks.getKey("co_k", null);
    }

    // API key is kept encrypted with an Android Keystore key (never in plain text)
    @PluginMethod public void secureSet(PluginCall c) {
        try {
            String n = c.getString("name", "k"), v = c.getString("value", "");
            SharedPreferences sp = getContext().getSharedPreferences("co_sec", 0);
            if (v.isEmpty()) { sp.edit().remove(n).apply(); c.resolve(); return; }
            javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(javax.crypto.Cipher.ENCRYPT_MODE, key());
            byte[] ct = ci.doFinal(v.getBytes("UTF-8"));
            sp.edit().putString(n, android.util.Base64.encodeToString(ci.getIV(), 2) + ":" + android.util.Base64.encodeToString(ct, 2)).apply();
            c.resolve();
        } catch (Exception e) { c.reject("secure"); }
    }

    @PluginMethod public void secureGet(PluginCall c) {
        JSObject r = new JSObject();
        try {
            String s = getContext().getSharedPreferences("co_sec", 0).getString(c.getString("name", "k"), null);
            if (s != null) {
                String[] a = s.split(":");
                javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(javax.crypto.Cipher.DECRYPT_MODE, key(), new javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(a[0], 2)));
                r.put("value", new String(ci.doFinal(android.util.Base64.decode(a[1], 2)), "UTF-8"));
            }
        } catch (Exception ignored) {}
        c.resolve(r);
    }

    // call recordings / shared audio are deleted as soon as they have been read
    @PluginMethod public void clearTemp(PluginCall c) {
        File[] f = getContext().getCacheDir().listFiles();
        if (f != null) for (File x : f) if (x.getName().startsWith("co_")) x.delete();
        c.resolve();
    }

    // ANSWER_PHONE_CALLS lets the floating bar answer / end calls (Android 8+)
    private String[] perms() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(basePerms()));
        if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS);
        return l.toArray(new String[0]);
    }

    private String[] basePerms() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS}
                : new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS};
    }

    @PluginMethod public void requestAll(PluginCall c) {
        ActivityCompat.requestPermissions(getActivity(), perms(), 9001);
        c.resolve();
    }

    @PluginMethod public void checkSetup(PluginCall c) {
        JSObject r = new JSObject();
        boolean all = true;
        for (String p : perms()) if (getContext().checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        r.put("perms", all);
        r.put("battery", pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        r.put("overlay", Settings.canDrawOverlays(getContext()));
        boolean fsi = true;     // Android 14+: full-screen notifications must be allowed by the user for sideloaded apps
        if (Build.VERSION.SDK_INT >= 34) {
            try { fsi = ((android.app.NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).canUseFullScreenIntent(); }
            catch (Exception ignored) {}
        }
        r.put("fsi", fsi);
        boolean side = false;   // Android 13+ blocks SMS / call log / notification access for apps installed outside a store
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                String ins = getContext().getPackageManager().getInstallSourceInfo(getContext().getPackageName()).getInstallingPackageName();
                side = ins == null || !(ins.equals("com.android.vending") || ins.equals("com.xiaomi.mipicks") || ins.equals("com.sec.android.app.samsungapps"));
            }
        } catch (Exception ignored) {}
        r.put("restricted", side);
        r.put("notif", androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName()));
        c.resolve(r);
    }

    @PluginMethod public void openSettings(PluginCall c) {
        String k = c.getString("kind", "app");
        Uri pkg = Uri.parse("package:" + getContext().getPackageName());
        Intent i;
        if ("notif".equals(k)) i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        else if ("overlay".equals(k)) i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg);
        else if ("battery".equals(k)) i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
        else if ("fsi".equals(k) && Build.VERSION.SDK_INT >= 34) i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
        else i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        try { getActivity().startActivity(i); }
        catch (Exception e) {                                   // some phones lack that screen: open the app info page instead
            try { getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); } catch (Exception ignored) {}
        }
        c.resolve();
    }

    // ---- Direct call: rings straight away (ACTION_CALL) instead of only opening the dialer ----
    // Needs CALL_PHONE (asked once, on the first direct call). If it is refused the JS side falls back to the normal dialer.
    private static String cleanNumber(String n) { return n == null ? "" : n.replaceAll("[^0-9+]", ""); }

    @PluginMethod public void directCall(PluginCall c) {
        String num = cleanNumber(c.getString("number", ""));
        if (num.length() < 5) { c.reject("bad_number"); return; }
        if (getContext().checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("callphone", c, "directCallPerm");
            return;
        }
        placeCall(c, num);
    }

    @PermissionCallback
    private void directCallPerm(PluginCall c) {
        if (getContext().checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            placeCall(c, cleanNumber(c.getString("number", "")));
        else c.reject("denied");
    }

    private void placeCall(PluginCall c, String num) {
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", num, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            c.resolve();
        } catch (SecurityException e) { c.reject("denied"); }
        catch (Exception e) { c.reject("fail"); }
    }

    // ---- Leave the app (back to the previous screen) once an order taken during a call is saved ----
    // The web app calls backWhenDone() after the save. If the call is still going on we wait for it to end
    // (CallReceiver calls onCallIdle), so the caller's in-call screen is never taken away mid-call.
    private static volatile boolean backPending = false;

    @android.annotation.SuppressLint("MissingPermission")
    private boolean callIdle() {
        try {
            return ((android.telephony.TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE)).getCallState()
                    == android.telephony.TelephonyManager.CALL_STATE_IDLE;
        } catch (Exception e) { return true; }
    }

    private void goBackNow() {
        backPending = false;
        try {
            final Activity a = getActivity();
            if (a != null) a.runOnUiThread(() -> { try { a.moveTaskToBack(true); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    void onCallIdle() { if (backPending) goBackNow(); }

    @PluginMethod public void backWhenDone(PluginCall c) {
        if (callIdle()) goBackNow(); else backPending = true;
        c.resolve();
    }

    @PluginMethod public void cancelBack(PluginCall c) { backPending = false; c.resolve(); }

    // ---- Business-hours keep-alive (see KeepAlive.java): the web app sets on/off and the hour window ----
    @PluginMethod public void setKeepAlive(PluginCall c) {
        int f = Math.max(0, Math.min(23, c.getInt("from", 16))), t = Math.max(0, Math.min(23, c.getInt("to", 22)));
        getContext().getSharedPreferences("co_keep", 0).edit()
                .putBoolean("on", c.getBoolean("on", true)).putInt("from", f).putInt("to", t).apply();
        KeepAlive.apply(getContext());
        c.resolve();
    }

    @PluginMethod public void getPending(PluginCall c) {
        SharedPreferences sp = getContext().getSharedPreferences("co_pending", 0);
        JSObject r = new JSObject();
        try { r.put("items", new JSArray(sp.getString("l", "[]"))); } catch (Exception e) { r.put("items", new JSArray()); }
        sp.edit().remove("l").apply();
        c.resolve(r);
    }

    @PluginMethod public void getShared(PluginCall c) {
        JSObject r = new JSObject();
        if (CallBus.sharedPath != null) { r.put("path", CallBus.sharedPath); r.put("name", CallBus.sharedName); }
        CallBus.sharedPath = null;
        c.resolve(r);
    }

    // Finds the phone's own call recording made around the call (Xiaomi Mi Dialer: MIUI/sound_recorder/call_rec).
    // A file whose name/folder says "call" wins; any other "rec" file is used only if nothing better exists.
    @PluginMethod public void findRecording(PluginCall c) {
        long ts = c.getData().optLong("ts", 0) / 1000;
        JSObject r = new JSObject();
        String[] pr = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.SIZE};
        long bestId = -1, fallbackId = -1; String bestName = "", fallbackName = "";
        try (Cursor q = getContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, pr,
                MediaStore.Audio.Media.DATE_ADDED + ">=?", new String[]{String.valueOf(ts - 120)},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            while (q != null && q.moveToNext()) {
                String name = q.getString(1) == null ? "" : q.getString(1), rel = q.getString(2) == null ? "" : q.getString(2);
                if (q.getLong(3) > 30L * 1024 * 1024) continue;
                String all = (rel + name).toLowerCase();
                if (all.contains("call")) { bestId = q.getLong(0); bestName = name; break; }
                if (fallbackId < 0 && all.contains("rec")) { fallbackId = q.getLong(0); fallbackName = name; }
            }
        } catch (Exception e) { r.put("error", String.valueOf(e.getMessage())); c.resolve(r); return; }
        long id = bestId >= 0 ? bestId : fallbackId;
        String name = bestId >= 0 ? bestName : fallbackName;
        if (id >= 0) {
            Uri u = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".m4a";
            File out = new File(getContext().getCacheDir(), "co_rec" + ext);
            try (InputStream in = getContext().getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) o.write(b, 0, n);
                r.put("path", out.getAbsolutePath()); r.put("name", name);
            } catch (Exception e) { out.delete(); r.put("error", String.valueOf(e.getMessage())); }
        }
        c.resolve(r);
    }
}
