package com.symmetricalpalmtree.soil.seam

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.data.item.ItemNames
import com.symmetricalpalmtree.soil.data.item.ItemRefused
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.util.UUID

/**
 * **Soil's end of the seam** — the service the Sprout apps bind to.
 *
 * It is exported, because other apps must reach it, and guarded twice: the manifest gives it
 * Soil's signature permission, so Android refuses the bind to any app not signed with Soil's key,
 * and every call runs [SeamCallerCheck.enforce] before it does anything else.
 *
 * It never prompts and never shows anything. While the library is not open every storage call is
 * refused, and the person opens the library in Soil.
 */
class SoilSeamService : Service() {

    private val seam = object : ISoilSeam.Stub() {

        override fun hello(): SeamHello {
            SeamCallerCheck.enforce(this@SoilSeamService)
            return SeamHello(
                seamVersion = Seam.VERSION,
                libraryUnlocked = SoilIndex.isReady(),
                libraryOpen = libraryOpen(),
            )
        }

        override fun attachClient(client: ISeamClient) {
            SeamCallerCheck.enforce(this@SoilSeamService)
            SeamClients.attach(client)
        }

        override fun detachClient(client: ISeamClient) {
            SeamCallerCheck.enforce(this@SoilSeamService)
            SeamClients.detach(client)
        }

        override fun createItem(name: String, schema: SeamSchema): SeamItem = answered {
            val clean = ItemNames.clean(name)
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            // The file first. A file with no row is never seen; a row with no file is an item
            // that cannot be opened.
            ItemFiles.create(this@SoilSeamService, id, clean, now, schema)
            IndexStore().insert(id, schema.kind, clean, now)
            ItemSessions.changed()
            SeamItem(id = id, kind = schema.kind, name = clean, createdAt = now, updatedAt = now)
        }

        override fun listItems(kind: String): List<SeamItem> = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            IndexStore().aliveItems(kind).map(::seamItem)
        }

        override fun recentItems(kind: String, limit: Int): List<SeamItem> = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            require(limit in 1..200) { "a limit is 1..200" }
            IndexStore().recentItems(kind, limit).map(::seamItem)
        }

        override fun item(itemId: String): SeamItem? = answered {
            IndexStore().aliveItem(itemId)?.let(::seamItem)
        }

        override fun renameItem(itemId: String, name: String) = answered {
            val clean = ItemNames.clean(name)
            check(IndexStore().rename(itemId, clean, System.currentTimeMillis())) { NO_SUCH_ITEM }
            ItemSessions.rename(itemId, clean)
            ItemSessions.changed()
        }

        override fun setPageCount(itemId: String, count: Int) = answered {
            require(count >= 0) { "a page count is not negative" }
            IndexStore().setPageCount(itemId, count)
            ItemSessions.changed()
        }

        override fun deleteItem(itemId: String) = answered {
            check(!ItemSessions.isHeld(itemId)) { "the item is open" }
            check(IndexStore().softDelete(itemId, System.currentTimeMillis())) { NO_SUCH_ITEM }
            ItemSessions.changed()
        }

        override fun openItem(itemId: String, schema: SeamSchema, owner: IBinder): ISeamItem = answered {
            val item = IndexStore().aliveItem(itemId) ?: throw IllegalStateException(NO_SUCH_ITEM)
            // An app opens items of its own kind and no other.
            check(item.kind == schema.kind) { "the item is of another kind" }
            runCatching { IndexStore().markOpened(itemId, System.currentTimeMillis()) }
            val holder = try {
                ItemSessions.join(this@SoilSeamService, itemId, schema)
            } catch (e: ItemRefused) {
                throw IllegalStateException("the file is not this item: ${e.verdict.name}")
            }
            val session = SeamItemSession(
                context = this@SoilSeamService,
                itemId = itemId,
                holder = holder,
                ownerUid = Binder.getCallingUid(),
                owner = owner,
                gate = ::requireOpen,
            )
            try {
                owner.linkToDeath(session, 0)
            } catch (_: android.os.RemoteException) {
                // It died between the call and here.
                ItemSessions.leave(this@SoilSeamService, itemId, holder, tidy = false)
                throw IllegalStateException(SeamLimits.SESSION_ENDED)
            }
            session
        }
    }

    private fun seamItem(item: Item) = SeamItem(
        id = item.id, kind = item.kind, name = item.name,
        createdAt = item.createdAt, updatedAt = item.updatedAt, pageCount = item.pageCount,
    )

    /** Unlocked, the recovery key saved, and no passphrase change standing unfinished. */
    private fun libraryOpen(): Boolean =
        SoilIndex.isReady() && Library.status.value.route == KeyGate.Route.OPEN

    private fun requireOpen() {
        check(libraryOpen()) { SeamLimits.LIBRARY_NOT_OPEN }
    }

    /**
     * Guard, gate, run, and let nothing cross that Binder cannot carry. A failure of any other
     * kind becomes an `IllegalStateException` that names its class and nothing else: a message
     * can hold a path, or what a person wrote.
     */
    private inline fun <T> answered(block: () -> T): T {
        SeamCallerCheck.enforce(this)
        requireOpen()
        return try {
            block()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (t: Throwable) {
            Slog.d(TAG) { "a call failed: ${t.javaClass.simpleName}" }
            throw IllegalStateException("the call failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Slog.d(TAG) { "bound" }
        return seam
    }

    private companion object {
        const val TAG = "SoilSeam"
        const val NO_SUCH_ITEM = "there is no such item"
    }
}
