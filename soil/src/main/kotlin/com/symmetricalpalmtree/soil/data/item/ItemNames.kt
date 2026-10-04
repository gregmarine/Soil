package com.symmetricalpalmtree.soil.data.item

import com.symmetricalpalmtree.soil.seam.SeamLimits

/** What an item may be called. Pure. */
object ItemNames {

    /**
     * [name] as it is kept: on one line, with no space at either end. A name is what a person
     * typed and is never a path, so nothing in it is refused for what it looks like.
     *
     * @throws IllegalArgumentException when nothing is left, or it is longer than a name may be
     */
    fun clean(name: String): String {
        val one = name.replace(Regex("\\s+"), " ").trim()
        require(one.isNotEmpty()) { "an item needs a name" }
        require(one.length <= SeamLimits.MAX_NAME_CHARS) { "a name has at most ${SeamLimits.MAX_NAME_CHARS} characters" }
        return one
    }
}
