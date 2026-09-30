package com.symmetricalpalmtree.soil.crypto

import com.symmetricalpalmtree.soil.crypto.RotationPlan.Aftermath
import com.symmetricalpalmtree.soil.crypto.RotationPlan.CommitStep
import com.symmetricalpalmtree.soil.crypto.RotationPlan.Failure
import com.symmetricalpalmtree.soil.crypto.RotationPlan.Kind
import com.symmetricalpalmtree.soil.crypto.RotationPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** D2's tables: the id order, the id kinds, the per-file outcome, the commit's side-effect list. */
class RotationPlanTest {

    @Test
    fun orderIsNotebooksThenStoresThenIndexLast() {
        val ids = RotationPlan.order(listOf("nb-b", "nb-a"), listOf("com.x.pad", "com.x.tags"))
        assertEquals(listOf("nb-b", "nb-a", "store:com.x.pad", "store:com.x.tags", RotationPlan.INDEX_ID), ids)
        assertEquals(RotationPlan.INDEX_ID, ids.last())
    }

    @Test
    fun orderDropsDuplicatesAndForeignIds() {
        // A notebook id that happens to look like the index or a store id can never be walked twice
        // or as the wrong kind.
        val ids = RotationPlan.order(listOf("nb-1", "nb-1", RotationPlan.INDEX_ID, "store:evil"), emptyList())
        assertEquals(listOf("nb-1", RotationPlan.INDEX_ID), ids)
    }

    @Test
    fun emptyLibraryStillRotatesTheIndex() {
        assertEquals(listOf(RotationPlan.INDEX_ID), RotationPlan.order(emptyList(), emptyList()))
    }

    @Test
    fun kinds() {
        assertEquals(Kind.INDEX, RotationPlan.kindOf(RotationPlan.INDEX_ID))
        assertEquals(Kind.STORE, RotationPlan.kindOf("store:com.x.pad"))
        assertEquals(Kind.ITEM, RotationPlan.kindOf("0f3c-uuid"))
        assertEquals("com.x.pad", RotationPlan.storePackage("store:com.x.pad"))
        assertNull(RotationPlan.storePackage("nb"))
        assertNull(RotationPlan.storePackage("store:"))
        assertEquals("store:com.x.pad", RotationPlan.storeId("com.x.pad"))
    }

    @Test
    fun perFileOutcomeTable() {
        for (kind in Kind.values()) {
            // Already under the new key wins whatever the old key says (a resume after a late commit).
            assertEquals(Step.SKIP, RotationPlan.decide(kind, opensUnderNew = true, opensUnderOld = false))
            assertEquals(Step.SKIP, RotationPlan.decide(kind, opensUnderNew = true, opensUnderOld = true))
            assertEquals(Step.REKEY, RotationPlan.decide(kind, opensUnderNew = false, opensUnderOld = true))
        }
        assertEquals(Step.QUARANTINE, RotationPlan.decide(Kind.ITEM, opensUnderNew = false, opensUnderOld = false))
        assertEquals(Step.STOP, RotationPlan.decide(Kind.STORE, opensUnderNew = false, opensUnderOld = false))
        assertEquals(Step.STOP, RotationPlan.decide(Kind.INDEX, opensUnderNew = false, opensUnderOld = false))
    }

    @Test
    fun afterFailureTable() {
        for (kind in Kind.values()) assertEquals(Failure.TRANSIENT, RotationPlan.afterFailure(kind, opensUnderOld = true))
        assertEquals(Failure.QUARANTINE, RotationPlan.afterFailure(Kind.ITEM, opensUnderOld = false))
        assertEquals(Failure.STOP, RotationPlan.afterFailure(Kind.STORE, opensUnderOld = false))
        assertEquals(Failure.STOP, RotationPlan.afterFailure(Kind.INDEX, opensUnderOld = false))
    }

    // ── beforeRekey — arc 34 / M2: a raw-key hit on a resume is not "under the old key" ──────

    /** A fake of the three facts `beforeRekey` may ask for, recording what it was asked. */
    private class Before(
        private val rawKeyOpens: Boolean,
        private val underNew: Boolean = false,
        private val underOld: Boolean = false,
    ) {
        var askedRaw = 0
        var askedNew = 0
        var askedOld = 0
        fun run(kind: Kind, resumed: Boolean): Step = RotationPlan.beforeRekey(
            kind,
            resumed = resumed,
            rawKeyOpens = { askedRaw++; rawKeyOpens },
            opensUnderNew = { askedNew++; underNew },
            opensUnderOld = { askedOld++; underOld },
        )
    }

    @Test
    fun onResumeARawKeyWarmedUnderTheNewKeyAnswersSkipWithoutARekey() {
        // The verifier's scenario: between a Cancel and the Resume, `KeyResolver`'s two-candidate
        // open re-keyed nothing but warmed the cache under the NEW passphrase. The hit opens the
        // file, but it says nothing about which key — the new key is asked first.
        for (kind in Kind.values()) {
            val f = Before(rawKeyOpens = true, underNew = true)
            assertEquals(Step.SKIP, f.run(kind, resumed = true))
            assertEquals(1, f.askedNew)
            assertEquals(0, f.askedOld)
        }
    }

    @Test
    fun onResumeARawKeyHitNotUnderTheNewKeyIsTheOldKeyForFree() {
        for (kind in Kind.values()) {
            val f = Before(rawKeyOpens = true, underNew = false)
            assertEquals(Step.REKEY, f.run(kind, resumed = true))
            assertEquals(1, f.askedNew)
            assertEquals(0, f.askedOld) // the hit settles it — no second KDF
        }
    }

    @Test
    fun onStartARawKeyHitIsTheOldKeyWithNoKdf() {
        // No marker existed before `start`, so nothing can have been warmed under the new key:
        // the cheap answer stands and neither passphrase is verified.
        for (kind in Kind.values()) {
            val f = Before(rawKeyOpens = true)
            assertEquals(Step.REKEY, f.run(kind, resumed = false))
            assertEquals(0, f.askedNew)
            assertEquals(0, f.askedOld)
        }
    }

    @Test
    fun aCacheMissVerifiesTheLikelierKeyFirst() {
        // On a start every file is under the old key: old first, new only when it fails.
        val start = Before(rawKeyOpens = false, underOld = true)
        assertEquals(Step.REKEY, start.run(Kind.ITEM, resumed = false))
        assertEquals(0, start.askedNew)
        // On a resume a miss is most often a file whose rekey invalidated the key: new first.
        val resume = Before(rawKeyOpens = false, underNew = true)
        assertEquals(Step.SKIP, resume.run(Kind.ITEM, resumed = true))
        assertEquals(0, resume.askedOld)
        val resumeOld = Before(rawKeyOpens = false, underOld = true)
        assertEquals(Step.REKEY, resumeOld.run(Kind.ITEM, resumed = true))
        assertEquals(1, resumeOld.askedNew)
        assertEquals(1, resumeOld.askedOld)
    }

    @Test
    fun beforeRekeyUnderNeitherKeyReadsTheTable() {
        for (resumed in listOf(false, true)) {
            assertEquals(Step.QUARANTINE, Before(rawKeyOpens = false).run(Kind.ITEM, resumed))
            assertEquals(Step.STOP, Before(rawKeyOpens = false).run(Kind.STORE, resumed))
            assertEquals(Step.STOP, Before(rawKeyOpens = false).run(Kind.INDEX, resumed))
        }
    }

    // ── afterThrow — arc 34 / M1: a missing original is recovered first, never judged ─────

    /** A fake of the four facts `afterThrow` may ask for, recording what it was asked. */
    private class Facts(
        private var present: Boolean,
        private val presentAfterRecover: Boolean = present,
        private val underNew: Boolean = false,
        private val underOld: Boolean = false,
    ) {
        var recovered = 0
        var askedNew = 0
        var askedOld = 0
        fun run(kind: Kind): Aftermath = RotationPlan.afterThrow(
            kind,
            originalExists = { present },
            recover = { recovered++; present = presentAfterRecover },
            opensUnderNew = { askedNew++; underNew },
            opensUnderOld = { askedOld++; underOld },
        )
    }

    @Test
    fun bothKeptIsFinishedByRecoveryAndAnswersDone() {
        // `RekeyCommit.Outcome.BothKept`: `X.old.bak` + `X.rekey.tmp`, no `X`. Recovery puts the
        // verified tmp back as `X`; it opens under the new key → DONE for every kind, no quarantine.
        for (kind in Kind.values()) {
            val f = Facts(present = false, presentAfterRecover = true, underNew = true, underOld = false)
            assertEquals(Aftermath.DONE, f.run(kind))
            assertEquals(1, f.recovered)
            assertEquals(0, f.askedOld)
        }
    }

    @Test
    fun aPresentOriginalIsNeverRecoveredAndReadsTheOldTable() {
        // The export failed, or the commit landed late: `X` stands. Recovery is not run.
        for (kind in Kind.values()) {
            val late = Facts(present = true, underNew = true)
            assertEquals(Aftermath.DONE, late.run(kind))
            assertEquals(0, late.recovered)
            val transient = Facts(present = true, underOld = true)
            assertEquals(Aftermath.TRANSIENT, transient.run(kind))
            assertEquals(0, transient.recovered)
        }
        assertEquals(Aftermath.QUARANTINE, Facts(present = true).run(Kind.ITEM))
        assertEquals(Aftermath.STOP, Facts(present = true).run(Kind.STORE))
        assertEquals(Aftermath.STOP, Facts(present = true).run(Kind.INDEX))
    }

    @Test
    fun aStillMissingOriginalIsTransientNotAVerdict() {
        // Recovery could not put a file back (both leftovers unverified, or the rename failed):
        // nothing on disk answers the key question, so the file stays pending for the next resume.
        for (kind in Kind.values()) {
            val f = Facts(present = false, presentAfterRecover = false)
            assertEquals(Aftermath.TRANSIENT, f.run(kind))
            assertEquals(1, f.recovered)
            assertEquals(0, f.askedNew)
            assertEquals(0, f.askedOld)
        }
    }

    @Test
    fun recoveryThatRestoresTheOldCopyIsTransient() {
        // The tmp did not verify, the bak did: the original is back under the OLD key → try again.
        for (kind in Kind.values()) {
            val f = Facts(present = false, presentAfterRecover = true, underNew = false, underOld = true)
            assertEquals(Aftermath.TRANSIENT, f.run(kind))
            assertTrue(f.askedNew == 1 && f.askedOld == 1)
            assertFalse(f.recovered == 0)
        }
    }

    @Test
    fun resumeCandidatesAreNewRowsOrLiveRawKeys() {
        val startedAt = 1_000L
        val library = listOf(
            "pending" to 10L,      // still in the marker — never a candidate
            "done-old" to 10L,     // older than the marker, raw key invalidated by its rekey — done
            "created-since" to 2_000L, // minted under the old key after the marker — must join
            "imported-since" to 1_000L, // updatedAt == startedAt counts as since
            "warm-raw" to 10L,     // old row whose cached raw key still opens it — under the old key
        )
        val out = RotationPlan.resumeCandidates(
            globalNotebooks = library,
            pendingIds = setOf("pending"),
            startedAt = startedAt,
            rawKeyOpens = { it == "warm-raw" || it == "pending" },
        )
        assertEquals(listOf("created-since", "imported-since", "warm-raw"), out)
    }

    @Test
    fun resumeCandidatesWithNoStartedAtChecksEveryRow() {
        // A marker from a build before `startedAt` existed reads 0: every non-pending row is a
        // candidate (a KDF each — the safe direction).
        val out = RotationPlan.resumeCandidates(listOf("a" to 5L, "b" to 5L), setOf("b"), startedAt = 0L) { false }
        assertEquals(listOf("a"), out)
    }

    @Test
    fun commitStepsInOrder() {
        assertEquals(
            listOf(CommitStep.SET_GLOBAL, CommitStep.CLEAR_ACK, CommitStep.CLEAR_RAW_KEYS, CommitStep.SET_SESSION, CommitStep.CLEAR_MARKER),
            RotationPlan.commitSteps(minted = true),
        )
        assertEquals(
            listOf(CommitStep.SET_GLOBAL, CommitStep.CLEAR_RAW_KEYS, CommitStep.SET_SESSION, CommitStep.CLEAR_MARKER),
            RotationPlan.commitSteps(minted = false),
        )
        // The invariants the order encodes: the global is set before the marker goes, and the
        // marker goes last.
        for (minted in listOf(true, false)) {
            val steps = RotationPlan.commitSteps(minted)
            assertEquals(CommitStep.SET_GLOBAL, steps.first())
            assertEquals(CommitStep.CLEAR_MARKER, steps.last())
        }
    }
}
