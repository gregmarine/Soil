package com.symmetricalpalmtree.soil.crypto

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for a file's SQLCipher **raw key** (the derive-once cache).
 *
 * Resolution: process RAM → [DerivedKeyStore] (Keystore) → derive + persist. Every key is
 * persisted, a `NOTEBOOK`-scope notebook's included (arc 26 / U4, decision 12: the raw key is
 * cached for speed; the passphrase never is — the prompt still asks on every open, and a silent
 * read is gated by [NotebookUnlocks], not by this cache). Blocking on a miss (KDF) — call on
 * Dispatchers.IO.
 */
object KeyMaterial {

    /** Stable id for the index in the raw-key cache. */
    const val INDEX_FILE_ID = "__soil_index__"

    private val ram = ConcurrentHashMap<String, ByteArray>()

    /** Bumped by every [invalidate] (per file) and [clearAll] (every file) — see [generation]. */
    private val perFileGen = ConcurrentHashMap<String, Int>()
    @Volatile private var epoch = 0

    /**
     * A number that changes whenever [fileId]'s key is dropped (arc 26 / U6 — the U6 walk's
     * finding). A background derive queued *before* a rekey lands *after* the rekey's [invalidate]
     * and would store a key for the file that no longer exists: [rawKey] with `ifGeneration` set
     * refuses to store when the number moved. The verify-first opens would catch the stale key
     * anyway, at the cost of one wasted ~9 s derive and a verify on the next open.
     */
    fun generation(fileId: String): Long = (epoch.toLong() shl 32) or (perFileGen[fileId] ?: 0).toLong()

    /**
     * Raw key for [fileId]: RAM → Keystore → derive against [file]'s salt + persist. With
     * [ifGeneration] (a value of [generation] taken when the caller decided to derive), the derived
     * key is **not** stored if the file's key was dropped meanwhile — returned to the caller only.
     */
    fun rawKey(context: Context, fileId: String, file: File, passphrase: String, ifGeneration: Long? = null): ByteArray {
        ram[fileId]?.let { return it }
        DerivedKeyStore.get(context, fileId)?.let { ram[fileId] = it; return it }
        val key = RawKeyDerivation.deriveKey(file, passphrase)
        if (ifGeneration != null && ifGeneration != generation(fileId)) return key
        DerivedKeyStore.put(context, fileId, key)
        ram[fileId] = key
        return key
    }

    /** RAM or Keystore hit, **never derives**. Null when not yet derived on this device. A hit is
     *  **unverified** — anything that will open a file with it goes through [peekVerified]; this
     *  bare form is for "is one cached?" questions only. */
    fun peekOrLoad(context: Context, fileId: String): ByteArray? {
        ram[fileId]?.let { return it }
        return DerivedKeyStore.get(context, fileId)?.also { ram[fileId] = it }
    }

    /**
     * [peekOrLoad], **verified against [file]** (arc 26 / U6 — the raw-path audit): a cached key
     * can be stale for a file this process has not opened (the file behind the id was swapped, so
     * its salt changed — a rekey, a restore, a store wiped and re-minted), and a stale key opens
     * as "file is not a database". Every raw-path user asks here: a hit that fits is returned, one
     * that does not is dropped everywhere ([invalidate]) and the answer is null. Never derives.
     * Blocking on the verify (~35 ms): IO.
     */
    fun peekVerified(context: Context, fileId: String, file: File): ByteArray? {
        val cached = peekOrLoad(context, fileId) ?: return null
        if (SoilCrypto.verifyRawKey(file, cached)) return cached
        invalidate(context, fileId)
        return null
    }

    /** Drop one file's key everywhere — on delete, or when a cached key no longer fits the file.
     *  Clears **both** the RAM map and the Keystore entry (the Paper Phase-6 lesson: clearing only
     *  the Keystore leaks the RAM entry for the process lifetime). */
    fun invalidate(context: Context, fileId: String) {
        perFileGen.merge(fileId, 1, Int::plus)
        ram.remove(fileId)
        DerivedKeyStore.remove(context, fileId)
    }

    /** Wipe every cached key (the Encryption screen's Forget, arc 26 / U1; rotation from U3). */
    fun clearAll(context: Context) {
        epoch++
        ram.clear()
        DerivedKeyStore.clear(context)
    }
}
