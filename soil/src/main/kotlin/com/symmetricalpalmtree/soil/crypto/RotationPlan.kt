package com.symmetricalpalmtree.soil.crypto

/**
 * The pure half of the global rotation (arc 26 / U3, D2) — everything [GlobalRotation] decides
 * that does not need a file or a context, so it is JVM-tested in full.
 *
 *  - [order]: the id list a rotation walks — `GLOBAL`-scope notebooks, then `ext:<pkg>` stores,
 *    then the index **last**. The index last is the whole point: it holds the rows the loop reads
 *    (names for progress, the quarantine flag), and after its own rekey nothing in this process
 *    may touch it until the relaunch.
 *  - [kindOf]: what an id names — the three file kinds have three paths, three cache ids and two
 *    close rituals.
 *  - [decide]: the per-file outcome table over two facts (does the file open under the new key?
 *    under the old?); [beforeRekey]: the order those facts are read in, which differs between a
 *    start and a resume; [afterThrow] / [afterFailure]: the same facts after a rekey threw.
 *  - [commitSteps]: the commit's side-effect list, in order, so the executor cannot reorder it.
 */
object RotationPlan {

    /** The id the rotation walks the index under. */
    const val INDEX_ID = KeyMaterial.INDEX_FILE_ID

    /** `ext:<pkg>` — `ExtensionStores.fileIdFor`'s shape; kept here so the plan has no Android import. */
    const val STORE_PREFIX = "store:"

    enum class Kind { ITEM, STORE, INDEX }

    fun kindOf(id: String): Kind = when {
        id == INDEX_ID -> Kind.INDEX
        id.startsWith(STORE_PREFIX) -> Kind.STORE
        else -> Kind.ITEM
    }

    /** The package a store id names; null for any other id. */
    fun storePackage(id: String): String? =
        if (kindOf(id) == Kind.STORE) id.removePrefix(STORE_PREFIX).takeIf { it.isNotEmpty() } else null

    fun storeId(pkg: String): String = STORE_PREFIX + pkg

    /** Notebooks → stores → index. [notebookIds] as the index lists them, [storePackages] as the
     *  Garden lists them (both already stable orders); duplicates dropped, the index exactly once. */
    fun order(notebookIds: List<String>, storePackages: List<String>): List<String> =
        (notebookIds.filter { kindOf(it) == Kind.ITEM } + storePackages.map(::storeId) + INDEX_ID).distinct()

    /**
     * Which `GLOBAL` notebooks a resume must add to the list: not already pending, and either
     * their row is newer than the marker (created / imported between a Cancel and the Resume —
     * under the OLD key, and the commit would strand them) or their cached raw key still opens
     * the file ([rawKeyOpens] — free to test; a rekey always invalidates it, so a hit means the
     * file was opened since, under the old key **or** the new one — [beforeRekey] settles which
     * with one verify, and a file already done answers SKIP). Rows older than the marker with no
     * live raw key are the ones already re-keyed; they are not re-verified (a KDF each).
     */
    fun resumeCandidates(
        globalNotebooks: List<Pair<String, Long>>, // id → max(createdAt, updatedAt)
        pendingIds: Set<String>,
        startedAt: Long,
        rawKeyOpens: (String) -> Boolean,
    ): List<String> = globalNotebooks
        .filter { (id, newest) -> id !in pendingIds && (newest >= startedAt || rawKeyOpens(id)) }
        .map { it.first }

    // ── Per-file outcomes ────────────────────────────────────────────────────

    enum class Step {
        /** Already under the new key (a prior interrupted run did it) — drop from pending. */
        SKIP,
        /** Under the old key — re-key it. */
        REKEY,
        /** A notebook that opens under neither key: mark it `NOTEBOOK` scope, drop from pending,
         *  carry on. Never deleted; U6's recovery is the way back. */
        QUARANTINE,
        /** A store or the index that opens under neither key — nothing can be quarantined; stop
         *  with Failed and keep it pending. Hand recovery only. */
        STOP,
    }

    /** What to do with a file before touching it. */
    fun decide(kind: Kind, opensUnderNew: Boolean, opensUnderOld: Boolean): Step = when {
        opensUnderNew -> Step.SKIP
        opensUnderOld -> Step.REKEY
        kind == Kind.ITEM -> Step.QUARANTINE
        else -> Step.STOP
    }

    /**
     * The two facts of [decide], read in the cheapest **safe** order (arc 34 / M2). A cached raw
     * key that opens the file ([rawKeyOpens], ~35 ms, no KDF) proves only that this device once
     * derived a key for it under *some* passphrase — and between a Cancel and a Resume,
     * `KeyResolver`'s two-candidate open warms the cache under the **new** passphrase for a file
     * already re-keyed. Taken as "under the old key", that hit sent an already-rotated file back
     * through a rekey that could only fail, rescued after one or two wasted KDFs and a warning.
     *
     *  - **[resumed] = false** (a `start`): no marker existed before it, so nothing can have been
     *    warmed under the new key — a hit is the old key for free, exactly as before; a miss
     *    verifies old first (every file is under it on a start), then new.
     *  - **[resumed] = true**: a hit is ambiguous, so the new key is verified **first** (one KDF);
     *    only when that fails does the hit answer "old" for free. A miss verifies new first too —
     *    on a resume a miss is most often a file whose own rekey invalidated its key.
     */
    fun beforeRekey(
        kind: Kind,
        resumed: Boolean,
        rawKeyOpens: () -> Boolean,
        opensUnderNew: () -> Boolean,
        opensUnderOld: () -> Boolean,
    ): Step {
        if (!resumed) {
            val underOld = rawKeyOpens() || opensUnderOld()
            return decide(kind, opensUnderNew = !underOld && opensUnderNew(), opensUnderOld = underOld)
        }
        if (opensUnderNew()) return Step.SKIP
        return decide(kind, opensUnderNew = false, opensUnderOld = rawKeyOpens() || opensUnderOld())
    }

    enum class Failure {
        /** The file still opens under the old key — transient (disk, a WAL that would not absorb).
         *  Keep it pending, stop with Failed; the person resumes. */
        TRANSIENT,
        /** A notebook that now opens under neither — quarantine (as [Step.QUARANTINE]). */
        QUARANTINE,
        /** A store or the index under neither — [Step.STOP]. */
        STOP,
    }

    /** What a rekey that threw means, from the same two facts re-read after the failure. A file
     *  that opens under the new key after a "failure" is a commit that landed late — [decide] on
     *  the next pass answers SKIP, so it is not a failure kind here. */
    fun afterFailure(kind: Kind, opensUnderOld: Boolean): Failure = when {
        opensUnderOld -> Failure.TRANSIENT
        kind == Kind.ITEM -> Failure.QUARANTINE
        else -> Failure.STOP
    }

    /** What [afterThrow] answers for a file whose rekey threw. */
    enum class Aftermath { DONE, TRANSIENT, QUARANTINE, STOP }

    /**
     * The whole sequence after a rekey threw (arc 34 / M1), so the executor cannot ask the key
     * question of a file that is not there. `SoilRekey.rekeyInPlace` can throw with the original
     * gone — `RekeyCommit.Outcome.BothKept` leaves `X.old.bak` + `X.rekey.tmp` and no `X` — and
     * against a missing file every verify is false, which used to read as "under neither key":
     * a good `GLOBAL` notebook quarantined to `NOTEBOOK` scope while Bootstrap's recovery later
     * restored the verified tmp; a store or the index reported STUCK though they self-heal.
     *
     *  1. If the original is missing, [recover] (`RekeyRecovery.recover` for that one file, under
     *     a verifier that knows both keys) — the plan's "if the original is missing but a verified
     *     tmp exists, finish the commit". Still missing afterwards → [Aftermath.TRANSIENT]: nothing
     *     on disk can answer the key question, the file stays pending, the next resume runs
     *     recovery again before its loop. Never a quarantine, never STUCK.
     *  2. A standing original that opens under the new key → [Aftermath.DONE] (the commit landed
     *     late, or recovery just finished it).
     *  3. Otherwise [afterFailure] over the old key, as before.
     */
    fun afterThrow(
        kind: Kind,
        originalExists: () -> Boolean,
        recover: () -> Unit,
        opensUnderNew: () -> Boolean,
        opensUnderOld: () -> Boolean,
    ): Aftermath {
        if (!originalExists()) {
            recover()
            if (!originalExists()) return Aftermath.TRANSIENT
        }
        if (opensUnderNew()) return Aftermath.DONE
        return when (afterFailure(kind, opensUnderOld())) {
            Failure.TRANSIENT -> Aftermath.TRANSIENT
            Failure.QUARANTINE -> Aftermath.QUARANTINE
            Failure.STOP -> Aftermath.STOP
        }
    }

    // ── Commit ───────────────────────────────────────────────────────────────

    enum class CommitStep {
        /** `PassphraseStore.setGlobalPassphrase(new)` — first, so a death right after still resumes
         *  to a rotation where every file skips and the commit re-runs. */
        SET_GLOBAL,
        /** Only for a minted key: `clearRecoveryKeyAcknowledged`, so Bootstrap shows it once. */
        CLEAR_ACK,
        /** `KeyMaterial.clearAll` — every cached raw key was derived against an old salt. */
        CLEAR_RAW_KEYS,
        /** `KeySession.set(new)` + `PassphraseCache.clear()` — the process copies. */
        SET_SESSION,
        /** `clearRotationMarker` — last: the journal outlives everything it guards. */
        CLEAR_MARKER,
    }

    fun commitSteps(minted: Boolean): List<CommitStep> = buildList {
        add(CommitStep.SET_GLOBAL)
        if (minted) add(CommitStep.CLEAR_ACK)
        add(CommitStep.CLEAR_RAW_KEYS)
        add(CommitStep.SET_SESSION)
        add(CommitStep.CLEAR_MARKER)
    }
}
