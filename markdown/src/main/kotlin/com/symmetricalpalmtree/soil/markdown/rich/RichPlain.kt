package com.symmetricalpalmtree.soil.markdown.rich

/**
 * [RichDoc] → plain text: the words with no Markdown in them. A list item keeps a plain marker
 * (`-`, its number, `[ ]` or `[x]`) and its indent, because a list with no markers is not a
 * list; a rule is a line of dashes; a raw line is as it is. A link is its words, with its
 * address after them in brackets when the address is not the words themselves (and is not an
 * address into the library, which is ids and reads as nothing). Blocks are a
 * blank line apart, list items and raw lines one under the other. Pure.
 */
object RichPlain {

    fun write(doc: RichDoc): String {
        val kept = doc.blocks.map { it.normalized() }.filter { !it.isBlank }
        val numbers = RichRules.numbering(kept.map { it.attr })
        val out = StringBuilder()
        for ((k, block) in kept.withIndex()) {
            if (k > 0) out.append(if (RichRules.tight(kept[k - 1].attr, block.attr)) "\n" else "\n\n")
            val a = block.attr
            val indent = "  ".repeat(a.depth)
            out.append(
                when (a.kind) {
                    RichKind.RULE -> "----------"
                    RichKind.RAW -> block.text
                    RichKind.BULLET -> indent + "- " + words(block)
                    RichKind.TASK -> indent + (if (a.checked) "[x] " else "[ ] ") + words(block)
                    RichKind.ORDERED -> indent + "${numbers[k]}. " + words(block)
                    else -> words(block)
                },
            )
        }
        if (kept.isNotEmpty()) out.append('\n')
        return out.toString()
    }

    private fun words(block: RichBlock): String {
        // An address into the library is ids, and reads as nothing: only its words are kept.
        val links = block.spans.filter { it.style == RichStyle.LINK && it.url.isNotEmpty() && !it.url.startsWith("soil:") }.sortedBy { it.end }
        if (links.isEmpty()) return block.text
        val out = StringBuilder()
        var at = 0
        for (link in links) {
            if (link.end < at) continue
            out.append(block.text, at, link.end)
            if (block.text.substring(link.start, link.end) != link.url) out.append(" (").append(link.url).append(')')
            at = link.end
        }
        out.append(block.text, at, block.text.length)
        return out.toString()
    }
}
