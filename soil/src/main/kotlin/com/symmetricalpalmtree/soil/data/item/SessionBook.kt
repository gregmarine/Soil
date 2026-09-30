package com.symmetricalpalmtree.soil.data.item

/**
 * **Who holds which item, and whether its file has to be open** — the pure half of
 * [ItemSessions], so every case is JVM-tested.
 *
 * A *holder* is one session: one app's hold on one item. An item's file is open exactly while at
 * least one of its holders is not parked. A parked holder keeps its session and gives up the
 * file, which is what lets a notebook left in the background stand aside for a passphrase change.
 *
 * Each call answers what has to be done to the file, and the book already reflects it. When an
 * open fails the caller takes the holder out again with [leave].
 */
class SessionBook {

    enum class Act {
        NONE,
        /** Open the file. */
        OPEN,
        /** Fold what is written into the file and close it. Nothing is purged. */
        RELEASE,
        /** Close the file for good: purge what is soft-deleted, give the space back, close. */
        CLOSE_TIDY,
        /** Close the file for good without purging. */
        CLOSE,
        /** The file is already shut and is to be tidied: open it, purge, close. */
        TIDY_COLD,
    }

    private class Entry {
        /** Holder → parked. */
        val holders = LinkedHashMap<Long, Boolean>()
        var open = false
        /** Set when a holder asked for a tidy close while others still held the item. */
        var tidyOwed = false
    }

    private val items = HashMap<String, Entry>()

    fun join(itemId: String, holder: Long): Act {
        val entry = items.getOrPut(itemId) { Entry() }
        entry.holders[holder] = false
        return opened(entry)
    }

    fun park(itemId: String, holder: Long): Act {
        val entry = held(itemId, holder) ?: return Act.NONE
        entry.holders[holder] = true
        return releasedIfIdle(entry)
    }

    fun resume(itemId: String, holder: Long): Act {
        val entry = held(itemId, holder) ?: return Act.NONE
        entry.holders[holder] = false
        return opened(entry)
    }

    /**
     * The holder is gone. While others hold the item nothing is purged: what one app soft-deleted
     * another may be about to restore. The purge is owed, and paid when the last one leaves.
     */
    fun leave(itemId: String, holder: Long, tidy: Boolean): Act {
        val entry = held(itemId, holder) ?: return Act.NONE
        entry.holders.remove(holder)
        if (tidy) entry.tidyOwed = true
        if (entry.holders.isNotEmpty()) return releasedIfIdle(entry)
        items.remove(itemId)
        return when {
            entry.open && entry.tidyOwed -> Act.CLOSE_TIDY
            entry.open -> Act.CLOSE
            entry.tidyOwed -> Act.TIDY_COLD
            else -> Act.NONE
        }
    }

    fun holds(itemId: String, holder: Long): Boolean = held(itemId, holder) != null

    fun isParked(itemId: String, holder: Long): Boolean = items[itemId]?.holders?.get(holder) == true

    /** Whether any session holds [itemId], parked or not. */
    fun isHeld(itemId: String): Boolean = items[itemId]?.holders?.isNotEmpty() == true

    /** The items whose files are open, in the order they were first held. */
    fun openItems(): List<String> = items.filterValues { it.open }.keys.toList()

    /** Every holder of every item. */
    fun holders(): List<Pair<String, Long>> = items.flatMap { (id, e) -> e.holders.keys.map { id to it } }

    private fun held(itemId: String, holder: Long): Entry? =
        items[itemId]?.takeIf { holder in it.holders }

    private fun opened(entry: Entry): Act {
        if (entry.open) return Act.NONE
        entry.open = true
        return Act.OPEN
    }

    private fun releasedIfIdle(entry: Entry): Act {
        if (!entry.open || entry.holders.values.any { !it }) return Act.NONE
        entry.open = false
        return Act.RELEASE
    }
}
