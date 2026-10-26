package tv.pogoda.imgw;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * content://tv.pogoda.imgw.now/line: one row for the launcher's Home: "line", the weather in a sentence, and "icon",
 * the name of this app's drawable for it (ic_w_*), which the launcher loads from this package to draw beside the line.
 * The launcher asks when Home comes to the front, off its main thread. An answer older than 30 minutes is fetched
 * again first (a few seconds at most); when that fails the last one is given, and nothing when there is none or it
 * is over 6 hours old. Nothing is fetched at any other time.
 */
public class WeatherProvider extends ContentProvider {

    private static final long FRESH_MS = 30 * 60_000L;
    private static final long TOO_OLD_MS = 6 * 60 * 60_000L;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor out = new MatrixCursor(new String[]{"line", "icon"});
        Weather.Forecast f = null;
        long age = System.currentTimeMillis() - Weather.fetchedAt(getContext());
        if (age > FRESH_MS) {
            try {
                f = Weather.fetch(getContext());
            } catch (Exception ignored) {
                // fall back to the cache below
            }
        }
        if (f == null) f = Weather.cached(getContext());
        if (f != null && System.currentTimeMillis() - f.fetchedAt <= TOO_OLD_MS) {
            String icon = getContext().getResources().getResourceEntryName(Weather.iconRes(f.code, f.day));
            out.addRow(new Object[]{Weather.line(f), icon});
        }
        return out;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.tv.pogoda.line";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
