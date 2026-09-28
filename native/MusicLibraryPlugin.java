package com.digitalminds.universe;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UNIVERSE :: MusicLibrary
 * Lists songs or videos stored on the phone (internal or SD), grouped by the
 * real folder they live in. Only Android's MediaStore, no extra libraries.
 */
@CapacitorPlugin(name = "MusicLibrary")
public class MusicLibraryPlugin extends Plugin {

    private final Perms perms = new Perms();

    @Override
    public void load() { perms.register(getActivity()); }

    private static String[] needed(boolean video) {
        if (Build.VERSION.SDK_INT >= 33) return new String[] { video ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_MEDIA_AUDIO" };
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    @PluginMethod
    public void getLibrary(PluginCall call) {
        boolean video = "video".equals(call.getString("kind", "audio"));
        perms.ensure(getContext(), needed(video),
            () -> new Thread(() -> read(call, video)).start(),
            () -> call.reject("PERMISSION_DENIED"));
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Perms.openAppSettings(getContext());
        call.resolve();
    }

    private void read(PluginCall call, boolean video) {
        Uri uri = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = video
            ? new String[] { MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DISPLAY_NAME }
            : new String[] { MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.ARTIST };
        // Songs: skip ringtones and notification sounds. Videos: skip tiny clips.
        String selection = (video ? MediaStore.Video.Media.DURATION : MediaStore.Audio.Media.DURATION) + " >= ?";
        String[] args = { video ? "3000" : "15000" };

        Map<String, List<JSObject>> byFolder = new HashMap<>();
        Map<String, Long> newest = new HashMap<>();
        try (Cursor c = getContext().getContentResolver().query(uri, projection, selection, args, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String path = c.getString(2);
                    if (path == null) continue;
                    File parent = new File(path).getParentFile();
                    if (parent == null) continue;
                    String title = c.getString(0);
                    if (video) {
                        String display = c.getString(4);
                        if (display != null && display.contains(".")) title = display.substring(0, display.lastIndexOf('.'));
                    }
                    if (title == null || title.isEmpty()) title = new File(path).getName();
                    String extra = c.getString(4);
                    long added = c.getLong(3);

                    JSObject item = new JSObject();
                    item.put("title", title);
                    item.put("artist", video || extra == null || extra.startsWith("<") ? "" : extra);
                    item.put("duration", c.getLong(1));
                    item.put("added", added);
                    item.put("path", path);

                    String key = parent.getAbsolutePath();
                    List<JSObject> list = byFolder.get(key);
                    if (list == null) { list = new ArrayList<>(); byFolder.put(key, list); }
                    list.add(item);
                    Long n = newest.get(key);
                    if (n == null || added > n) newest.put(key, added);
                }
            }
        } catch (Exception e) {
            call.reject("READ_FAILED", e);
            return;
        }

        JSArray out = new JSArray();
        for (Map.Entry<String, List<JSObject>> e : byFolder.entrySet()) {
            JSObject f = new JSObject();
            f.put("name", new File(e.getKey()).getName());
            f.put("path", e.getKey());
            f.put("added", newest.get(e.getKey()));
            JSArray items = new JSArray();
            for (JSObject s : e.getValue()) items.put(s);
            f.put("songs", items);
            out.put(f);
        }
        JSObject ret = new JSObject();
        ret.put("folders", out);
        call.resolve(ret);
    }
}
