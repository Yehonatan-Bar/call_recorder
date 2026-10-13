package il.co.kolrecorder;

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

/** Only individual finalized WAV files may be shared, always read-only. */
public final class RecordingProvider extends ContentProvider {
    static Uri uri(File file) { return new Uri.Builder().scheme("content").authority("il.co.kolrecorder.files").appendPath(file.getName()).build(); }
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"il.co.kolrecorder.files".equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        try { return new RecordingStore(getContext()).resolve(uri.getLastPathSegment()); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        try {
            File f = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(new RecordingStore(getContext()).title(f).replaceAll("[\\\\/:*?\"<>|]", "_") + ".wav");
                else if (OpenableColumns.SIZE.equals(column)) row.add(f.length());
                else row.add(null);
            }
            return cursor;
        } catch (IOException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
