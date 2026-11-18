package com.pojia.compositor.mobile

/** Matches the desktop defaults; Android accepts either Ctrl or Command as the primary modifier. */
internal enum class FileShortcut(val key: Char, val shift: Boolean, val alt: Boolean = false) {
    NEW_CANVAS('n', false), OPEN_PROJECT('o', false), SAVE('s', false), SAVE_COPY('s', true), EXPORT_PNG('e', true), EXPORT_AS('s', true, true), EXPORT_AS_ALIAS('w', true, true);

    companion object {
        fun resolve(key: Char, control: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): FileShortcut? {
            if (!control && !meta) return null
            return entries.singleOrNull { it.key == key.lowercaseChar() && it.shift == shift && it.alt == alt }
        }
    }
}
