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
                String displayName = "photo_" + System.currentTimeMillis();
                String mimeType = "image/jpeg";
                long size = 0;
                long mediaId = -1;

                // Step 1: Query the picker URI for metadata
                try (Cursor cursor = getContext().getContentResolver()
                        .query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idIdx   = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                        int nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        int typeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
                        int sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);

                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                        if (typeIdx >= 0) mimeType = cursor.getString(typeIdx);
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                        if (idIdx >= 0) mediaId = cursor.getLong(idIdx);
                    }
                }

                Uri mediaStoreUri = null;

                // Step 2a: If we got a valid _id, construct MediaStore URI directly
                if (mediaId > 0) {
                    Uri base = mimeType.startsWith("video/")
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    mediaStoreUri = ContentUris.withAppendedId(base, mediaId);
                }

                // Step 2b: Fallback — query MediaStore directly by display name + size
                if (mediaStoreUri == null) {
                    mediaStoreUri = findMediaByDisplayName(displayName, size, mimeType);
                }

                // Step 3: Build result
                JSONObject f = new JSONObject();
                f.put("name", displayName);
                f.put("type", mimeType);
                f.put("uri", mediaStoreUri != null
                    ? mediaStoreUri.toString()
                    : uri.toString());  // last-resort fallback to picker URI
                if (mediaStoreUri != null) {
                    f.put("_pickerUri", uri.toString());
                }

                files.put(f);
            } catch (Exception e) {
                Log.e(TAG, "Error resolving URI: " + uri, e);
            }
        }
        JSObject result = new JSObject();
        result.put("files", files);
        pendingPickCall.resolve(result);
        pendingPickCall = null;
    }

    /**
     * Queries MediaStore directly by display name and size to find the
     * real MediaStore _id. This is a fallback when picker URI doesn't
     * expose _id.
     */
    private Uri findMediaByDisplayName(String displayName, long size, String mimeType) {
        boolean isVideo = mimeType.startsWith("video/");
        Uri collection = isVideo
            ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        String[] args = new String[]{displayName};

        try (Cursor cursor = getContext().getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE},
                selection, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {

            if (cursor != null && cursor.moveToFirst()) {
                long bestId = -1;
                do {
                    int idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                    int szIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                    long rowId = idIdx >= 0 ? cursor.getLong(idIdx) : -1;
                    long rowSize = szIdx >= 0 ? cursor.getLong(szIdx) : 0;

                    // Exact size match is ideal
                    if (size > 0 && rowSize == size) {
                        return ContentUris.withAppendedId(collection, rowId);
                    }
                    // Keep first result as fallback
                    if (bestId < 0) bestId = rowId;
                } while (cursor.moveToNext());

                if (bestId > 0) {
                    return ContentUris.withAppendedId(collection, bestId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore", e);
        }

        // Also search the other collection (image vs video mismatch)
        Uri altCollection = isVideo
            ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        try (Cursor cursor = getContext().getContentResolver().query(
                altCollection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                new String[]{displayName},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {

            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                if (idIdx >= 0) {
                    return ContentUris.withAppendedId(altCollection, cursor.getLong(idIdx));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying alt MediaStore", e);
        }

        return null;
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

        // URIs should already be resolved to MediaStore format at pick time
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < uriArray.length(); i++) {
            try {
                uris.add(Uri.parse(uriArray.getString(i)));
            } catch (Exception ignored) {}
        }

        if (uris.isEmpty()) {
            call.reject("No valid URIs");
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // Debug: verify all URIs have numeric IDs
                for (Uri u : uris) {
                    String last = u.getLastPathSegment();
                    if (last == null || !last.matches("\\d+")) {
                        call.reject("Bad URI (last segment not numeric): " + u.toString());
                        return;
                    }
                }
                PendingIntent pi = MediaStore.createDeleteRequest(
                    getContext().getContentResolver(), uris);
                pendingDeleteCall = call;
                pendingDeleteCount = uris.size();
                getActivity().startIntentSenderForResult(
                    pi.getIntentSender(),
                    DELETE_REQUEST_CODE,
                    null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                call.reject("Failed to show delete dialog: " + e.getMessage());
            } catch (Exception e) {
                String sampleUri = uris.isEmpty() ? "none" : uris.get(0).toString();
                call.reject("Delete error [" + sampleUri + "]: " + e.getMessage());
            }
        } else {
            int deleted = 0;
            JSONArray failed = new JSONArray();
            for (Uri uri : uris) {
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

}
