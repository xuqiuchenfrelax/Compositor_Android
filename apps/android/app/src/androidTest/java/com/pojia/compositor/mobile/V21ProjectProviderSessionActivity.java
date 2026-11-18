package com.pojia.compositor.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Owns only test-APK fixture sessions; the production workspace is never opened here. */
public final class V21ProjectProviderSessionActivity extends Activity {
    private static final long BYTE_LIMIT = 32L * 1024 * 1024;
    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static File child(File root, String path) throws Exception {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) throw new IOException("Unsafe fixture path");
        File value = root;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe fixture component");
            value = new File(value, part);
            if (Files.isSymbolicLink(value.toPath())) throw new IOException("Fixture symlink refused");
        }
        if (!value.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Fixture escaped root");
        return value;
    }
    private static void inventory(File root, File current, Set<String> paths) throws Exception {
        if (Files.isSymbolicLink(current.toPath())) throw new IOException("Fixture symlink refused");
        if (current.isDirectory()) {
            File[] entries = current.listFiles(); if (entries == null) throw new IOException("Fixture listing failed");
            for (File entry : entries) inventory(root, entry, paths);
        } else {
            if (!current.isFile()) throw new IOException("Fixture is not regular");
            paths.add(root.toPath().relativize(current.toPath()).toString());
        }
    }
    private static JSONObject verify(File root, JSONObject expected) throws Exception {
        if (!root.isDirectory() || Files.isSymbolicLink(root.toPath())) throw new IOException("Missing fixture root");
        JSONArray fixtures = expected.getJSONArray("fixtures");
        if (expected.getInt("schemaVersion") != 1 || expected.getInt("sourceCurrent") != 21 || fixtures.length() < 2 || fixtures.length() > 16)
            throw new IOException("Unexpected P146 fixture schema");
        JSONObject hashes = new JSONObject(); Set<String> packages = new HashSet<>(); long total = 0;
        for (int i = 0; i < fixtures.length(); ++i) {
            JSONObject fixture = fixtures.getJSONObject(i); String name = fixture.getString("package");
            if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}\\.comp") || !packages.add(name)) throw new IOException("Invalid fixture package");
            File folder = child(root, name); JSONArray files = fixture.getJSONArray("files"); Set<String> listed = new HashSet<>();
            for (int j = 0; j < files.length(); ++j) {
                JSONObject record = files.getJSONObject(j); String path = record.getString("path");
                if (!listed.add(path)) throw new IOException("Duplicate fixture asset");
                File file = child(folder, path); long bytes = record.getLong("bytes"); total += bytes;
                if (bytes < 0 || total > BYTE_LIMIT || !file.isFile() || file.length() != bytes) throw new IOException("Fixture size mismatch");
                String digest = sha(file); if (!digest.equals(record.getString("sha256"))) throw new IOException("Fixture hash mismatch: " + name + "/" + path);
                hashes.put(name + "/" + path, digest);
            }
            Set<String> actual = new HashSet<>(); inventory(folder, folder, actual);
            if (!actual.equals(listed) || !listed.contains("manifest.json")) throw new IOException("Fixture inventory mismatch");
        }
        return new JSONObject().put("files", hashes).put("bytes", total).put("packages", packages.size());
    }
    private static void removeOwned(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Refusing cleanup through a symlink");
        if (file.isDirectory()) {
            File[] entries = file.listFiles(); if (entries == null) throw new IOException("Cannot list owned session");
            for (File entry : entries) removeOwned(entry);
        }
        if (!file.delete()) throw new IOException("Cannot remove owned session item: " + file);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ResultReceiver receiver = getIntent().getParcelableExtra("receiver");
        Bundle result = new Bundle(); int code = 1;
        try {
            String token = getIntent().getStringExtra("token"), action = getIntent().getStringExtra("action");
            if (!V21ProjectDocumentsProvider.validToken(token)) throw new IOException("Invalid P146 session token");
            File base = V21ProjectDocumentsProvider.base(this);
            if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe session base");
            File session = child(base, token);
            if ("cleanup".equals(action) && !session.exists()) {
                result.putBoolean("removed", true); result.putBoolean("passed", true); code = 0; return;
            }
            File input = new File(getFilesDir(), "p146-v21-fixtures");
            File inputManifest = child(input, "expected.json");
            String raw = V21ProjectDocumentsProvider.readText(inputManifest); JSONObject expected = new JSONObject(raw);
            JSONObject source = verify(input, expected);
            result.putString("expected", raw); result.putString("inputSHA256", sha(inputManifest));
            result.putString("source", source.toString()); result.putString("sessionPath", session.getAbsolutePath());
            if ("prepare".equals(action)) {
                if (session.exists() || (!base.isDirectory() && !base.mkdirs()) || !session.mkdir()) throw new IOException("Session already exists or cannot be created");
                Files.write(new File(session, ".owner").toPath(), token.getBytes(StandardCharsets.UTF_8));
                JSONArray fixtures = expected.getJSONArray("fixtures");
                for (int i = 0; i < fixtures.length(); ++i) {
                    JSONObject fixture = fixtures.getJSONObject(i); String name = fixture.getString("package");
                    JSONArray files = fixture.getJSONArray("files");
                    for (int j = 0; j < files.length(); ++j) {
                        String path = name + "/" + files.getJSONObject(j).getString("path");
                        File target = child(session, path);
                        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create session folder");
                        Files.copy(child(input, path).toPath(), target.toPath());
                    }
                }
                result.putString("session", verify(session, expected).toString());
                Files.write(new File(session, "ready.json").toPath(), raw.getBytes(StandardCharsets.UTF_8));
            } else if ("verify".equals(action) || "cleanup".equals(action)) {
                if (!session.isDirectory() || !V21ProjectDocumentsProvider.readText(new File(session, ".owner")).equals(token)) throw new IOException("Session ownership mismatch");
                if (new File(session, "ready.json").isFile()) result.putString("session", verify(session, expected).toString());
                if ("cleanup".equals(action)) {
                    JSONArray fixtures = expected.getJSONArray("fixtures");
                    for (int i = 0; i < fixtures.length(); ++i) revokeUriPermission(
                        DocumentsContract.buildTreeDocumentUri(V21ProjectDocumentsProvider.AUTHORITY,
                            token + "/root/" + fixtures.getJSONObject(i).getString("package")),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    removeOwned(session); result.putBoolean("removed", !session.exists());
                }
            } else throw new IOException("Unknown session action");
            getContentResolver().notifyChange(DocumentsContract.buildRootsUri(V21ProjectDocumentsProvider.AUTHORITY), null);
            result.putBoolean("passed", true); code = 0;
        } catch (Throwable failure) { result.putBoolean("passed", false); result.putString("error", failure.toString()); }
        finally { if (receiver != null) receiver.send(code, result); finish(); }
    }
}
