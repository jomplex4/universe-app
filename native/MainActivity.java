package com.digitalminds.universe;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(StripPlugin.class);
        registerPlugin(MusicLibraryPlugin.class);
        registerPlugin(PlayerPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            if (getBridge() != null) {
                WebView wv = getBridge().getWebView();
                if (wv != null) {
                    wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
                    // Keep the web engine at full priority while the app is in the background.
                    if (Build.VERSION.SDK_INT >= 26) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
                }
            }
        } catch (Exception ignored) { }
    }
}
