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
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Read-only, session-scoped PC fixtures exposed through the ordinary system directory picker. */
public final class V21ProjectDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.pojia.compositor.mobile.test.v21projects";
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    @Override public boolean onCreate() { return true; }
    static boolean validToken(String token) { return token != null && token.matches("p146-[a-zA-Z0-9_-]{1,65}"); }
    static File base(android.content.Context context) { return new File(context.getFilesDir(), "p146-v21-provider"); }
    public static String title(String token) { return "P146 " + token; }
    static String readText(File file) throws IOException {
        if (file.length() > 524288) throw new IOException("Fixture metadata exceeds 512 KiB");
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
    private File resolve(String id) throws FileNotFoundException {
        try {
            String[] parts = id.split("/", -1);
            if (parts.length < 2 || !validToken(parts[0]) || !parts[1].equals("root")) throw new IOException("Invalid session document");
            File session = new File(base(getContext()), parts[0]);
            if (!new File(session, "ready.json").isFile()) throw new IOException("Session is not ready");
            File file = session;
            for (int index = 2; index < parts.length; ++index) {
                if (parts[index].isEmpty() || parts[index].equals(".") || parts[index].equals("..") || parts[index].contains("\\"))
                    throw new IOException("Invalid document path");
                if (index == 2 && !parts[index].matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}\\.comp")) throw new IOException("Invalid fixture package");
                file = new File(file, parts[index]);
                if (Files.isSymbolicLink(file.toPath())) throw new IOException("Fixture symlink refused");
            }
            if (!file.getCanonicalPath().startsWith(session.getCanonicalPath() + File.separator) && !file.equals(session))
                throw new IOException("Document escaped its session");
            if (!file.exists()) throw new IOException("Missing fixture document");
            return file;
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException("Unavailable P146 document: " + id);
            failure.initCause(error); throw failure;
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor result = new MatrixCursor(columns);
        File[] sessions = base(getContext()).listFiles();
        if (sessions == null) return result;
        Arrays.sort(sessions, java.util.Comparator.comparing(File::getName));
        for (File session : sessions) {
            String token = session.getName();
            if (!validToken(token) || !new File(session, "ready.json").isFile() || Files.isSymbolicLink(session.toPath())) continue;
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; ++i) switch (columns[i]) {
                case DocumentsContract.Root.COLUMN_ROOT_ID: values[i] = token; break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: values[i] = token + "/root"; break;
                case DocumentsContract.Root.COLUMN_TITLE: values[i] = title(token); break;
                case DocumentsContract.Root.COLUMN_FLAGS: values[i] = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD | DocumentsContract.Root.FLAG_LOCAL_ONLY; break;
                case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES: values[i] = 0L; break;
                default: values[i] = null;
            }
            result.addRow(values);
        }
        return result;
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        File file = resolve(id);
        boolean root = id.split("/").length == 2;
        Object[] values = new Object[cursor.getColumnNames().length];
        for (int i = 0; i < values.length; ++i) switch (cursor.getColumnNames()[i]) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[i] = root ? title(file.getName()) : file.getName(); break;
            case DocumentsContract.Document.COLUMN_MIME_TYPE: values[i] = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : file.getName().endsWith(".png") ? "image/png" : "application/json"; break;
            case DocumentsContract.Document.COLUMN_FLAGS: values[i] = 0; break;
            case DocumentsContract.Document.COLUMN_SIZE: values[i] = file.isFile() ? file.length() : null; break;
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED: values[i] = file.lastModified(); break;
            default: values[i] = null;
        }
        cursor.addRow(values);
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : COLUMNS); add(result, documentId); return result;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        long started = android.os.SystemClock.uptimeMillis();
        String loggedParent = parentId == null ? "null" : parentId.substring(0, Math.min(parentId.length(), 180));
        android.util.Log.i("P146Provider", "queryChildren start parent=" + loggedParent + " uptime=" + started);
        try {
            File directory = resolve(parentId);
            if (!directory.isDirectory()) throw new FileNotFoundException("Not a fixture directory");
            MatrixCursor result = new MatrixCursor(projection != null ? projection : COLUMNS);
            File[] files = directory.listFiles();
            if (files == null) throw new FileNotFoundException("Cannot list fixture directory");
            Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File file : files) {
                if (parentId.split("/").length == 2 && !file.getName().endsWith(".comp")) continue;
                add(result, parentId + "/" + file.getName());
            }
            android.util.Log.i("P146Provider", "queryChildren end parent=" + loggedParent + " rows=" + result.getCount() +
                " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            return result;
        } catch (FileNotFoundException | RuntimeException error) {
            android.util.Log.w("P146Provider", "queryChildren failed parent=" + loggedParent +
                " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started), error);
            throw error;
        }
    }
    @Override public boolean isChildDocument(String parentId, String documentId) {
        try { resolve(parentId); resolve(documentId); return documentId.startsWith(parentId + "/"); }
        catch (FileNotFoundException ignored) { return false; }
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        if (cancellation != null) cancellation.throwIfCanceled();
        if (!mode.equals("r")) throw new SecurityException("PC fixtures are read-only");
        File file = resolve(documentId);
        if (!file.isFile()) throw new FileNotFoundException("Not a regular fixture asset");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
