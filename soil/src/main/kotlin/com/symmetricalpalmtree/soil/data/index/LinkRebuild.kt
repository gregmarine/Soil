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
 */
object LinkRebuild {

    private const val TAG = "LinkRebuild"

    class Outcome(val items: Int, val skipped: Int)

    fun rebuild(context: Context): Outcome {
        val app = context.applicationContext
        val index = IndexStore()
        var done = 0
        var skipped = 0
        for (item in index.aliveItems()) {
            try {
                if (!ItemSessions.remirror(item.id)) {
                    index.replaceLinks(item.id, LinkRows.indexable(ItemFiles.readLinkMirror(app, item.id, item.kind)))
                }
                done++
            } catch (t: Throwable) {
                Slog.d(TAG) { "an item's mirror was not read: ${t.javaClass.simpleName}" }
                skipped++
            }
        }
        Slog.d(TAG) { "rebuilt: $done item(s), $skipped skipped" }
        return Outcome(done, skipped)
    }
}
