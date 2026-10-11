package com.symmetricalpalmtree.soil.markdown.rich

import org.junit.Assert.assertEquals
import org.junit.Test

class RichPlainTest {

    private fun plain(markdown: String) = RichPlain.write(RichParse.parse(markdown).doc)

    @Test
    fun `the words come out with no markup in them`() {
        assertEquals("Title\n\nSome bold and italic and code words.\n\nA quote.\n", plain("# Title\n\nSome **bold** and _italic_ and `code` words.\n\n> A quote.\n"))
    }

    @Test
    fun `lists keep plain markers, their numbers and their indent`() {
        assertEquals("- a\n  - b\n[ ] c\n[x] d\n\n3. e\n4. f\n", plain("- a\n  - b\n- [ ] c\n- [x] d\n3. e\n9. f\n"))
    }

    @Test
    fun `a rule is a line and a raw line is as it is`() {
        assertEquals("a\n\n----------\n\n| x | y |\n|---|---|\n", plain("a\n\n---\n\n| x | y |\n|---|---|\n"))
    }

    @Test
    fun `a link is its words with its address after them, unless the address is the words`() {
        assertEquals("See the site (http://x.org) and http://y.org now.\n", plain("See [the site](http://x.org) and [http://y.org](http://y.org) now.\n"))
    }

    @Test
    fun `a link into the library or the Bible is its words alone`() {
        assertEquals("See the notes and John 3:16 now.\n", plain("See [the notes](soil:855fe3bc) and [John 3:16](bible:JHN:3:16-3:16) now.\n"))
    }

    @Test
    fun `escaped markers come out as the characters they are`() {
        assertEquals("2 * 3 * 4 and # not a heading\n", plain("""2 \* 3 * 4 and # not a heading""" + "\n"))
    }

    @Test
    fun `nothing gives nothing`() {
        assertEquals("", plain(""))
    }
}
