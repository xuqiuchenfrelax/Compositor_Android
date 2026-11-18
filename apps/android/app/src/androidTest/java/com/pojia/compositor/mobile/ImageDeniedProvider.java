package com.pojia.compositor.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** A real image source under the standalone provider UID; the target receives no read grant. */
public final class ImageDeniedProvider extends ContentProvider {
    public static final String AUTHORITY = "com.pojia.compositor.mobile.test.deniedimage";
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!uri.getPath().equals("/fixture.png") || !mode.equals("r")) throw new FileNotFoundException("Unknown image fixture");
        File file = new File(getContext().getCacheDir(), "p21-denied-fixture.png");
        if (!file.exists()) {
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.eraseColor(Color.BLUE);
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Could not encode denied image");
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            finally { bitmap.recycle(); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
