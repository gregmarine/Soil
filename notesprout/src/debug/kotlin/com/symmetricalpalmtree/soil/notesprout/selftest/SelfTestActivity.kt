package com.symmetricalpalmtree.soil.notesprout.selftest

import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamSchema
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.RowCodec
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * **The check of the seam's storage calls**, in debug builds only. It makes a notebook through
 * the seam, writes and reads it, and tries what Soil must refuse. Each line is said on the screen
 * and in the log (`NotesproutSelfTest`), as PASS or FAIL.
 *
 * Everything it writes is made up here. It reads nothing a person wrote, and the notebook it
 * makes is deleted at the end unless it was asked to keep it.
 */
class SelfTestActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private val owner = Binder()
    private var running = false
    private var failures = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ink = getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val pad = (16 * resources.displayMetrics.density).toInt()
        out = TextView(this).apply {
            textSize = 14f
            setTextColor(ink)
            setPadding(pad, pad, pad, pad)
        }
        fun button(label: String, keep: Boolean, kill: Boolean = false) = AppCompatButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { run(keep, kill) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(button("Run", keep = false))
            addView(button("Run and keep", keep = true))
            addView(button("Die mid-write", keep = true, kill = true))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(com.symmetricalpalmtree.soil.paper.R.color.paperWhite))
                addView(bar)
                addView(out, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    private fun run(keep: Boolean, kill: Boolean) {
        if (running) return
        running = true
        failures = 0
        out.text = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { if (kill) dieMidWrite() else checks(keep) } }
            result.onFailure { say("FAIL  stopped: ${it.javaClass.simpleName}: ${it.message}"); failures++ }
            say(if (failures == 0) "DONE  every check passed" else "DONE  $failures failed")
            running = false
        }
    }

    private suspend fun checks(keep: Boolean) {
        val seam = (application as NotesproutApp).soil.seam()

        val hello = seam.hello()
        check("hello: seam ${hello.seamVersion}, unlocked ${hello.libraryUnlocked}, open ${hello.libraryOpen}") { hello.libraryOpen }

        val name = "Self-test ${System.currentTimeMillis() % 100_000}"
        val item = seam.createItem(name, NotebookSchema.SCHEMA)
        check("made a notebook") { item.kind == "notebook" && item.name == name }
        check("it is listed") { seam.listItems("notebook").any { it.id == item.id } }

        var session = timed("opened") { seam.openItem(item.id, NotebookSchema.SCHEMA, owner) }
        var rows = SeamRowStore(session)

        // One page of 3,000 strokes, in one batch, and back.
        val page = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val strokes = List(3_000) { i -> insert(UUID.randomUUID().toString(), page, "stroke", i, now, blob = ByteArray(200) { i.toByte() }) }
        timed("wrote a page and 3,000 strokes in one batch") {
            rows.exec(listOf(insert(page, item.id, "page", 0, now)) + strokes)
        }
        val back = timed("read them back") {
            rows.query(Statement("SELECT id, blob FROM notebook WHERE parentId = ? AND type = 'stroke' ORDER BY \"order\"", page))
        }
        check("3,000 strokes came back in order") {
            back.size == 3_000 && back[0].blob("blob")[0] == 0.toByte() && back[2_999].blob("blob")[0] == 2_999.toByte()
        }
        timed("wrote one stroke") {
            rows.exec(listOf(insert(UUID.randomUUID().toString(), page, "stroke", 3_000, now, blob = ByteArray(200))))
        }

        // A large value, whole.
        val large = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val largeId = UUID.randomUUID().toString()
        timed("wrote a 5 MiB value") { rows.exec(listOf(insert(largeId, item.id, "template", 0, now, blob = large))) }
        val largeBack = timed("read it back") { rows.query(Statement("SELECT blob FROM notebook WHERE id = ?", largeId))[0].blob("blob") }
        check("the 5 MiB value is what was written") { largeBack.contentEquals(large) }

        // What the app's own check refuses, before anything is sent.
        refused("a value over the cap, stopped here") {
            rows.exec(listOf(insert("x", item.id, "template", 1, now, blob = ByteArray(SeamLimits.MAX_VALUE_BYTES + 1))))
        }
        refused("PRAGMA, stopped here") { rows.exec(listOf(Statement("PRAGMA user_version = 9"))) }

        // What Soil refuses, sent past the app's own check.
        refused("PRAGMA, refused by Soil") { raw(session, "PRAGMA user_version = 9") }
        refused("ATTACH, refused by Soil") { raw(session, "ATTACH DATABASE 'x' AS y") }
        refused("a write to soil_meta, refused by Soil") { raw(session, "DELETE FROM soil_meta") }
        refused("a read of soil_meta, refused by Soil") { rawQuery(session, "SELECT * FROM soil_meta") }
        refused("a read of sqlite_master, refused by Soil") { rawQuery(session, "SELECT * FROM sqlite_master") }
        refused("pragma_user_version, refused by Soil") { rawQuery(session, "SELECT * FROM pragma_user_version") }
        refused("sqlcipher_export, refused by Soil") { rawQuery(session, "SELECT sqlcipher_export('x')") }
        refused("two statements in one, refused by Soil") { raw(session, "DELETE FROM notebook; DELETE FROM notebook") }
        refused("a write sent as a query, refused by Soil") { rawQuery(session, "DELETE FROM notebook") }

        // A batch is one transaction: the second statement fails, so the first does not land.
        val orphan = UUID.randomUUID().toString()
        refused("a batch that fails half way") {
            rows.exec(listOf(insert(orphan, page, "stroke", 9_000, now), Statement("INSERT INTO nothing_here (id) VALUES (?)", "x")))
        }
        check("nothing of that batch landed") { count(rows, "id = '$orphan'") == 0L }

        // A schema Soil must not take.
        refused("a notebook opened as a sketchbook") {
            seam.openItem(item.id, SeamSchema("sketchbook", NotebookSchema.SCHEMA.steps), owner)
        }

        // Park gives up the file and keeps the session.
        session.park()
        refused("a read while parked") { rows.query(Statement("SELECT 1 AS n")) }
        session.resume()
        check("after resume the strokes are there") { count(rows, "type = 'stroke'") == 3_001L }

        // Soft-delete a page, close for good, and the purge takes the page and what is under it.
        rows.exec(listOf(Statement("UPDATE notebook SET deletedAt = ? WHERE id = ?", now, page)))
        session.close(true)
        refused("a call on a closed session") { rows.query(Statement("SELECT 1 AS n")) }
        session = timed("opened again") { seam.openItem(item.id, NotebookSchema.SCHEMA, owner) }
        rows = SeamRowStore(session)
        check("the deleted page and its strokes are purged") { count(rows, "type IN ('page', 'stroke')") == 0L }
        check("the template is spared") { count(rows, "type = 'template'") == 1L }

        seam.renameItem(item.id, "  $name\n renamed ")
        check("renamed, on one line") { seam.item(item.id)?.name == "$name renamed" }

        refused("a delete while it is open") { seam.deleteItem(item.id) }
        session.close(true)
        if (keep) {
            say("KEPT  \"$name renamed\" is in the library")
        } else {
            seam.deleteItem(item.id)
            check("deleted: no longer listed") { seam.item(item.id) == null }
        }
    }

    /** Makes a notebook, starts a long write, and kills this app in the middle of it. */
    private suspend fun dieMidWrite() {
        val seam: ISoilSeam = (application as NotesproutApp).soil.seam()
        val item = seam.createItem("Self-test died mid-write", NotebookSchema.SCHEMA)
        val session = seam.openItem(item.id, NotebookSchema.SCHEMA, owner)
        val rows = SeamRowStore(session)
        val now = System.currentTimeMillis()
        rows.exec(listOf(insert("kept-page", item.id, "page", 0, now)))
        say("KEPT  a notebook with one page; now dying mid-write")
        Thread {
            Thread.sleep(40)
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
        val many = List(SeamLimits.MAX_BATCH_STATEMENTS) { i ->
            insert(UUID.randomUUID().toString(), "kept-page", "stroke", i, now, blob = ByteArray(2_000))
        }
        while (true) rows.exec(many)
    }

    private fun insert(id: String, parent: String, type: String, order: Int, now: Long, blob: ByteArray? = null) = Statement(
        "INSERT INTO notebook (id, parentId, type, \"order\", createdAt, updatedAt, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, parent, type, order, now, now, blob,
    )

    private fun count(rows: SeamRowStore, where: String): Long =
        rows.query(Statement("SELECT count(*) AS n FROM notebook WHERE $where"))[0].long("n")

    /** A write sent to Soil without the app's own check. */
    private fun raw(session: ISeamItem, sql: String) {
        val sent = SeamShared.write(RowCodec.encodeStatements(listOf(Statement(sql, emptyList<Cell>()))))
        try { session.exec(sent) } finally { sent.memory.close() }
    }

    /** A query sent to Soil without the app's own check. */
    private fun rawQuery(session: ISeamItem, sql: String) {
        val sent = SeamShared.write(RowCodec.encodeStatements(listOf(Statement(sql, emptyList<Cell>()))))
        try { SeamShared.readAndClose(session.query(sent)) } finally { sent.memory.close() }
    }

    private suspend fun check(what: String, holds: () -> Boolean) {
        val ok = runCatching(holds).getOrDefault(false)
        if (!ok) failures++
        say("${if (ok) "PASS" else "FAIL"}  $what")
    }

    /** Passes when [block] throws. */
    private suspend fun refused(what: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        if (thrown == null) failures++
        say(if (thrown != null) "PASS  $what: ${thrown.javaClass.simpleName}" else "FAIL  $what was not refused")
    }

    private suspend fun <T> timed(what: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        val result = block()
        say("      $what in ${SystemClock.elapsedRealtime() - t0} ms")
        return result
    }

    private suspend fun say(line: String) {
        Log.i(TAG, line)
        withContext(Dispatchers.Main) { out.append(line + "\n") }
    }

    private companion object { const val TAG = "NotesproutSelfTest" }
}
