package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow

// The clipboard's payload is shared with what else writes and reads the one clipboard (the
// Scratch Pad, a document's paste of ink), so its shape lives in `:seam-kit`. These are its
// names here, and the two conversions that are a notebook's own.

typealias ClipRow = com.symmetricalpalmtree.soil.seamkit.clip.ClipRow
typealias ClipEnvelope = com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope

/** A notebook row as the clipboard carries it. */
fun clipRowOf(r: NotebookRow): ClipRow = ClipRow(
    id = r.id, parentId = r.parentId, type = r.type, order = r.order,
    text = r.text, refId = r.refId, x = r.x, y = r.y, width = r.width, height = r.height,
    color = r.color, strokeWidth = r.strokeWidth, style = r.style, flags = r.flags, blob = ClipRow.encodeBlob(r.blob),
)

/** The row as a paste writes it: fresh identity and lineage, the content verbatim. */
fun ClipRow.toRow(id: String, parentId: String, order: Int): NotebookRow = NotebookRow(
    id = id, parentId = parentId, type = type, order = order,
    text = text, refId = refId, x = x, y = y, width = width, height = height,
    color = color, strokeWidth = strokeWidth, style = style, flags = flags, blob = blobBytes(),
)
