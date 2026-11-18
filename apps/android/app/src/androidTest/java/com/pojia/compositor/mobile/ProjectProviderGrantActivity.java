package com.pojia.compositor.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Runs under the standalone test APK's provider-owner UID without Kotlin runtime dependencies. */
public final class ProjectProviderGrantActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (getIntent().getBooleanExtra("prepareDeniedImageOnly", false)) {
                // Materialize the source under its own UID without granting the target access.
                try (java.io.InputStream input = getContentResolver().openInputStream(
                        android.net.Uri.parse("content://" + ImageDeniedProvider.AUTHORITY + "/fixture.png"))) {
                    if (input == null || input.read() < 0) throw new java.io.IOException("Missing denied image fixture");
                } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                return;
            }
            for (String mode : FakeProjectDocumentsProvider.testModes) {
                grantUriPermission("com.pojia.compositor.mobile", FakeProjectDocumentsProvider.tree(mode),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
            for (String mode : ExportDocumentsProvider.MODES) grantUriPermission("com.pojia.compositor.mobile", ExportDocumentsProvider.tree(mode),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            String restore = getIntent().getStringExtra("restoreExport");
            if (restore != null) ExportDocumentsProvider.restore(this, restore);
        } finally { finish(); }
    }
}
