package com.symmetricalpalmtree.soil.seam;

/**
 * The Bible's words, served by Biblesprout to Soil alone (the service is guarded by the seam
 * permission; Soil relays it to the apps as ISoilSeam.passageText). Soil binds per call.
 */
interface IBibleText {
    /**
     * The verses of the wire (the codec's form) as Markdown: a bold label line, then the verses
     * as prose with plain verse numbers, one paragraph per chapter run. Throws an
     * IllegalStateException named Seam.BIBLE_TOO_LONG for more than a chapter, and
     * Seam.BIBLE_UNREADABLE for a wire the reader cannot read or has nothing for.
     */
    String passageText(String wire);
}
