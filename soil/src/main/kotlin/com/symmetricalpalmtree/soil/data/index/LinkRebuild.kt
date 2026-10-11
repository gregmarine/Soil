package com.symmetricalpalmtree.soil.data.index

import android.content.Context
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * **The link index, rebuilt from every file.** The index is kept in step at each write; this is
 * the way back when it is not: after a restore, or on request from Settings. Every alive item's
 * mirror is read, through the session that holds it open or from the file itself, and the
 * index's rows for that item replaced. An item whose file will not open is skipped and counted;
 * nothing else is touched. Blocking; IO only.
 *
 * **Stoppable** (cleanup, 2026-10-10): [stop] is asked before each item, and a yes ends the walk
 * there. An item's rows are replaced in one statement, so a stop leaves nothing half done: the
 * items walked are fresh, the rest as they were, and the index as consistent as before. Nothing
 * to clean up. [progress] says how far, for a line on the screen.
 */
object LinkRebuild {

    private const val TAG = "LinkRebuild"

    class Outcome(val items: Int, val skipped: Int, val total: Int = items + skipped, val stopped: Boolean = false)

    fun rebuild(context: Context, stop: () -> Boolean = { false }, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        val app = context.applicationContext
        val index = IndexStore()
        var done = 0
        var skipped = 0
        val items = index.aliveItems()
        progress(0, items.size)
        for (item in items) {
            if (stop()) {
                Slog.d(TAG) { "stopped: $done item(s) rebuilt, $skipped skipped, ${items.size - done - skipped} left as they were" }
                return Outcome(done, skipped, items.size, stopped = true)
            }
            try {
                if (!ItemSessions.remirror(item.id)) {
                    index.replaceLinks(item.id, LinkRows.indexable(ItemFiles.readLinkMirror(app, item.id, item.kind)))
                }
                done++
            } catch (t: Throwable) {
                Slog.d(TAG) { "an item's mirror was not read: ${t.javaClass.simpleName}" }
                skipped++
            }
            progress(done + skipped, items.size)
        }
        Slog.d(TAG) { "rebuilt: $done item(s), $skipped skipped" }
        return Outcome(done, skipped, items.size)
    }
}
