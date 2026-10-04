package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * Every statement the tag index sends, as a pure builder, so the shapes are JVM-tested without
 * a database. Two of them do more than they look like, because **the transaction is the lock**
 * between the two writers (a screen on IO, the seam on a Binder thread):
 *
 *  - [insertTag] carries both caps inside the insert, so the count and the insert are one
 *    statement in one transaction, and a refused attachment never leaves an orphan tag;
 *  - [insertAssignment] resolves the tag id **by identity, inside the statement**, so a
 *    concurrent creator of the same tag cannot leave this assignment pointing at a row that was
 *    never inserted.
 *
 * An item tag's `pageId` is `""` everywhere here: the builders take the stored form.
 */
object TagSql {

    // ── Reads ──────

    /** Every tag in browse order. `(identityKey, display)` is unique, so the order is stable. */
    fun selectTags(): Statement = Statement("SELECT id, display FROM tag ORDER BY identityKey, display")

    /** One read answering "does this tag exist, and is it already on this target". */
    fun selectTagByIdentity(identityKey: String, itemId: String, pageId: String): Statement = Statement(
        "SELECT t.id, t.display, EXISTS(SELECT 1 FROM tag_assignment a WHERE a.tagId = t.id AND a.itemId = ? AND a.pageId = ?) AS attached FROM tag t WHERE t.identityKey = ?",
        itemId, pageId, identityKey,
    )

    /** The blast radius of deleting a tag, by kind. `SUM` over no rows is NULL, read as 0. */
    fun selectUsage(tagId: String): Statement = Statement(
        "SELECT SUM(pageId = '') AS items, SUM(pageId <> '') AS pages FROM tag_assignment WHERE tagId = ?",
        tagId,
    )

    /** The screen's read: every assignment on one item, its own and its pages'. */
    fun selectAssignmentsOfItem(itemId: String): Statement = Statement(
        "SELECT tagId, itemId, pageId FROM tag_assignment WHERE itemId = ? ORDER BY tagId, pageId",
        itemId,
    )

    /** The search's read: the assignments of the tags that matched. One `?` per id. */
    fun selectAssignmentsOf(tagIds: List<String>): Statement {
        require(tagIds.isNotEmpty()) { "no tag ids" }
        val marks = tagIds.joinToString(", ") { "?" }
        return Statement(
            "SELECT tagId, itemId, pageId FROM tag_assignment WHERE tagId IN ($marks) ORDER BY tagId, itemId, pageId",
            tagIds.map { Cell.Text(it) },
        )
    }

    // ── Writes ──────

    fun insertTag(id: String, display: String, identityKey: String, now: Long, maxTags: Int, maxAssignments: Int): Statement = Statement(
        "INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt) SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM tag_assignment) < ?",
        id, display, identityKey, now, maxTags.toLong(), maxAssignments.toLong(),
    )

    fun insertAssignment(identityKey: String, itemId: String, pageId: String, now: Long, maxAssignments: Int): Statement = Statement(
        "INSERT OR IGNORE INTO tag_assignment (tagId, itemId, pageId, createdAt) SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM tag_assignment) < ?",
        itemId, pageId, now, identityKey, maxAssignments.toLong(),
    )

    /** Detach one tag from one target. The tag itself stays. */
    fun deleteAssignment(tagId: String, itemId: String, pageId: String): Statement =
        Statement("DELETE FROM tag_assignment WHERE tagId = ? AND itemId = ? AND pageId = ?", tagId, itemId, pageId)

    /** Delete a tag and every assignment of it: two statements, one transaction. */
    fun deleteTag(id: String): List<Statement> = listOf(
        Statement("DELETE FROM tag_assignment WHERE tagId = ?", id),
        Statement("DELETE FROM tag WHERE id = ?", id),
    )
}
