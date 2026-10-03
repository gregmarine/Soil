package com.symmetricalpalmtree.soil.notesprout.export

import com.symmetricalpalmtree.soil.markdown.HeadingPrefix
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.objects.Heading

/** A page's title: its topmost heading, leftmost on a tie, its prefix stripped; null without one. */
object PageLabels {

    fun titleOf(headings: List<Heading>): String? =
        headings.minWithOrNull(compareBy({ it.y }, { it.x }))?.let { HeadingPrefix.stripHeadingPrefix(it.text).trim().ifEmpty { null } }

    fun titleOf(content: PageContent): String? = titleOf(content.headings + content.links.flatMap { it.headings })
}
