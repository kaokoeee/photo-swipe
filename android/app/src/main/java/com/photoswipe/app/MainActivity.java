package com.photoswipe.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.photoswipe.app.plugins.GalleryPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GalleryPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
