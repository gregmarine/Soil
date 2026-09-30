package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * What an app declares about the files of its kind: the tables, as ordered steps, and how a file
 * is tidied when it is closed.
 *
 * `steps[i]` is the DDL that takes a file from version `i` to version `i + 1`, and the version is
 * the number of steps. Soil runs only the steps a file is missing, each in its own transaction
 * with its version. A file newer than the app's schema is refused and left as it was found. A
 * step that has landed is never edited: a change is a new step.
 *
 * [purge] is run by Soil when a file is closed for good, never while it is only parked: the
 * statements that remove what the app has soft-deleted. They take no binds. Soil runs them
 * itself because a session can end without the app, when its process dies.
 *
 * Every statement is checked by [SeamSql] at construction, so a bad schema fails where it is
 * declared, and again where it arrives.
 *
 * Wire form: `String kind · int stepCount · per step (int count · String…) · int purgeCount ·
 * String…`.
 */
class SeamSchema(
    val kind: String,
    val steps: List<List<String>>,
    val purge: List<String> = emptyList(),
) : Parcelable {

    init {
        requireValid(kind, steps, purge)
    }

    val version: Int get() = steps.size

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(kind)
        dest.writeInt(steps.size)
        for (step in steps) {
            dest.writeInt(step.size)
            for (sql in step) dest.writeString(sql)
        }
        dest.writeInt(purge.size)
        for (sql in purge) dest.writeString(sql)
    }

    override fun describeContents(): Int = 0

    companion object {
        private val KIND = Regex("^[a-z][a-z0-9_]{0,31}$")

        fun isValidKind(kind: String): Boolean = KIND.matches(kind)

        /** The constructor's checks, pure so they are JVM-tested. */
        fun requireValid(kind: String, steps: List<List<String>>, purge: List<String>) {
            require(isValidKind(kind)) { "not a kind" }
            require(steps.size in 1..SeamLimits.MAX_SCHEMA_STEPS) {
                "a schema has 1..${SeamLimits.MAX_SCHEMA_STEPS} steps (${steps.size})"
            }
            for ((i, step) in steps.withIndex()) {
                require(step.size in 1..SeamLimits.MAX_STEP_STATEMENTS) {
                    "step ${i + 1} must hold 1..${SeamLimits.MAX_STEP_STATEMENTS} statements (${step.size})"
                }
                for ((j, sql) in step.withIndex()) {
                    try {
                        SeamSql.checkDdl(sql)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("step ${i + 1} statement ${j + 1}: ${e.message}", e)
                    }
                }
            }
            require(purge.size <= SeamLimits.MAX_PURGE_STATEMENTS) {
                "at most ${SeamLimits.MAX_PURGE_STATEMENTS} purge statements (${purge.size})"
            }
            for ((j, sql) in purge.withIndex()) {
                try {
                    SeamSql.checkExec(sql)
                    require(SeamSql.bindCount(sql) == 0) { "a purge statement takes no binds" }
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("purge statement ${j + 1}: ${e.message}", e)
                }
            }
        }

        private fun read(parcel: Parcel): SeamSchema {
            val kind = requireNotNull(parcel.readString()) { "null kind" }
            val stepCount = parcel.readInt()
            require(stepCount in 0..SeamLimits.MAX_SCHEMA_STEPS) { "step count $stepCount" }
            val steps = ArrayList<List<String>>(stepCount)
            repeat(stepCount) {
                val n = parcel.readInt()
                require(n in 0..SeamLimits.MAX_STEP_STATEMENTS) { "statement count $n" }
                val step = ArrayList<String>(n)
                repeat(n) { step += requireNotNull(parcel.readString()) { "null statement" } }
                steps += step
            }
            val purgeCount = parcel.readInt()
            require(purgeCount in 0..SeamLimits.MAX_PURGE_STATEMENTS) { "purge count $purgeCount" }
            val purge = ArrayList<String>(purgeCount)
            repeat(purgeCount) { purge += requireNotNull(parcel.readString()) { "null statement" } }
            return SeamSchema(kind, steps, purge)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<SeamSchema> = object : Parcelable.Creator<SeamSchema> {
            override fun createFromParcel(parcel: Parcel): SeamSchema = read(parcel)
            override fun newArray(size: Int): Array<SeamSchema?> = arrayOfNulls(size)
        }
    }
}
