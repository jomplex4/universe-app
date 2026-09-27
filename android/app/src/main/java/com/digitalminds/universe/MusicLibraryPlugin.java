package com.digitalminds.universe;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * UNIVERSE :: MusicLibrary
 * Lee las canciones del telefono (almacenamiento interno o SD) y las
 * agrupa por la carpeta real donde estan guardadas (ej. "Old School").
 * Sin dependencias extra: usa solo MediaStore de Android.
 */
@CapacitorPlugin(
    name = "MusicLibrary",
    permissions = {
        @Permission(alias = "audioModern", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "audioLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicLibraryPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audioModern" : "audioLegacy";
    }

    @PluginMethod
    public void getLibrary(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "permCallback");
            return;
        }
        load(call);
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            load(call);
        } else {
            call.reject("PERMISSION_DENIED");
        }
    }

    private void load(PluginCall call) {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        };
        // Ignora audios muy cortos (notificaciones, tonos).
        String selection = MediaStore.Audio.Media.DURATION + " >= ?";
        String[] args = { "15000" };

        Map<String, List<JSObject>> byFolder = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> folderPath = new LinkedHashMap<>();

        try (Cursor c = getContext().getContentResolver().query(uri, projection, selection, args,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c != null) {
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                while (c.moveToNext()) {
                    String path = c.getString(iData);
                    if (path == null) continue;
                    File parent = new File(path).getParentFile();
                    if (parent == null) continue;
                    String folder = parent.getName();

                    JSObject song = new JSObject();
                    song.put("title", c.getString(iTitle));
                    String artist = c.getString(iArtist);
                    song.put("artist", (artist == null || artist.startsWith("<")) ? "" : artist);
                    song.put("duration", c.getLong(iDur));
                    song.put("path", path);

                    String key = parent.getAbsolutePath();
                    if (!byFolder.containsKey(key)) {
                        byFolder.put(key, new ArrayList<>());
                        folderPath.put(key, folder);
                    }
                    byFolder.get(key).add(song);
                }
            }
        } catch (Exception e) {
            call.reject("READ_FAILED", e);
            return;
        }

        List<JSObject> folders = new ArrayList<>();
        for (Map.Entry<String, List<JSObject>> e : byFolder.entrySet()) {
            JSObject f = new JSObject();
            f.put("name", folderPath.get(e.getKey()));
            f.put("path", e.getKey());
            JSArray songs = new JSArray();
            for (JSObject s : e.getValue()) songs.put(s);
            f.put("songs", songs);
            folders.add(f);
        }
        Collections.sort(folders, (a, b) -> a.getString("name", "").compareToIgnoreCase(b.getString("name", "")));

        JSArray out = new JSArray();
        for (JSObject f : folders) out.put(f);
        JSObject ret = new JSObject();
        ret.put("folders", out);
        call.resolve(ret);
    }
}
