package com.photoswipe.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.photoswipe.app.plugins.GalleryPlugin;

/**
 * MainActivity — Capacitor entry point.
 *
 * Steps after `npx cap add android`:
 * 1. Copy GalleryPlugin.java to:
 *    android/app/src/main/java/com/photoswipe/app/plugins/GalleryPlugin.java
 *
 * 2. Replace the generated MainActivity.java with this file, OR add the
 *    registerPlugin line to the generated one:
 *
 *      registerPlugin(GalleryPlugin.class);
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GalleryPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
