package com.ghosthands.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * v3.16 (Everything Train R8): the downloads store's door to
 * the rest of the phone.
 *
 * Opening and sharing a stored file hands another app a
 * content:// URI (a file:// URI is refused by the platform
 * at this target SDK), and a content URI needs a provider.
 * The framework-only rule rules out androidx's FileProvider,
 * so this is the smallest honest version of one:
 *
 *  - It serves ONLY the app's private downloads directory
 *    (Downloads.DIR_NAME), one file per URI, by bare name.
 *  - It is NOT exported; readers reach a file only through
 *    a per-URI read grant the app itself hands out with an
 *    open/share intent (grantUriPermissions).
 *  - It is read-only: insert/update/delete answer with the
 *    platform's refusal, and openFile honours mode "r" only.
 *  - The name is judged before it is ever joined to the
 *    directory: anything carrying a separator or a parent
 *    reference names nothing, and the resolved file's
 *    canonical path must sit directly inside the store.
 *
 * New manifest surface, called out plainly in the release
 * notes (like v3.14's service): a provider declaration. It
 * adds NO uses-permission — the four above it in the
 * manifest are still the whole set.
 */
public class DlProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.ghosthands.app.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The store directory, or null before the app has one. */
    private File storeDir() {
        if (getContext() == null) return null;
        File dir = new File(getContext().getFilesDir(),
                Downloads.DIR_NAME);
        return dir;
    }

    /** The file a URI names, or null when the name is not a
     *  bare store name or the file is not there. The
     *  canonical-path check is the seatbelt: a name that
     *  resolves anywhere but directly inside the store names
     *  nothing here, however it was spelled. */
    private File fileFor(Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0
                || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            return null;
        }
        File dir = storeDir();
        if (dir == null) return null;
        try {
            File f = new File(dir, name);
            String canonDir = dir.getCanonicalPath();
            String canonFile = f.getCanonicalPath();
            if (!canonFile.equals(canonDir + File.separator + name)) {
                return null;
            }
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection,
                        String selection, String[] selectionArgs,
                        String sortOrder) {
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{f.getName(),
                Long.valueOf(f.length())});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = (uri == null) ? null
                : uri.getLastPathSegment();
        return Downloads.mimeForName(name == null ? "" : name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)
                && !"rt".equals(mode)) {
            throw new FileNotFoundException(
                    "the downloads store is read-only");
        }
        File f = fileFor(uri);
        if (f == null) {
            throw new FileNotFoundException(
                    "no such file in the downloads store");
        }
        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values,
                      String selection, String[] selectionArgs) {
        return 0;
    }
}
