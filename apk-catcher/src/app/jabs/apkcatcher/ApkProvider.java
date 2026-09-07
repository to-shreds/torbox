package app.jabs.apkcatcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Read-only provider. Android enforces the temporary, per-URI permission grants. */
public final class ApkProvider extends ContentProvider {
    static final String AUTHORITY = "app.jabs.apkcatcher.apks";
    static final String MIME = "application/vnd.android.package-archive";

    @Override public boolean onCreate() { return true; }

    static File root(File cache) { return new File(cache, "apk-catcher"); }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || parts.size() != 2 || !"package.apk".equals(parts.get(1))) {
            throw new FileNotFoundException("Unknown APK");
        }
        try {
            String id = parts.get(0);
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            File base = root(getContext().getCacheDir()).getCanonicalFile();
            File candidate = new File(new File(base, id), "package.apk").getCanonicalFile();
            if (!candidate.getParentFile().getParentFile().equals(base) || !candidate.isFile()) {
                throw new FileNotFoundException("APK is no longer available");
            }
            return candidate;
        } catch (IOException | IllegalArgumentException failure) {
            throw new FileNotFoundException("APK is no longer available");
        }
    }

    @Override public String getType(Uri uri) { return MIME; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only APK");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection != null ? projection
                    : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor result = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "package.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            result.addRow(values);
            return result;
        } catch (FileNotFoundException missing) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
