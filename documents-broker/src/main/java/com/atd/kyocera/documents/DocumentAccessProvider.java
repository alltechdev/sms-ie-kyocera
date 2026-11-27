/* SPDX-License-Identifier: GPL-3.0-or-later
 * A permission-protected bridge to the framework document provider.
 * All files remain owned and mediated by Android's ExternalStorageProvider. */
package com.atd.kyocera.documents;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;

public final class DocumentAccessProvider extends ContentProvider {
    private static final String LOCAL = "com.atd.kyocera.documents";
    private static final String STORAGE = "com.android.externalstorage.documents";
    private static final String ACCESS = "com.github.tmo1.sms_ie.permission.DOCUMENTS";
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
        "android:createDocument", "android:deleteDocument", "android:isChildDocument"));
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("root", "document", "tree"));

    @Override public boolean onCreate() { return true; }
    private ContentResolver resolver() { return getContext().getContentResolver(); }

    private Uri upstream(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !LOCAL.equals(uri.getAuthority()) ||
                uri.getPathSegments().isEmpty() ||
                !KINDS.contains(uri.getPathSegments().get(0))) {
            throw new IllegalArgumentException("Not a local document URI");
        }
        return uri.buildUpon().authority(STORAGE).build();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String order) {
        return query(uri, projection, selection, args, order, null);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String order, CancellationSignal cancel) {
        Uri target = upstream(uri);
        long identity = Binder.clearCallingIdentity();
        try { return resolver().query(target, projection, selection, args, order, cancel); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public String getType(Uri uri) {
        getContext().enforceCallingOrSelfPermission(ACCESS, "Document access requires authorization");
        Uri target = upstream(uri);
        long identity = Binder.clearCallingIdentity();
        try { return resolver().getType(target); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal cancel)
            throws FileNotFoundException {
        Uri target = upstream(uri);
        long identity = Binder.clearCallingIdentity();
        try { return resolver().openFileDescriptor(target, mode, cancel); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.Transport does not enforce its read/write permission
        // for call(). Enforce before clearing Binder identity or delegating.
        getContext().enforceCallingOrSelfPermission(ACCESS, "Document access is limited to authorized clients");
        if (!METHODS.contains(method)) throw new UnsupportedOperationException("Unsupported document operation");
        Bundle request = extras == null ? new Bundle() : new Bundle(extras);
        for (String key : request.keySet()) {
            Object value = request.get(key);
            if (value instanceof Uri) request.putParcelable(key, upstream((Uri) value));
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle result = resolver().call(STORAGE, method, arg, request);
            if (result == null) return null;
            for (String key : result.keySet()) {
                Object value = result.get(key);
                if (value instanceof Uri) {
                    Uri uri = (Uri) value;
                    if (!STORAGE.equals(uri.getAuthority())) throw new SecurityException("Unexpected provider result");
                    result.putParcelable(key, uri.buildUpon().authority(LOCAL).build());
                }
            }
            return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
