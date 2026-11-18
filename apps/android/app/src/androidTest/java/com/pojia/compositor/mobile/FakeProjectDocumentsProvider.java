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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone test APK provider: no dependency on Kotlin or the target APK's runtime. */
public final class FakeProjectDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.pojia.compositor.mobile.test.projects";
    public static final String LAYER_ID = "A2000000-0000-4000-8000-000000000003";
    public static final String[] testModes = { "ok", "pipe", "alias", "duplicate", "unknown", "outside", "revoked", "editable" };
    public static final byte[] manifest = ("{\"format\":\"com.compositor.project\",\"version\":13,\"layers\":[{\"id\":\""
        + LAYER_ID + "\",\"name\":\"Photo\",\"imageFile\":\"" + LAYER_ID + ".png\"}]}")
        .getBytes(StandardCharsets.UTF_8);
    public static final byte[] asset = { 1, 2, 3, 4, 5, 6 };
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    public static Uri tree(String mode) {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, mode + "/root");
    }
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] names = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(names);
        Object[] row = new Object[names.length];
        for (int index = 0; index < names.length; ++index) {
            switch (names[index]) {
                case DocumentsContract.Root.COLUMN_ROOT_ID: row[index] = "ok"; break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row[index] = "ok/root"; break;
                case DocumentsContract.Root.COLUMN_TITLE: row[index] = "Project transport test"; break;
                case DocumentsContract.Root.COLUMN_FLAGS: row[index] = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD; break;
                default: row[index] = null;
            }
        }
        cursor.addRow(row);
        Object[] editable = new Object[names.length];
        for (int index = 0; index < names.length; ++index) {
            switch (names[index]) {
                case DocumentsContract.Root.COLUMN_ROOT_ID: editable[index] = "editable"; break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: editable[index] = "editable/root"; break;
                case DocumentsContract.Root.COLUMN_TITLE: editable[index] = "Editable project test"; break;
                case DocumentsContract.Root.COLUMN_FLAGS: editable[index] = DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD | DocumentsContract.Root.FLAG_LOCAL_ONLY; break;
                default: editable[index] = null;
            }
        }
        cursor.addRow(editable);
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        if (documentId.startsWith("revoked/")) throw new SecurityException("Test provider permission revoked");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parentId.startsWith("revoked/")) throw new SecurityException("Test provider permission revoked");
        String mode = parentId.substring(0, parentId.indexOf('/'));
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        if (mode.equals("editable")) {
            if (parentId.equals("editable/root")) {
                addDocument(cursor, "editable/root/manifest");
                addDocument(cursor, "editable/root/images");
            } else if (parentId.equals("editable/root/images")) {
                for (String name : editableImageNames()) addDocument(cursor, "editable/root/images/" + name);
            } else throw new FileNotFoundException("Unknown editable fixture directory");
            return cursor;
        }
        if (parentId.equals(mode + "/root")) {
            addDocument(cursor, mode + "/root/manifest");
            addDocument(cursor, mode + "/root/images");
            addDocument(cursor, mode + "/root/preview");
        } else if (parentId.equals(mode + "/root/images")) {
            addDocument(cursor, mode + "/root/asset");
            if (mode.equals("alias")) addDocument(cursor, mode + "/root/alias");
            if (mode.equals("duplicate")) addDocument(cursor, mode + "/root/asset");
        } else throw new FileNotFoundException("Unknown test directory");
        return cursor;
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        String mode = parentId.substring(0, parentId.indexOf('/'));
        if (mode.equals("editable")) {
            return (parentId.equals("editable/root") || parentId.equals("editable/root/images"))
                && documentId.startsWith(parentId + "/");
        }
        if (mode.equals("outside") && parentId.equals("outside/root/images")
            && documentId.equals("outside/root/asset")) return false;
        // Opaque provider IDs do not encode the declared parent relationship.
        if (parentId.equals(mode + "/root")) {
            return documentId.equals(mode + "/root/manifest") || documentId.equals(mode + "/root/images")
                || documentId.equals(mode + "/root/preview") || documentId.equals(mode + "/root/asset")
                || documentId.equals(mode + "/root/alias");
        }
        return parentId.equals(mode + "/root/images")
            && (documentId.equals(mode + "/root/asset") || documentId.equals(mode + "/root/alias"));
    }

    @Override public Uri canonicalize(Uri uri) {
        String id = DocumentsContract.getDocumentId(uri);
        return id.equals("alias/root/alias")
            ? DocumentsContract.buildDocumentUriUsingTree(uri, "alias/root/asset") : uri;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        if (!mode.equals("r")) throw new SecurityException("The test project is read-only");
        if (documentId.startsWith("revoked/")) throw new SecurityException("Test provider permission revoked");
        String kind = documentId.substring(documentId.lastIndexOf('/') + 1);
        byte[] bytes;
        if (documentId.startsWith("editable/")) bytes = editableBytes(documentId);
        else if (kind.equals("manifest")) bytes = manifest;
        else if (kind.equals("asset") || kind.equals("alias")) bytes = asset;
        else throw new FileNotFoundException("Not a regular test document");
        try {
            if (documentId.startsWith("pipe/") && kind.equals("asset")) {
                ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
                descriptors[1].close();
                return descriptors[0];
            }
            File folder = new File(getContext().getFilesDir(), "project-provider");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create test provider storage");
            File file = new File(folder, documentId.replace('/', '_'));
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Cannot open test document");
            failure.initCause(error);
            throw failure;
        }
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        if (id.startsWith("editable/")) {
            boolean directory = id.equals("editable/root") || id.equals("editable/root/images");
            String name = id.equals("editable/root") ? "nearest-editable.comp"
                : id.equals("editable/root/images") ? "images"
                : id.equals("editable/root/manifest") ? "manifest.json" : id.substring(id.lastIndexOf('/') + 1);
            byte[] bytes = directory ? null : editableBytes(id);
            documentRow(cursor, id, name, directory ? DocumentsContract.Document.MIME_TYPE_DIR
                : id.equals("editable/root/manifest") ? "application/json" : "image/png", bytes == null ? null : (long) bytes.length);
            return;
        }
        String kind = id.substring(id.lastIndexOf('/') + 1);
        boolean directory = kind.equals("root") || kind.equals("images") || kind.equals("preview");
        String name;
        switch (kind) {
            case "root": name = "test.comp"; break;
            case "manifest": name = "manifest.json"; break;
            case "images": name = "images"; break;
            case "preview": name = "QuickLook"; break;
            case "asset": name = LAYER_ID + ".png"; break;
            case "alias": name = "ignored.png"; break;
            default: throw new FileNotFoundException("Unknown test document");
        }
        String mime = directory ? DocumentsContract.Document.MIME_TYPE_DIR
            : kind.equals("manifest") ? "application/json" : "image/png";
        Long size = directory || (id.startsWith("unknown/") && kind.equals("asset")) ? null
            : kind.equals("manifest") ? (long) manifest.length : (long) asset.length;
        documentRow(cursor, id, name, mime, size);
    }

    private void documentRow(MatrixCursor cursor, String id, String name, String mime, Long size) {
        String[] names = cursor.getColumnNames();
        Object[] row = new Object[names.length];
        for (int index = 0; index < names.length; ++index) {
            switch (names[index]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[index] = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[index] = name; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row[index] = mime; break;
                case DocumentsContract.Document.COLUMN_FLAGS: row[index] = 0; break;
                case DocumentsContract.Document.COLUMN_SIZE: row[index] = size; break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row[index] = 1L; break;
                default: row[index] = null;
            }
        }
        cursor.addRow(row);
    }

    private String[] editableImageNames() throws FileNotFoundException {
        try {
            String[] names = getContext().getAssets().list("nearest-editable.comp/images");
            if (names == null) throw new IOException("Editable fixture images missing");
            return names;
        } catch (IOException error) { throw fixtureFailure(error); }
    }

    private byte[] editableBytes(String id) throws FileNotFoundException {
        String path;
        if (id.equals("editable/root/manifest")) path = "nearest-editable.comp/manifest.json";
        else if (id.startsWith("editable/root/images/")) {
            String name = id.substring("editable/root/images/".length());
            if (!Arrays.asList(editableImageNames()).contains(name)) throw new FileNotFoundException("Unknown editable fixture image");
            path = "nearest-editable.comp/images/" + name;
        } else throw new FileNotFoundException("Not an editable fixture file");
        try (InputStream input = getContext().getAssets().open(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        } catch (IOException error) { throw fixtureFailure(error); }
    }

    private FileNotFoundException fixtureFailure(IOException error) {
        FileNotFoundException failure = new FileNotFoundException("Cannot read editable test fixture");
        failure.initCause(error);
        return failure;
    }
}
