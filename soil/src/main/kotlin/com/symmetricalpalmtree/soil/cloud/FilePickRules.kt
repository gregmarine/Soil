package com.symmetricalpalmtree.soil.cloud

/**
 * **The file-pick screen's decision core**, pure: what the asking app's filter comes to, which
 * title the screen wears for it, how big a file it will carry, and the name the answer wears.
 */
object FilePickRules {

    /** A cache can hold a few of these; the sketch's own image cap is far under it. */
    const val MAX_BYTES: Long = 64L * 1024 * 1024

    /** The filter as the device's picker takes it: blank entries dropped, none at all meaning any file. */
    fun mimeTypes(asked: Array<String>?): Array<String> {
        val clean = asked.orEmpty().map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_MIME_LENGTH }.distinct().take(MAX_MIME_TYPES)
        return if (clean.isEmpty()) arrayOf(ANY) else clean.toTypedArray()
    }

    /** The picker's type: one family when every entry is of it, otherwise any. */
    fun pickerType(mimes: Array<String>): String {
        val families = mimes.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1 && families.single() != "*") "${families.single()}/*" else ANY
    }

    /** The screen says *Choose an image* when only images are asked for, *Choose a file* otherwise. */
    fun imagesOnly(mimes: Array<String>): Boolean = mimes.isNotEmpty() && mimes.all { it.startsWith("image/") }

    /** Whether a file of [bytes] is carried; an unknown size (negative) is read up to the cap. */
    fun fits(bytes: Long): Boolean = bytes <= MAX_BYTES

    /** The answer's name: the picked file's own, or a stand-in when it had none. */
    fun fileName(picked: String?): String = picked?.trim()?.takeIf { it.isNotEmpty() } ?: UNNAMED

    const val ANY = "*/*"
    const val UNNAMED = "file"
    private const val MAX_MIME_TYPES = 16
    private const val MAX_MIME_LENGTH = 128
}
