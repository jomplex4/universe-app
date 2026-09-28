package com.digitalminds.universe;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * UNIVERSE :: MediaWebViewClient
 * Serves songs and videos from the phone at /_media/<path> with correct
 * byte ranges, so seeking works and every MP4 loads (the built-in handler
 * always streams from byte 0).
 */
public class MediaWebViewClient extends BridgeWebViewClient {

    private static final String PREFIX = "/_media/";

    public MediaWebViewClient(Bridge bridge) { super(bridge); }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        String p = url.getPath();
        if (p == null || !p.startsWith(PREFIX)) return super.shouldInterceptRequest(view, request);
        try {
            File f = new File(Uri.decode(url.getEncodedPath().substring(PREFIX.length())));
            if (!f.isFile()) return error(404, "Not Found");
            long size = f.length();
            long[] r = parseRange(header(request, "Range"), size);
            if (r == null) return error(416, "Range Not Satisfiable");
            long start = r[0], end = r[1];
            boolean partial = r[2] == 1;
            long len = end - start + 1;

            FileInputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) { long s = in.skip(start - skipped); if (s <= 0) break; skipped += s; }

            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(len));
            h.put("Cache-Control", "no-cache");
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return new WebResourceResponse(mime(f.getName()), null, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, new Bounded(in, len));
        } catch (Exception e) {
            return error(500, "Error");
        }
    }

    /** Returns {start, end, partial(0/1)} for an HTTP Range header, or null if unsatisfiable. */
    static long[] parseRange(String range, long size) {
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.trim().startsWith("bytes=")) {
            String spec = range.trim().substring(6).split(",")[0].trim();
            String[] se = spec.split("-", -1);
            String a = se[0].trim(), b = se.length > 1 ? se[1].trim() : "";
            try {
                if (a.isEmpty() && !b.isEmpty()) { start = Math.max(0, size - Long.parseLong(b)); end = size - 1; }
                else { if (!a.isEmpty()) start = Long.parseLong(a); if (!b.isEmpty()) end = Long.parseLong(b); }
                partial = true;
            } catch (NumberFormatException e) { start = 0; end = size - 1; partial = false; }
        }
        if (size == 0 || start >= size || start < 0) return null;
        end = Math.min(end, size - 1);
        if (end < start) return null;
        return new long[] { start, end, partial ? 1 : 0 };
    }

    private static String header(WebResourceRequest r, String name) {
        for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static WebResourceResponse error(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (ext) {
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "3gp": return "video/3gpp";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "m4a": case "aac": return "audio/mp4";
            case "ogg": case "opus": case "oga": return "audio/ogg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "amr": return "audio/amr";
            default: return "application/octet-stream";
        }
    }

    /** Stops after exactly `left` bytes, so a range answer never over-reads. */
    private static final class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in, long left) { super(in); this.left = left; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = super.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(Integer.MAX_VALUE, left); }
    }
}
