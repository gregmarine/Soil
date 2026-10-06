package com.symmetricalpalmtree.soil.bibleref

/** Old or New Testament. */
enum class Testament { OLD, NEW }

/**
 * One book in the canonical 66-book table: the USFM code (the cross-source key,
 * e.g. `GEN`, `1CO`), the 1-based canonical [ordinal] (used for ordering and to
 * pack a verse address into a single sortable integer — see [VerseKey]), the
 * display [name] and its [testament].
 *
 * Deliberately translation-independent: a source may label a book "Psalm" vs
 * "Psalms", but both map back to the same USFM code here.
 */
data class CanonBook(
    val usfm: String,
    val ordinal: Int,
    val name: String,
    val testament: Testament,
    /** The human abbreviations the reference parser accepts ("Ps", "1 Cor", "Song of Songs"). */
    val aliases: List<String> = emptyList(),
    /** How many chapters the book has: the BSB's `book.chapter_count`, kept here so a reference
     *  is bounded without a database. 1,189 in all. */
    val chapters: Int = 0,
)

/**
 * The 66-book Protestant canon, in order. USFM codes follow the Paratext /
 * unfoldingWord standard, and the names and ordinals are the ones
 * `tools/bible/build_bible_db.py` wrote into the `book` table — the two lists
 * must agree or a chapter title and its database row would disagree.
 *
 * Notesprout SN's, verbatim, with each book's chapter count added.
 */
object Canon {
    val books: List<CanonBook> = listOf(
        // --- Old Testament ---
        CanonBook("GEN", 1, "Genesis", Testament.OLD, listOf("gn"), chapters = 50),
        CanonBook("EXO", 2, "Exodus", Testament.OLD, listOf("ex", "exod"), chapters = 40),
        CanonBook("LEV", 3, "Leviticus", Testament.OLD, listOf("lv"), chapters = 27),
        CanonBook("NUM", 4, "Numbers", Testament.OLD, listOf("nm", "nb"), chapters = 36),
        CanonBook("DEU", 5, "Deuteronomy", Testament.OLD, listOf("dt", "deut"), chapters = 34),
        CanonBook("JOS", 6, "Joshua", Testament.OLD, listOf("jsh", "josh"), chapters = 24),
        CanonBook("JDG", 7, "Judges", Testament.OLD, listOf("jdgs", "judg"), chapters = 21),
        CanonBook("RUT", 8, "Ruth", Testament.OLD, listOf("rth"), chapters = 4),
        CanonBook("1SA", 9, "1 Samuel", Testament.OLD, listOf("1sam", "1sm"), chapters = 31),
        CanonBook("2SA", 10, "2 Samuel", Testament.OLD, listOf("2sam", "2sm"), chapters = 24),
        CanonBook("1KI", 11, "1 Kings", Testament.OLD, listOf("1kgs", "1kg"), chapters = 22),
        CanonBook("2KI", 12, "2 Kings", Testament.OLD, listOf("2kgs", "2kg"), chapters = 25),
        CanonBook("1CH", 13, "1 Chronicles", Testament.OLD, listOf("1chr", "1chron"), chapters = 29),
        CanonBook("2CH", 14, "2 Chronicles", Testament.OLD, listOf("2chr", "2chron"), chapters = 36),
        CanonBook("EZR", 15, "Ezra", Testament.OLD, chapters = 10),
        CanonBook("NEH", 16, "Nehemiah", Testament.OLD, listOf("ne"), chapters = 13),
        CanonBook("EST", 17, "Esther", Testament.OLD, listOf("esth"), chapters = 10),
        CanonBook("JOB", 18, "Job", Testament.OLD, listOf("jb"), chapters = 42),
        CanonBook("PSA", 19, "Psalms", Testament.OLD, listOf("ps", "psalm", "psm", "pss"), chapters = 150),
        CanonBook("PRO", 20, "Proverbs", Testament.OLD, listOf("prov", "prv"), chapters = 31),
        CanonBook("ECC", 21, "Ecclesiastes", Testament.OLD, listOf("eccl", "qoh"), chapters = 12),
        CanonBook("SNG", 22, "Song of Solomon", Testament.OLD, listOf("song", "songofsongs", "sos", "canticles", "cant"), chapters = 8),
        CanonBook("ISA", 23, "Isaiah", Testament.OLD, listOf("is", "isa"), chapters = 66),
        CanonBook("JER", 24, "Jeremiah", Testament.OLD, listOf("je", "jer"), chapters = 52),
        CanonBook("LAM", 25, "Lamentations", Testament.OLD, listOf("la"), chapters = 5),
        CanonBook("EZK", 26, "Ezekiel", Testament.OLD, listOf("ez", "ezek"), chapters = 48),
        CanonBook("DAN", 27, "Daniel", Testament.OLD, listOf("dn"), chapters = 12),
        CanonBook("HOS", 28, "Hosea", Testament.OLD, listOf("ho"), chapters = 14),
        CanonBook("JOL", 29, "Joel", Testament.OLD, listOf("jl"), chapters = 3),
        CanonBook("AMO", 30, "Amos", Testament.OLD, listOf("am"), chapters = 9),
        CanonBook("OBA", 31, "Obadiah", Testament.OLD, listOf("ob", "obad"), chapters = 1),
        CanonBook("JON", 32, "Jonah", Testament.OLD, listOf("jnh"), chapters = 4),
        CanonBook("MIC", 33, "Micah", Testament.OLD, listOf("mc"), chapters = 7),
        CanonBook("NAM", 34, "Nahum", Testament.OLD, listOf("na", "nah"), chapters = 3),
        CanonBook("HAB", 35, "Habakkuk", Testament.OLD, listOf("hb", "hab"), chapters = 3),
        CanonBook("ZEP", 36, "Zephaniah", Testament.OLD, listOf("zph", "zeph"), chapters = 3),
        CanonBook("HAG", 37, "Haggai", Testament.OLD, listOf("hg", "hag"), chapters = 2),
        CanonBook("ZEC", 38, "Zechariah", Testament.OLD, listOf("zc", "zech"), chapters = 14),
        CanonBook("MAL", 39, "Malachi", Testament.OLD, listOf("ml", "mal"), chapters = 4),
        // --- New Testament ---
        CanonBook("MAT", 40, "Matthew", Testament.NEW, listOf("mt", "matt"), chapters = 28),
        CanonBook("MRK", 41, "Mark", Testament.NEW, listOf("mk", "mrk"), chapters = 16),
        CanonBook("LUK", 42, "Luke", Testament.NEW, listOf("lk"), chapters = 24),
        CanonBook("JHN", 43, "John", Testament.NEW, listOf("jn", "jhn"), chapters = 21),
        CanonBook("ACT", 44, "Acts", Testament.NEW, listOf("ac"), chapters = 28),
        CanonBook("ROM", 45, "Romans", Testament.NEW, listOf("ro", "rm"), chapters = 16),
        CanonBook("1CO", 46, "1 Corinthians", Testament.NEW, listOf("1cor"), chapters = 16),
        CanonBook("2CO", 47, "2 Corinthians", Testament.NEW, listOf("2cor"), chapters = 13),
        CanonBook("GAL", 48, "Galatians", Testament.NEW, listOf("ga"), chapters = 6),
        CanonBook("EPH", 49, "Ephesians", Testament.NEW, listOf("ep"), chapters = 6),
        CanonBook("PHP", 50, "Philippians", Testament.NEW, listOf("php", "phil", "pp"), chapters = 4),
        CanonBook("COL", 51, "Colossians", Testament.NEW, listOf("co"), chapters = 4),
        CanonBook("1TH", 52, "1 Thessalonians", Testament.NEW, listOf("1thess", "1thes"), chapters = 5),
        CanonBook("2TH", 53, "2 Thessalonians", Testament.NEW, listOf("2thess", "2thes"), chapters = 3),
        CanonBook("1TI", 54, "1 Timothy", Testament.NEW, listOf("1tim"), chapters = 6),
        CanonBook("2TI", 55, "2 Timothy", Testament.NEW, listOf("2tim"), chapters = 4),
        CanonBook("TIT", 56, "Titus", Testament.NEW, listOf("ti"), chapters = 3),
        CanonBook("PHM", 57, "Philemon", Testament.NEW, listOf("phm", "phlm", "philem"), chapters = 1),
        CanonBook("HEB", 58, "Hebrews", Testament.NEW, listOf("he"), chapters = 13),
        CanonBook("JAS", 59, "James", Testament.NEW, listOf("jm", "jas"), chapters = 5),
        CanonBook("1PE", 60, "1 Peter", Testament.NEW, listOf("1pet", "1pt"), chapters = 5),
        CanonBook("2PE", 61, "2 Peter", Testament.NEW, listOf("2pet", "2pt"), chapters = 3),
        CanonBook("1JN", 62, "1 John", Testament.NEW, listOf("1jn", "1jhn", "1jo"), chapters = 5),
        CanonBook("2JN", 63, "2 John", Testament.NEW, listOf("2jn", "2jhn", "2jo"), chapters = 1),
        CanonBook("3JN", 64, "3 John", Testament.NEW, listOf("3jn", "3jhn", "3jo"), chapters = 1),
        CanonBook("JUD", 65, "Jude", Testament.NEW, listOf("jud", "jd"), chapters = 1),
        CanonBook("REV", 66, "Revelation", Testament.NEW, listOf("re", "rev", "apocalypse", "apoc"), chapters = 22),
    )

    private val byUsfm: Map<String, CanonBook> = books.associateBy { it.usfm }

    // Declared before byAlias: its initializer calls normalize(), which uses these, and an
    // object's properties initialize in declaration order.
    private val romanPrefix = Regex("^(iii|ii|i)\\s+")
    private val ordinalPrefix = Regex("^(1st|2nd|3rd|first|second|third)\\s+")
    private val strip = Regex("[\\s.]+")

    /** Normalised alias → book, built once from names, USFM codes and aliases. */
    private val byAlias: Map<String, CanonBook> = buildMap {
        fun add(key: String, b: CanonBook) {
            val n = normalize(key)
            if (n.isNotEmpty()) put(n, b)
        }
        for (b in books) {
            add(b.usfm, b)
            add(b.name, b)
            b.aliases.forEach { add(it, b) }
        }
    }

    fun byUsfm(usfm: String): CanonBook =
        byUsfm[usfm.uppercase()] ?: error("Unknown USFM code: $usfm")

    fun tryUsfm(usfm: String): CanonBook? = byUsfm[usfm.uppercase()]

    /**
     * The name a **chapter** wears — on the page heading and in the running head — as distinct
     * from the name the **book** wears in the index. They differ for exactly one book: the book
     * is "Psalms" but a chapter of it is "Psalm 23", the way every printed Bible says it (the
     * user's call at the arc-37 freeze). Every other book names its chapters as itself.
     */
    fun chapterTitleName(usfm: String): String =
        if (usfm.equals("PSA", ignoreCase = true)) "Psalm" else byUsfm(usfm).name

    fun byOrdinal(ordinal: Int): CanonBook = books[ordinal - 1]

    /** Human-typed or source book name → canon entry, or null if unrecognised. */
    fun lookup(name: String): CanonBook? = byAlias[normalize(name)]

    /** The book's chapter count, 0 for a code that is not a book: [ReferenceResolver]'s
     *  `chapterCount` where there is no database to ask. */
    fun chapterCount(usfm: String): Int = tryUsfm(usfm)?.chapters ?: 0

    /** Every spelling [lookup] knows for the book, as written: the name, the code, the aliases. */
    fun spellings(book: CanonBook): List<String> = listOf(book.name, book.usfm) + book.aliases

    /**
     * Lowercases and strips whitespace/punctuation so "1 Cor.", "1cor" and "I Corinthians"
     * collapse toward a common key. Leading Roman numerals and ordinal words for the numbered
     * books are converted to digits first.
     */
    private fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        // Patterns are anchored at ^, so replace() matches at most once.
        s = romanPrefix.replace(s) { m ->
            when (m.groupValues[1]) { "iii" -> "3"; "ii" -> "2"; else -> "1" }
        }
        s = ordinalPrefix.replace(s) { m ->
            when (m.groupValues[1]) {
                "2nd", "second" -> "2"; "3rd", "third" -> "3"; else -> "1"
            }
        }
        return s.replace(strip, "")
    }
}
