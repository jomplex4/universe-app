package com.digitalminds.universe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * UNIVERSE :: Perms
 * Pide permisos directo a Android (sin depender de anotaciones), asi el
 * dialogo siempre aparece y nunca queda una llamada esperando para siempre.
 */
final class Perms {
    private ActivityResultLauncher<String[]> launcher;
    private Runnable after;

    void register(AppCompatActivity activity) {
        launcher = activity.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            Runnable r = after;
            after = null;
            if (r != null) r.run();
        });
    }

    static boolean has(Context ctx, String... perms) {
        for (String p : perms) if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** Runs onGranted if all perms are granted (asking once if needed), else onDenied. */
    void ensure(Context ctx, String[] perms, Runnable onGranted, Runnable onDenied) {
        if (perms.length == 0 || has(ctx, perms)) { onGranted.run(); return; }
        if (launcher == null) { onDenied.run(); return; }
        after = () -> { if (has(ctx, perms)) onGranted.run(); else onDenied.run(); };
        try { launcher.launch(perms); } catch (Exception e) { after = null; onDenied.run(); }
    }

    static void openAppSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
