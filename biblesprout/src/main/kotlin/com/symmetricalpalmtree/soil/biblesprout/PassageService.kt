package com.symmetricalpalmtree.soil.biblesprout

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.symmetricalpalmtree.soil.bibleref.*
import com.symmetricalpalmtree.soil.seam.IBibleText
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * **A passage's words, served to Soil** ([Seam.ACTION_BIBLE_TEXT]): the verses of a wire as
 * Markdown ([PassageMarkdown]), read from the installed Bible on the binder thread. Soil binds
 * per call and relays the answer to the app that asked; the service is guarded by the seam
 * permission, so no other app reaches it. At most a chapter's worth ([Seam.MAX_PASSAGE_VERSES]):
 * a page's own ten-verse cap is the notebook's to apply before asking.
 *
 * The installer's lock serialises a copy still in progress with the reader's own; the database
 * is opened once per bind and closed with it. **Never logged**: the wire or the words.
 */
class PassageService : Service() {

    private val binder = object : IBibleText.Stub() {
        override fun passageText(wire: String): String = try {
            read(wire)
        } catch (e: IllegalStateException) {
            throw e // already one of the seam's codes
        } catch (e: Exception) {
            // An IOException (the install) or an SQLiteException (the read) would not cross the
            // binder as anything Soil can name: the documented code instead.
            Log.w(TAG, "the passage could not be read: ${e.javaClass.simpleName}")
            throw IllegalStateException(Seam.BIBLE_UNREADABLE)
        }

        private fun read(wire: String): String {
            val passages = ReferenceCodec.decode(wire) ?: throw IllegalStateException(Seam.BIBLE_UNREADABLE)
            val file = ContentInstaller(this@PassageService).ensureInstalled(ContentInstaller.BSB_ASSET, ContentInstaller.BSB_NAME)
            val db = try {
                BibleDatabase.open(file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "the Bible could not be opened: ${e.javaClass.simpleName}")
                throw IllegalStateException(Seam.BIBLE_UNREADABLE)
            }
            try {
                val verses = ArrayList<VerseRow>()
                for (passage in passages) {
                    for (range in passage.ranges) {
                        verses.addAll(db.versesForRange(range.startKey, range.endKey))
                        if (verses.size > Seam.MAX_PASSAGE_VERSES) throw IllegalStateException(Seam.BIBLE_TOO_LONG)
                    }
                }
                if (verses.isEmpty()) throw IllegalStateException(Seam.BIBLE_UNREADABLE)
                return PassageMarkdown.build(ReferenceCodec.label(passages), verses)
            } finally {
                db.close()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "PassageService"
    }
}
