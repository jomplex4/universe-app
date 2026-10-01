package com.digitalminds.universe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * UNIVERSE :: Strip
 * Bluetooth propio para la cinta ELK. Conecta directo a una cinta ya
 * emparejada o recordada (sin escanear, asi Android no pide ubicacion).
 * Solo escanea si nunca se conecto a una cinta y no esta emparejada.
 * Tambien mantiene la app viva en segundo plano y agenda rutinas.
 */
@SuppressLint("MissingPermission")
@CapacitorPlugin(name = "Strip")
public class StripPlugin extends Plugin {

    static final String SERVICE_PREFIX = "0000fff0";

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private PluginCall connectCall;
    private PluginCall writeCall;
    private boolean ticking = false;

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /* ---------------- permissions (asked directly, only what each action needs) ---------------- */

    private final Perms perms = new Perms();

    @Override
    public void load() {
        perms.register(getActivity());
        LightService.listener = (action) -> {
            JSObject o = new JSObject();
            o.put("action", action);
            notifyListeners("media", o);
        };
    }

    private static String[] connectPerms() {
        return Build.VERSION.SDK_INT >= 31 ? new String[] { "android.permission.BLUETOOTH_CONNECT" } : new String[0];
    }

    private static String[] scanPerms() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[] { "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT" }
            : new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private void withPerms(PluginCall call, String[] p, Runnable action) {
        perms.ensure(getContext(), p, action, () -> call.reject("PERMISSION_DENIED"));
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Perms.openAppSettings(getContext());
        call.resolve();
    }

    /* ---------------- adapter state ---------------- */

    @PluginMethod
    public void status(PluginCall call) {
        BluetoothAdapter a = adapter();
        JSObject r = new JSObject();
        r.put("supported", a != null);
        r.put("enabled", a != null && a.isEnabled());
        r.put("sdk", Build.VERSION.SDK_INT);
        call.resolve(r);
    }

    @PluginMethod
    public void enable(PluginCall call) {
        withPerms(call, connectPerms(), () -> doEnable(call));
    }

    private void doEnable(PluginCall call) {
        BluetoothAdapter a = adapter();
        if (a == null) { call.reject("NO_BLUETOOTH"); return; }
        if (a.isEnabled()) { call.resolve(); return; }
        startActivityForResult(call, new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "enableResult");
    }

    @ActivityCallback
    private void enableResult(PluginCall call, ActivityResult result) {
        BluetoothAdapter a = adapter();
        if (a != null && a.isEnabled()) call.resolve(); else call.reject("BT_OFF");
    }

    @PluginMethod
    public void openLocationSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /* ---------------- find the strip ---------------- */

    @PluginMethod
    public void bonded(PluginCall call) {
        withPerms(call, connectPerms(), () -> doBonded(call));
    }

    private void doBonded(PluginCall call) {
        BluetoothAdapter a = adapter();
        JSArray list = new JSArray();
        if (a != null) {
            Set<BluetoothDevice> set = a.getBondedDevices();
            if (set != null) for (BluetoothDevice d : set) {
                JSObject o = new JSObject();
                o.put("id", d.getAddress());
                o.put("name", d.getName() == null ? "" : d.getName());
                list.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("devices", list);
        call.resolve(r);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        withPerms(call, scanPerms(), () -> doScan(call));
    }

    private void doScan(PluginCall call) {
        if (Build.VERSION.SDK_INT < 31 && !locationOn()) { call.reject("LOCATION_OFF"); return; }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { call.reject("BT_OFF"); return; }
        final BluetoothLeScanner scanner = a.getBluetoothLeScanner();
        if (scanner == null) { call.reject("BT_OFF"); return; }

        final Map<String, JSObject> found = new LinkedHashMap<>();
        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult res) {
                BluetoothDevice d = res.getDevice();
                String name = d.getName();
                if (name == null && res.getScanRecord() != null) name = res.getScanRecord().getDeviceName();
                if (name == null) return;
                JSObject o = new JSObject();
                o.put("id", d.getAddress());
                o.put("name", name);
                o.put("rssi", res.getRssi());
                found.put(d.getAddress(), o);
            }
        };
        scanner.startScan(cb);
        int ms = call.getInt("ms", 4000);
        main.postDelayed(() -> {
            try { scanner.stopScan(cb); } catch (Exception ignored) { }
            JSArray list = new JSArray();
            for (JSObject o : found.values()) list.put(o);
            JSObject r = new JSObject();
            r.put("devices", list);
            call.resolve(r);
        }, ms);
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /* ---------------- connection ---------------- */

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    main.postDelayed(() -> { try { g.discoverServices(); } catch (Exception ignored) { } }, 250);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    boolean wasReady = ch != null && gatt == g;
                    try { g.close(); } catch (Exception ignored) { }
                    if (gatt == g) { gatt = null; ch = null; }
                    if (connectCall != null) { connectCall.reject("CONNECT_FAILED"); connectCall = null; }
                    if (writeCall != null) { writeCall.resolve(); writeCall = null; }
                    if (wasReady) notifyListeners("disconnected", new JSObject());
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                BluetoothGattCharacteristic pick = null;
                for (BluetoothGattService s : g.getServices()) {
                    if (!s.getUuid().toString().toLowerCase().startsWith(SERVICE_PREFIX)) continue;
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        int p = c.getProperties();
                        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) { pick = c; break; }
                        if (pick == null && (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) pick = c;
                    }
                }
                if (pick == null) {
                    if (connectCall != null) { connectCall.reject("NOT_A_STRIP"); connectCall = null; }
                    try { g.disconnect(); } catch (Exception ignored) { }
                    return;
                }
                ch = pick;
                if (connectCall != null) {
                    JSObject r = new JSObject();
                    r.put("id", g.getDevice().getAddress());
                    connectCall.resolve(r);
                    connectCall = null;
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> { if (writeCall != null) { writeCall.resolve(); writeCall = null; } });
        }
    };

    @PluginMethod
    public void connect(PluginCall call) {
        withPerms(call, connectPerms(), () -> main.post(() -> doConnect(call)));
    }

    private void doConnect(PluginCall call) {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { call.reject("BT_OFF"); return; }
        String id = call.getString("id");
        if (id == null || !BluetoothAdapter.checkBluetoothAddress(id)) { call.reject("BAD_ID"); return; }
        closeGatt();
        connectCall = call;
        BluetoothDevice dev = a.getRemoteDevice(id);
        gatt = dev.connectGatt(getContext(), false, gattCb, BluetoothDevice.TRANSPORT_LE);
        final PluginCall mine = call;
        main.postDelayed(() -> {
            if (connectCall == mine) {
                connectCall.reject("TIMEOUT");
                connectCall = null;
                closeGatt();
            }
        }, 9000);
    }

    @PluginMethod
    public void write(PluginCall call) {
        if (gatt == null || ch == null) { call.reject("NOT_CONNECTED"); return; }
        JSArray arr = call.getArray("bytes");
        if (arr == null) { call.reject("NO_DATA"); return; }
        byte[] b = new byte[arr.length()];
        try { for (int i = 0; i < b.length; i++) b[i] = (byte) arr.getInt(i); }
        catch (Exception e) { call.reject("BAD_DATA"); return; }
        boolean reliable = Boolean.TRUE.equals(call.getBoolean("reliable", false));
        attemptWrite(call, b, reliable ? 12 : 1);
    }

    /** Power commands retry until the radio accepts them; color frames are simply replaced by the next one. */
    private void attemptWrite(PluginCall call, byte[] b, int triesLeft) {
        if (gatt == null || ch == null) { call.resolve(); return; }
        boolean noResp = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(ch, b, type) == BluetoothStatusCodes.SUCCESS;
            } else {
                ch.setWriteType(type);
                ch.setValue(b);
                ok = gatt.writeCharacteristic(ch);
            }
        } catch (Exception e) { ok = false; }
        if (!ok) {
            if (triesLeft > 1) { main.postDelayed(() -> attemptWrite(call, b, triesLeft - 1), 40); return; }
            call.resolve();
            return;
        }
        if (writeCall != null && writeCall != call) writeCall.resolve();
        writeCall = call;
        main.postDelayed(() -> { if (writeCall == call) { writeCall.resolve(); writeCall = null; } }, 300);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeGatt();
        call.resolve();
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        ch = null;
    }

    /* ---------------- background: keep lights, music and routines running ---------------- */

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            try { getBridge().getWebView().evaluateJavascript("window.__ut&&window.__ut()", null); }
            catch (Exception ignored) { }
            main.postDelayed(this, 50);
        }
    };

    /**
     * session({ on, light })
     * Starts, updates or stops the background notification. "on" false stops it.
     */
    @PluginMethod
    public void session(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (on && Build.VERSION.SDK_INT >= 33 && !Perms.has(getContext(), "android.permission.POST_NOTIFICATIONS") && !askedNotify) {
            askedNotify = true;   // optional: everything works even if refused
            Runnable go = () -> applySession(call, true);
            perms.ensure(getContext(), new String[] { "android.permission.POST_NOTIFICATIONS" }, go, go);
            return;
        }
        applySession(call, on);
    }

    private boolean askedNotify = false;

    private void applySession(PluginCall call, boolean on) {
        Context ctx = getContext();
        Intent svc = new Intent(ctx, LightService.class);
        if (on) {
            svc.setAction(LightService.ACT_UPDATE)
                .putExtra("light", Boolean.TRUE.equals(call.getBoolean("light", true)));
            try { ContextCompat.startForegroundService(ctx, svc); } catch (Exception ignored) { }
            if (!ticking) { ticking = true; main.post(ticker); }
        } else {
            ticking = false;
            ctx.stopService(svc);
        }
        call.resolve();
    }

    @PluginMethod
    public void setRoutines(PluginCall call) {
        Routines.save(getContext(), call.getData().toString());
        Routines.scheduleAll(getContext());
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        ticking = false;
        LightService.listener = null;
        closeGatt();
        try { getContext().stopService(new Intent(getContext(), LightService.class)); } catch (Exception ignored) { }
    }
}
