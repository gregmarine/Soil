package com.symmetricalpalmtree.soil.crypto

/**
 * **Was this open failure the key's fault?** (arc 26 / U6, D5) — the pure classifier the notebook
 * screen asks before it offers [NotebookRecovery]. Only a *key* failure earns the offer: a file
 * that will not decrypt is a file a passphrase can still open, while a schema or migration error
 * is a file the key already opened — prompting for a passphrase there would be wrong and futile.
 *
 * The chain of causes is walked, first verdict wins:
 *  - [SoilLockedException] — every "no key fits" refusal in this app throws it;
 *  - SQLite's corruption class (`SQLiteDatabaseCorruptException`, matched by name so the JVM can
 *    pin the table) — SQLCipher reports a wrong key as corruption, never as "wrong key";
 *  - the messages SQLCipher writes for a wrong key or an undecryptable header;
 *  - and, **negative**, Room's schema/migration wording on an [IllegalStateException].
 *
 * Nothing here reads a file, holds a key, or logs.
 */
object KeyFailure {

    private const val CORRUPT_CLASS = "SQLiteDatabaseCorruptException"

    private val keyPhrases = listOf(
        "file is not a database", "not a database", "file is encrypted", "corrupt",
    )
    private val schemaPhrases = listOf("invalid schema", "migration", "identity")

    /** True when [t] (or any cause under it) says the file could not be decrypted or read. */
    fun isKeyFailure(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var hops = 0
        while (cur != null && hops < 16) {
            when (classify(cur)) {
                Verdict.KEY -> return true
                Verdict.SCHEMA -> return false
                Verdict.UNKNOWN -> Unit
            }
            cur = cur.cause
            hops++
        }
        return false
    }

    enum class Verdict { KEY, SCHEMA, UNKNOWN }

    /** One link of the chain, over its class and message only. */
    fun classify(t: Throwable): Verdict = classify(
        isLocked = t is SoilLockedException,
        className = t.javaClass.simpleName,
        isIllegalState = t is IllegalStateException,
        message = t.message,
    )

    /** The table itself, over plain inputs — what the tests pin. */
    fun classify(isLocked: Boolean, className: String, isIllegalState: Boolean, message: String?): Verdict {
        if (isLocked) return Verdict.KEY
        if (className == CORRUPT_CLASS) return Verdict.KEY
        val m = message?.lowercase().orEmpty()
        if (keyPhrases.any { it in m }) return Verdict.KEY
        if (isIllegalState && schemaPhrases.any { it in m }) return Verdict.SCHEMA
        return Verdict.UNKNOWN
    }
}
