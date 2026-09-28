package com.digitalminds.universe;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * UNIVERSE :: Routines
 * Guarda las rutinas y agenda alarmas del sistema. Si la app esta cerrada,
 * a la hora indicada se conecta sola a la cinta, la enciende o apaga y se
 * desconecta. Si la app esta abierta, ella misma lo hace.
 */
@SuppressLint("MissingPermission")
public class Routines extends BroadcastReceiver {

    static final String ACTION = "com.digitalminds.universe.ROUTINE";
    private static final String PREFS = "universe_routines";

    static void save(Context ctx, String json) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", json).apply();
    }

    private static JSONObject load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONObject(p.getString("data", "{}"));
        } catch (Exception e) { return new JSONObject(); }
    }

    static void scheduleAll(Context ctx) {
        JSONObject d = load(ctx);
        schedule(ctx, d.optJSONObject("on"), true);
        schedule(ctx, d.optJSONObject("off"), false);
    }

    private static PendingIntent intentFor(Context ctx, boolean on) {
        Intent i = new Intent(ctx, Routines.class).setAction(ACTION).putExtra("on", on);
        return PendingIntent.getBroadcast(ctx, on ? 1 : 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context ctx, JSONObject r, boolean on) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = intentFor(ctx, on);
        long t = r == null || !r.optBoolean("active", false) ? -1 : next(r);
        if (t < 0) { am.cancel(pi); return; }
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
    }

    /** Next moment (ms) matching HH:MM on an enabled day. Days: index 0 = Monday. */
    private static long next(JSONObject r) {
        String time = r.optString("time", "");
        JSONArray days = r.optJSONArray("days");
        if (days == null || !time.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] hm = time.split(":");
        int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
        long now = System.currentTimeMillis();
        for (int add = 0; add <= 7; add++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, add);
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            int idx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if (c.getTimeInMillis() > now + 1000 && days.optInt(idx, 0) == 1) return c.getTimeInMillis();
        }
        return -1;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!ACTION.equals(a)) { scheduleAll(ctx); return; }   // boot or app update: re-arm alarms
        boolean on = intent.getBooleanExtra("on", true);
        scheduleAll(ctx);                                      // arm the next occurrence
        if (LightService.running) return;                      // app is alive: it handles the routine
        String mac = load(ctx).optString("mac", "");
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return;
        fire(ctx, mac, on, goAsync());
    }

    private static void fire(Context ctx, String mac, boolean on, PendingResult done) {
        final byte[] cmd = { 0x7e, 0x00, 0x04, (byte) (on ? 1 : 0), 0, 0, 0, 0, (byte) 0xef };
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] finished = { false };
        final BluetoothGatt[] ref = { null };
        final Runnable end = () -> {
            if (finished[0]) return;
            finished[0] = true;
            if (ref[0] != null) { try { ref[0].disconnect(); } catch (Exception ignored) { } try { ref[0].close(); } catch (Exception ignored) { } }
            try { done.finish(); } catch (Exception ignored) { }
        };
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) { end.run(); return; }
            BluetoothDevice dev = ad.getRemoteDevice(mac);
            ref[0] = dev.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) h.postDelayed(g::discoverServices, 250);
                    else if (state == BluetoothProfile.STATE_DISCONNECTED) h.post(end);
                }
                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    for (BluetoothGattService s : g.getServices()) {
                        if (!s.getUuid().toString().toLowerCase().startsWith(StripPlugin.SERVICE_PREFIX)) continue;
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            int p = c.getProperties();
                            boolean nr = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                            if (!nr && (p & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) continue;
                            int type = nr ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, cmd, type);
                            else { c.setWriteType(type); c.setValue(cmd); g.writeCharacteristic(c); }
                            h.postDelayed(end, 600);
                            return;
                        }
                    }
                    h.post(end);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { end.run(); return; }
        h.postDelayed(end, 9000);   // never hang
    }
}
