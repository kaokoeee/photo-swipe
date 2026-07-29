package com.photoswipe.app.plugins;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
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

@CapacitorPlugin(name = "GalleryPlugin")
public class GalleryPlugin extends Plugin {

    private static final String TAG = "GalleryPlugin";
    private static final int DELETE_REQUEST_CODE = 90210;

    private PluginCall pendingPickCall;
    private PluginCall pendingDeleteCall;
    private int pendingDeleteCount;

    /**
     * Opens the Android system photo/video picker (multi-select).
     */
    @PluginMethod
    public void pickPhotos(PluginCall call) {
        pendingPickCall = call;

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityResultLauncher<PickVisualMediaRequest> launcher =
                getActivity().getActivityResultRegistry()
                    .register("pickVisualMedia_" + System.currentTimeMillis(),
                        new ActivityResultContracts.PickMultipleVisualMedia(100),
                        this::onPickResult);
            launcher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                .build());
        } else {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"image/*", "video/*"});
            startActivityForResult(pendingPickCall, intent, "onLegacyPickResult");
        }
    }

    private void onPickResult(List<Uri> uris) {
        if (pendingPickCall == null) return;
        resolveUris(uris);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);

        // Handle legacy picker result
        if (pendingPickCall != null && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) {
                resolveUris(uris);
            } else {
                pendingPickCall.resolve(new JSObject().put("files", new JSONArray()));
            }
            return;
        }

        // Handle delete request result
        if (requestCode == DELETE_REQUEST_CODE && pendingDeleteCall != null) {
            JSObject result = new JSObject();
            if (resultCode == Activity.RESULT_OK) {
                result.put("deleted", pendingDeleteCount);
                result.put("failed", new JSONArray());
            } else {
                result.put("deleted", 0);
                result.put("failed", new JSONArray());
            }
            pendingDeleteCall.resolve(result);
            pendingDeleteCall = null;
        }
    }

    private void resolveUris(List<Uri> uris) {
        JSONArray files = new JSONArray();
        for (Uri uri : uris) {
            try {
                JSONObject f = new JSONObject();
                f.put("uri", uri.toString());

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
                if (!f.has("name")) f.put("name", "photo_" + System.currentTimeMillis());
                if (!f.has("type")) f.put("type", "image/jpeg");

                files.put(f);
            } catch (Exception e) {
                Log.e(TAG, "Error resolving URI", e);
            }
        }
        JSObject result = new JSObject();
        result.put("files", files);
        pendingPickCall.resolve(result);
        pendingPickCall = null;
    }

    /**
     * Permanently deletes photos from the device.
     *
     * Android 11+ uses MediaStore.createDeleteRequest() which shows
     * a system confirmation dialog. Android 10- uses direct delete.
     */
    @PluginMethod
    public void deletePhotos(PluginCall call) {
        JSONArray uriArray = call.getArray("uris");
        if (uriArray == null || uriArray.length() == 0) {
            call.reject("No URIs provided");
            return;
        }

        // Resolve picker URIs to proper MediaStore URIs
        List<Uri> mediaStoreUris = new ArrayList<>();
        for (int i = 0; i < uriArray.length(); i++) {
            try {
                Uri pickerUri = Uri.parse(uriArray.getString(i));
                Uri resolved = resolveToMediaStoreUri(pickerUri);
                if (resolved != null) {
                    mediaStoreUris.add(resolved);
                }
            } catch (Exception ignored) {}
        }

        if (mediaStoreUris.isEmpty()) {
            call.reject("No valid URIs to delete");
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(
                    getContext().getContentResolver(), mediaStoreUris);
                pendingDeleteCall = call;
                pendingDeleteCount = mediaStoreUris.size();
                getActivity().startIntentSenderForResult(
                    pi.getIntentSender(),
                    DELETE_REQUEST_CODE,
                    null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                call.reject("Failed to show delete dialog: " + e.getMessage());
            } catch (Exception e) {
                call.reject("Delete error: " + e.getMessage());
            }
        } else {
            int deleted = 0;
            JSONArray failed = new JSONArray();
            for (Uri uri : mediaStoreUris) {
                try {
                    int rows = getContext().getContentResolver().delete(uri, null, null);
                    if (rows > 0) deleted++;
                    else {
                        try {
                            JSONObject f = new JSONObject();
                            f.put("uri", uri.toString());
                            f.put("reason", "No rows deleted");
                            failed.put(f);
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    try {
                        JSONObject f = new JSONObject();
                        f.put("uri", uri.toString());
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
    }

    /**
     * Converts a picker/content URI to a proper MediaStore URI
     * (content://media/external/images/media/ID) that createDeleteRequest accepts.
     */
    private Uri resolveToMediaStoreUri(Uri uri) {
        // If it's already a standard MediaStore URI, return as-is
        String uriStr = uri.toString();
        if (uriStr.startsWith("content://media/")) {
            return uri;
        }

        // Query the picker URI to get the real MediaStore ID and MIME type
        try (Cursor cursor = getContext().getContentResolver().query(
                uri,
                new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DISPLAY_NAME
                },
                null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int typeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);

                if (idIdx >= 0) {
                    long mediaId = cursor.getLong(idIdx);
                    String mimeType = typeIdx >= 0 ? cursor.getString(typeIdx) : "image/jpeg";

                    Uri baseUri;
                    if (mimeType != null && mimeType.startsWith("video/")) {
                        baseUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    } else {
                        baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    }
                    return ContentUris.withAppendedId(baseUri, mediaId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve URI: " + uri, e);
        }
        return null;
    }
}
