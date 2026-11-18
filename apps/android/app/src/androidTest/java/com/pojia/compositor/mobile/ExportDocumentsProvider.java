package com.pojia.compositor.mobile;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/** Real standalone file-backed SAF provider with deterministic export faults and changing rename IDs. */
public final class ExportDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.pojia.compositor.mobile.test.exports";
    public static final String[] MODES = { "ok", "alias", "wrongname", "diskfull", "pipe", "corrupt", "renamethrow", "foreign", "deletefail", "revoked", "crash" };
    private static final String[] COLUMNS = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    public static Uri tree(String mode) { return DocumentsContract.buildTreeDocumentUri(AUTHORITY, mode + "/root"); }
    @Override public boolean onCreate() { return true; }
    private File storage() { return new File(getContext().getFilesDir(), "export-provider"); }
    private File file(String id) throws FileNotFoundException {
        int split = id.indexOf("/root");
        if (split < 1 || !Arrays.asList(MODES).contains(id.substring(0, split)) || id.contains("..") || id.contains("\\"))
            throw new FileNotFoundException("Unsafe export test ID");
        try {
            File root = new File(storage(), id.substring(0, split));
            String relative = id.substring(split + 5);
            if (relative.startsWith("/")) relative = relative.substring(1);
            File result = new File(root, relative).getCanonicalFile();
            if (!result.equals(root.getCanonicalFile()) && !result.getPath().startsWith(root.getCanonicalPath() + File.separator))
                throw new IOException("Export ID escaped root");
            return result;
        } catch (IOException error) { throw failure(error); }
    }
    private String mode(String id) { return id.substring(0, id.indexOf('/')); }
    private void ensure(String mode) throws FileNotFoundException {
        File root = file(mode + "/root");
        if (!root.exists() && !root.mkdirs()) throw new FileNotFoundException("Cannot create export test root");
        File old = new File(root, "old.comp");
        if (!old.exists()) copyAssets("nearest-editable.comp", old);
        if (new File(root, ".revoked").exists()) throw new SecurityException("Injected export permission loss");
    }
    public static void restore(android.content.Context context, String mode) {
        if (Arrays.asList(MODES).contains(mode)) new File(context.getFilesDir(), "export-provider/" + mode + "/.revoked").delete();
    }
    private void copyAssets(String path, File destination) throws FileNotFoundException {
        try {
            String[] children = getContext().getAssets().list(path);
            if (children != null && children.length > 0) {
                if (!destination.mkdirs()) throw new IOException("Cannot create old source fixture");
                for (String child : children) copyAssets(path + "/" + child, new File(destination, child));
            } else try (InputStream input = getContext().getAssets().open(path); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        } catch (IOException error) { throw failure(error); }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] { DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS };
        MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; ++index) switch (columns[index]) {
            case DocumentsContract.Root.COLUMN_ROOT_ID: row[index] = "ok"; break;
            case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row[index] = "ok/root"; break;
            case DocumentsContract.Root.COLUMN_TITLE: row[index] = "Export project test"; break;
            case DocumentsContract.Root.COLUMN_FLAGS: row[index] = DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD | DocumentsContract.Root.FLAG_LOCAL_ONLY; break;
            default: row[index] = null;
        }
        cursor.addRow(row); return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        ensure(mode(id)); MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS); row(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        ensure(mode(parent)); File[] children = file(parent).listFiles();
        if (children == null) throw new FileNotFoundException("Not an export test directory");
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        for (File child : children) row(cursor, parent + "/" + child.getName()); return cursor;
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        File value = file(id); if (!value.exists()) throw new FileNotFoundException("Missing export document");
        boolean directory = value.isDirectory(); String[] columns = cursor.getColumnNames(); Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; ++index) switch (columns[index]) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[index] = id; break;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[index] = id.endsWith("/root") ? "Export project test" : value.getName(); break;
            case DocumentsContract.Document.COLUMN_MIME_TYPE: row[index] = directory ? DocumentsContract.Document.MIME_TYPE_DIR
                : mimeType(value.getName()); break;
            case DocumentsContract.Document.COLUMN_FLAGS: row[index] = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME | (directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE); break;
            case DocumentsContract.Document.COLUMN_SIZE: row[index] = directory ? null : value.length(); break;
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row[index] = value.lastModified(); break;
            default: row[index] = null;
        }
        cursor.addRow(row);
    }
    private static String mimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
    @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    @Override public Uri canonicalize(Uri uri) { return uri; }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        ensure(mode(parent));
        if (mode(parent).equals("alias") && name.endsWith(".partial")) return mode(parent) + "/root/old.comp";
        if (name.contains("/") || name.contains("\\") || name.equals("..")) throw new FileNotFoundException("Unsafe requested export name");
        String actual = mode(parent).equals("wrongname") && name.endsWith(".partial") ? name + "-different" : name;
        File value = new File(file(parent), actual);
        try {
            boolean created = mime.equals(DocumentsContract.Document.MIME_TYPE_DIR) ? value.mkdir() : value.createNewFile();
            if (!created) throw new IOException("Export test name conflict");
            return parent + "/" + actual;
        } catch (IOException error) { throw failure(error); }
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        File old = file(id); File next = new File(old.getParentFile(), name);
        if (next.exists() || !old.renameTo(next)) throw new FileNotFoundException("Cannot rename export folder");
        if (mode(id).equals("renamethrow")) throw new FileNotFoundException("Fault after rename side effect");
        return id.substring(0, id.lastIndexOf('/') + 1) + name; // Changed folder and descendant IDs.
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.contains("/old.comp")) throw new SecurityException("Old source must never be deleted");
        if (mode(id).equals("deletefail")) throw new FileNotFoundException("Injected cleanup refusal");
        File value = file(id);
        if (value.isDirectory() && value.list().length != 0) throw new SecurityException("No recursive provider deletion");
        if (!value.delete()) throw new FileNotFoundException("Export document not deleted");
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled(); ensure(mode(id)); File value = file(id);
        if (!mode.equals("r") && id.contains("/old.comp/")) throw new SecurityException("Old source must never be written");
        try {
            String fault = mode(id);
            if (!mode.equals("r") && fault.equals("revoked")) {
                new File(file("revoked/root"), ".revoked").createNewFile();
                throw new SecurityException("Injected export permission loss");
            }
            if (!mode.equals("r") && fault.equals("diskfull")) throw new IOException("Injected storage exhaustion");
            if (!mode.equals("r") && fault.equals("pipe")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); pipe[0].close(); return pipe[1];
            }
            if (mode.equals("r") && fault.equals("corrupt") && id.endsWith("manifest.json") && !id.contains("/old.comp/")) {
                try (FileOutputStream output = new FileOutputStream(value)) { output.write(new byte[] { 1, 2, 3 }); }
            }
            if (mode.equals("r") && fault.equals("foreign") && id.endsWith("manifest.json") && !id.contains("/old.comp/")) {
                File foreign = new File(value.getParentFile(), "foreign.txt");
                if (!foreign.exists()) try (FileOutputStream output = new FileOutputStream(foreign)) { output.write(42); }
            }
            return ParcelFileDescriptor.open(value, ParcelFileDescriptor.parseMode(mode));
        } catch (IOException error) { throw failure(error); }
    }
    private FileNotFoundException failure(IOException error) { FileNotFoundException value = new FileNotFoundException(error.getMessage()); value.initCause(error); return value; }
}
