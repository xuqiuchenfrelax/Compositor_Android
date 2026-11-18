package com.pojia.compositor.mobile

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.security.MessageDigest

/** Optional UI metadata. A corrupt/missing entry never changes project provenance or prevents opening. */
internal object ProjectNamePreferences {
    private const val FILE = "project-display-names"
    private const val KEY = "recent"
    private const val MAX_JSON_LENGTH = 40000

    private fun key(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun load(context: Context): ProjectNameHistory = runCatching {
        val text = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@runCatching ProjectNameHistory()
        if (text.length > MAX_JSON_LENGTH) return@runCatching ProjectNameHistory()
        val array = JSONArray(text)
        if (array.length() > 64) return@runCatching ProjectNameHistory()
        ProjectNameHistory((0 until array.length()).map { index ->
            val item = array.getJSONArray(index)
            require(item.length() == 2)
            item.getString(0) to item.getString(1)
        })
    }.getOrElse { ProjectNameHistory() }

    @Synchronized
    fun projectName(context: Context, sourceIdentity: String?): String? =
        ImageExportNames.projectName(sourceIdentity,
            sourceIdentity?.takeIf { it.startsWith("saf:") }?.let { load(context).name(key(it)) })

    @Synchronized
    fun remember(context: Context, sourceIdentity: String, displayName: String) {
        if (!sourceIdentity.startsWith("saf:") || ImageExportNames.validProjectName(displayName) == null) return
        // This is a bounded convenience cache, not part of the project publication transaction.
        runCatching {
            val history = load(context)
            history.remember(key(sourceIdentity), displayName)
            val array = JSONArray()
            history.entries().forEach { (identity, name) -> array.put(JSONArray().put(identity).put(name)) }
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, array.toString()) }
        }
    }
}
