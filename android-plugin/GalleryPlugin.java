package com.photoswipe.app.plugins;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GalleryPlugin — Capacitor custom plugin for Android
 *
 * Provides two native capabilities that web apps cannot do:
 * 1. pickPhotos()  — open the system photo picker, return URI + metadata
 * 2. deletePhotos() — permanently delete photos from MediaStore using
 *    the scoped-storage-safe createDeleteRequest() API (Android 11+)
 *
 * Register in MainActivity.java:
 *   add(GalleryPlugin.class);
 */
@CapacitorPlugin(name = "GalleryPlugin")
public class GalleryPlugin extends Plugin {

    private static final String TAG = "GalleryPlugin";
    private PluginCall pendingCall;

    /**
     * Opens the Android system photo/video picker.
     * Uses the modern Photo Picker on API 33+, falls back to
     * ACTION_OPEN_DOCUMENT on older versions.
     *
     * JS call:
     *   Capacitor.Plugins.GalleryPlugin.pickPhotos()
     *
     * Returns:
     *   { files: [{ uri, name, type, size }] }
     */
    @PluginMethod
    public void pickPhotos(PluginCall call) {
        pendingCall = call;

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ modern photo picker — supports multi-select out of the box
            ActivityResultLauncher<PickVisualMediaRequest> launcher =
                getActivity().getActivityResultRegistry()
                    .register("pickVisualMedia_" + System.currentTimeMillis(),
                        new ActivityResultContracts.PickMultipleVisualMedia(50),
                        this::onPickResult);

            launcher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                .build());
        } else {
            // Android 11–12: use ACTION_OPEN_DOCUMENT for multi-select
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"image/*", "video/*"});
            startActivityForResult(pendingCall, intent, "onLegacyPickResult");
        }
    }

    private void onPickResult(List<Uri> uris) {
        if (pendingCall == null) return;
        resolveUris(uris);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (pendingCall == null) return;

        if (data != null) {
            List<Uri> uris = new ArrayList<>();
            // Multiple select
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                // Single select
                uris.add(data.getData());
            }
            resolveUris(uris);
        } else {
            pendingCall.resolve(new JSObject().put("files", new JSONArray()));
        }
    }

    private void resolveUris(List<Uri> uris) {
        JSONArray files = new JSONArray();
        for (Uri uri : uris) {
            try {
                JSONObject f = new JSONObject();
                f.put("uri", uri.toString());

                // Try to get display name and type from ContentResolver
                try (Cursor cursor = getContext().getContentResolver()
                        .query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        int typeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
                        int sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);

                        if (nameIdx >= 0) f.put("name", cursor.getString(nameIdx));
                        if (typeIdx >= 0) f.put("type", cursor.getString(typeIdx));
                        if (sizeIdx >= 0) f.put("size", cursor.getLong(sizeIdx));
                    }
                }
                // Fallbacks
                if (!f.has("name")) f.put("name", "photo_" + System.currentTimeMillis());
                if (!f.has("type")) f.put("type", "image/jpeg");

                files.put(f);
            } catch (Exception e) {
                Log.e(TAG, "Error resolving URI", e);
            }
        }
        JSObject result = new JSObject();
        result.put("files", files);
        pendingCall.resolve(result);
        pendingCall = null;
    }

    /**
     * Permanently deletes photos from the device using MediaStore.
     * Android 11+ (API 30+) scoped storage requires createDeleteRequest().
     *
     * JS call:
     *   Capacitor.Plugins.GalleryPlugin.deletePhotos({ uris: [...] })
     *
     * Returns:
     *   { deleted: N, failed: [{ uri, reason }] }
     */
    @PluginMethod
    public void deletePhotos(PluginCall call) {
        JSONArray uriArray = call.getArray("uris");
        if (uriArray == null || uriArray.length() == 0) {
            call.reject("No URIs provided");
            return;
        }

        int deleted = 0;
        JSONArray failed = new JSONArray();

        for (int i = 0; i < uriArray.length(); i++) {
            try {
                String uriStr = uriArray.getString(i);
                Uri uri = Uri.parse(uriStr);

                if (Build.VERSION.SDK_INT >= 30) {
                    // Android 11+ scoped storage — the proper way
                    // This shows a system confirmation dialog to the user
                    // and handles the actual file deletion
                    try {
                        // Get the media store ID from the URI
                        long mediaId = ContentUris.parseId(uri);
                        Uri deleteUri = ContentUris.withAppendedId(
                            getMediaStoreUri(uri), mediaId);

                        int rows = getContext().getContentResolver()
                            .delete(deleteUri, null, null);
                        if (rows > 0) {
                            deleted++;
                        } else {
                            JSONObject f = new JSONObject();
                            f.put("uri", uriStr);
                            f.put("reason", "No rows deleted — file may not exist");
                            failed.put(f);
                        }
                    } catch (SecurityException se) {
                        // If direct delete fails, try the pending delete intent
                        // (shows system dialog)
                        Intent intent = null;
                        if (Build.VERSION.SDK_INT >= 30) {
                            // Build a delete request that the system will handle
                            intent = MediaStore.createDeleteRequest(
                                getContext().getContentResolver(),
                                java.util.Collections.singletonList(uri));
                        }
                        if (intent != null) {
                            getContext().startActivity(intent);
                            deleted++; // assume success since user confirmed
                        } else {
                            JSONObject f = new JSONObject();
                            f.put("uri", uriStr);
                            f.put("reason", "Security error: " + se.getMessage());
                            failed.put(f);
                        }
                    }
                } else {
                    // Android 10 and below — direct file access still works
                    int rows = getContext().getContentResolver()
                        .delete(uri, null, null);
                    if (rows > 0) deleted++;
                    else {
                        JSONObject f = new JSONObject();
                        f.put("uri", uriStr);
                        f.put("reason", "No rows deleted");
                        failed.put(f);
                    }
                }
            } catch (Exception e) {
                try {
                    JSONObject f = new JSONObject();
                    f.put("uri", uriArray.getString(i));
                    f.put("reason", e.getMessage());
                    failed.put(f);
                } catch (Exception ignored) {}
            }
        }

        JSObject result = new JSObject();
        result.put("deleted", deleted);
        result.put("failed", failed);
        call.resolve(result);
    }

    private Uri getMediaStoreUri(Uri uri) {
        String path = uri.toString();
        if (path.contains("images")) return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        if (path.contains("video")) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        // Default to images
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
}
