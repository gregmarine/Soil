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
import com.symmetricalpalmtree.soil.data.index.TemplateStore
import com.symmetricalpalmtree.soil.data.index.ClipStore
import com.symmetricalpalmtree.soil.data.index.TagStore
import com.symmetricalpalmtree.soil.templates.TextStaging
import com.symmetricalpalmtree.soil.ext.ExtContract
import com.symmetricalpalmtree.soil.ext.InkStroke
import com.symmetricalpalmtree.soil.ext.RecognizerBinder
import com.symmetricalpalmtree.soil.ext.RecognizerCallFailed
import com.symmetricalpalmtree.soil.ext.Recognizers
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.templates.TemplatePrefs
import com.symmetricalpalmtree.soil.templates.TemplateStaging
import com.symmetricalpalmtree.soil.paper.templates.TemplateImport
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.data.item.ItemNames
import com.symmetricalpalmtree.soil.data.item.ItemRefused
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.util.UUID
import com.symmetricalpalmtree.soil.shell.SoilBarService

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

        override fun makeItemFromFile(besideItemId: String, name: String, fileExtension: String, file: SeamBytes): SeamItem = answered {
            val bytes = SeamShared.readAndClose(file)
            require(bytes.size <= SeamLimits.MAX_VALUE_BYTES) { SeamLimits.VALUE_TOO_LARGE }
            require(fileExtension.isNotEmpty() && fileExtension.length <= 12 && fileExtension.all { it in 'a'..'z' || it in '0'..'9' }) { "not a file extension" }
            val beside = IndexStore().aliveItem(besideItemId) ?: throw IllegalStateException(NO_SUCH_ITEM)
            val clean = ItemNames.clean(name)
            val context = this@SoilSeamService
            kotlinx.coroutines.runBlocking {
                val taker = com.symmetricalpalmtree.soil.importing.AppImports.takerOf(context, fileExtension) ?: throw IllegalStateException(Seam.MAKE_NO_APP)
                // Beside a notebook of the same name is where it belongs; a second of its own kind is a copy.
                val taken = com.symmetricalpalmtree.soil.data.index.LibraryStore().items(beside.parentId).filter { it.kind == taker.kind }.mapTo(HashSet()) { it.name }
                val landed = if (clean in taken) com.symmetricalpalmtree.soil.importing.ImportNames.keepBothName(clean) { it in taken } else clean
                val staged = java.io.File(java.io.File(cacheDir, "make").apply { mkdirs() }, UUID.randomUUID().toString())
                try {
                    staged.writeBytes(bytes)
                    val id = com.symmetricalpalmtree.soil.importing.AppImports.make(context, taker.kind, taker.renderer, landed, beside.parentId, fileExtension, staged)
                    ItemSessions.changed()
                    val now = System.currentTimeMillis()
                    SeamItem(id = id, kind = taker.kind, name = landed, createdAt = now, updatedAt = now)
                } finally {
                    staged.delete()
                }
            }
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
            // The library's own delete: the row with its cover, pin, tags and page order, then
            // the file, its sidecars and its derived key — what the library's Delete does.
            check(com.symmetricalpalmtree.soil.data.index.LibraryStore().deleteItem(itemId, System.currentTimeMillis())) { NO_SUCH_ITEM }
            com.symmetricalpalmtree.soil.library.LibraryFiles.deleteItemFile(this@SoilSeamService, itemId)
            ItemSessions.changed()
        }

        override fun setCover(itemId: String, cover: SeamBytes) = answered {
            val bytes = SeamShared.readAndClose(cover)
            require(bytes.isNotEmpty() && bytes.size <= MAX_COVER_BYTES) { "a cover is at most $MAX_COVER_BYTES bytes" }
            com.symmetricalpalmtree.soil.data.index.LibraryStore().setCover(itemId, bytes)
            ItemSessions.changed()
        }

        override fun backlinks(itemId: String): List<SeamBacklink> = answered {
            IndexStore().backlinks(itemId).map {
                SeamBacklink(
                    linkId = it.linkId, sourceItemId = it.sourceItemId, sourceKind = it.sourceKind,
                    sourceName = it.sourceName, sourcePageId = it.sourcePageId, targetPageId = it.targetPageId,
                )
            }
        }

        override fun bibleBacklinks(startKey: Int, endKey: Int): List<SeamBibleBacklink> = answered {
            require(startKey in 1..66_999_999 && endKey >= startKey) { "not a verse span" }
            IndexStore().bibleBacklinks(startKey, endKey).map {
                SeamBibleBacklink(
                    linkId = it.linkId, sourceItemId = it.sourceItemId, sourceKind = it.sourceKind, sourceName = it.sourceName,
                    sourcePageId = it.sourcePageId, pageNumber = it.pageNumber, wire = it.wire, startKey = it.startKey, endKey = it.endKey,
                )
            }
        }

        override fun calBacklinks(fromDate: String, toDate: String): List<SeamCalBacklink> = answered {
            require(CalAddress.isDate(fromDate) && CalAddress.isDate(toDate) && fromDate <= toDate) { "not a day range" }
            IndexStore().calBacklinks(fromDate, toDate).map {
                SeamCalBacklink(
                    linkId = it.linkId, sourceItemId = it.sourceItemId, sourceKind = it.sourceKind, sourceName = it.sourceName,
                    sourcePageId = it.sourcePageId, pageNumber = it.pageNumber, date = it.date,
                )
            }
        }

        override fun passageText(wire: String): String = answered {
            require(BibleAddress.isWire(wire)) { "not a wire" }
            com.symmetricalpalmtree.soil.library.BibleTextClient.passageText(this@SoilSeamService, wire)
        }

        override fun template(templateId: String): SeamTemplate? = answered {
            TemplateStore().template(templateId)?.let { SeamTemplate(it.id, it.name, it.fit) }
        }

        override fun templateImage(templateId: String): SeamBytes = answered {
            val bytes = TemplateStore().image(templateId) ?: throw IllegalStateException(NO_SUCH_TEMPLATE)
            SeamShared.write(bytes).also { sent.set(it) }
        }

        override fun templateUsed(cardId: String) = answered {
            require(cardId.isNotBlank() && cardId.length <= 64) { "not a card id" }
            TemplatePrefs(this@SoilSeamService).recordUse(cardId)
        }

        override fun stageTemplate(image: SeamBytes): String = answered {
            val bytes = SeamShared.readAndClose(image)
            require(bytes.isNotEmpty() && !TemplateImport.overCap(bytes.size)) { "a template is at most ${TemplateImport.MAX_BLOB_BYTES} bytes" }
            TemplateStaging.stage(bytes)
        }

        // ── The clipboard ──────

        override fun clipHeader(kind: String): SeamClip? = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            ClipStore().header(kind)
        }

        override fun putClip(kind: String, header: SeamClip, payload: SeamBytes) = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            val bytes = SeamShared.readAndClose(payload)
            require(bytes.isNotEmpty() && bytes.size <= SeamLimits.MAX_VALUE_BYTES) { SeamLimits.VALUE_TOO_LARGE }
            ClipStore().put(kind, header, bytes)
        }

        override fun clip(kind: String): SeamBytes? = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            ClipStore().bytes(kind)?.let { SeamShared.write(it).also { region -> sent.set(region) } }
        }

        override fun clearClip(kind: String) = answered {
            require(SeamSchema.isValidKind(kind)) { "not a kind" }
            ClipStore().clear(kind)
        }

        // ── Pages and tags ──────

        override fun setPages(itemId: String, pageIds: List<String>) = answered {
            require(pageIds.size <= MAX_PAGES) { "at most $MAX_PAGES pages" }
            require(pageIds.all { TagRules.isId(it) }) { "not a page id" }
            check(IndexStore().aliveItem(itemId) != null) { NO_SUCH_ITEM }
            IndexStore().setPages(itemId, pageIds)
            ItemSessions.changed()
        }

        override fun assignTag(itemId: String, pageId: String, text: String): String = answered {
            require(TagRules.isValid(text)) { "not a tag" }
            check(IndexStore().aliveItem(itemId) != null) { NO_SUCH_ITEM }
            TagStore().assign(text, itemId, pageId.ifEmpty { null }).display
        }

        override fun stageText(text: String): String = answered {
            require(text.length <= SeamLimits.MAX_STAGED_TEXT_CHARS) { "the text is too long" }
            TextStaging.stage(text)
        }

        // ── Ink between a notebook and the Scratch Pad ──────

        override fun sendInkToPad(ink: SeamBytes, placement: Int) = answered {
            val bytes = SeamShared.readAndClose(ink)
            require(bytes.isNotEmpty() && bytes.size <= SeamLimits.MAX_VALUE_BYTES) { SeamLimits.VALUE_TOO_LARGE }
            require(placement == Seam.PAD_PLACEMENT_NEW_PAGE || placement == Seam.PAD_PLACEMENT_CURRENT_PAGE) { "not a placement" }
            com.symmetricalpalmtree.soil.pad.PadTransfer.parkIncoming(bytes, placement)
        }

        // ── Recognition, relayed to the chosen recogniser ──────

        override fun recognizer(): SeamRecognizer? = answered {
            Recognizers.chosen(this@SoilSeamService)?.let { SeamRecognizer(it.recognizer.label, it.languageTag) }
        }

        override fun recognizerStatus(): Int = answered {
            val choice = Recognizers.chosen(this@SoilSeamService) ?: throw IllegalStateException(SeamLimits.NO_RECOGNIZER)
            relay { RecognizerBinder.call(this@SoilSeamService, choice.recognizer.component, STATUS_TIMEOUT_MS) { ExtContract.status(it.status(choice.languageTag)) } }
        }

        override fun prepareRecognizer() = answered {
            val choice = Recognizers.chosen(this@SoilSeamService) ?: throw IllegalStateException(SeamLimits.NO_RECOGNIZER)
            relay { RecognizerBinder.call(this@SoilSeamService, choice.recognizer.component, STATUS_TIMEOUT_MS) { it.prepare(choice.languageTag) } }
        }

        override fun recognizeInk(ink: SeamBytes, areaWidth: Float, areaHeight: Float, preContext: String): String = answered {
            val choice = Recognizers.chosen(this@SoilSeamService) ?: throw IllegalStateException(SeamLimits.NO_RECOGNIZER)
            val strokes = inkOf(ink)
            require(areaWidth > 0f && areaHeight > 0f) { "non-positive writing area" }
            val pre = preContext.takeLast(ExtContract.MAX_PRECONTEXT_CHARS)
            relay {
                RecognizerBinder.call(this@SoilSeamService, choice.recognizer.component, INK_TIMEOUT_MS) {
                    it.recognizeInk(choice.languageTag, strokes, areaWidth, areaHeight, pre)
                }
            }.orEmpty().take(ExtContract.MAX_RECOGNIZED_CHARS)
        }

        override fun recognizePage(ink: SeamBytes, pageWidth: Float, pageHeight: Float): String = answered {
            val choice = Recognizers.chosen(this@SoilSeamService) ?: throw IllegalStateException(SeamLimits.NO_RECOGNIZER)
            val strokes = inkOf(ink)
            require(pageWidth > 0f && pageHeight > 0f) { "non-positive page size" }
            relay {
                RecognizerBinder.call(this@SoilSeamService, choice.recognizer.component, PAGE_TIMEOUT_MS) {
                    it.recognizePage(choice.languageTag, strokes, pageWidth, pageHeight)
                }
            }.orEmpty().take(ExtContract.MAX_RECOGNIZED_CHARS)
        }

        // ── The side bars ──────

        override fun barKey(keyCode: Int, action: Int, eventTime: Long, repeatCount: Int) {
            SeamCallerCheck.enforce(this@SoilSeamService)
            SoilBarService.barKey(keyCode, action, eventTime, repeatCount)
        }

        /** The geometry of an `InkWire` document as the recogniser takes it, under the caps. */
        private fun inkOf(ink: SeamBytes): List<InkStroke> {
            val bundle = InkWire.decode(SeamShared.readAndClose(ink)) ?: throw IllegalArgumentException("unreadable ink")
            require(bundle.strokes.size <= ExtContract.MAX_INK_STROKES) { SeamLimits.INK_TOO_LARGE }
            var points = 0
            val out = ArrayList<InkStroke>(bundle.strokes.size)
            for (s in bundle.strokes) {
                val n = s.points.size
                if (n == 0) continue
                points += n
                require(points <= ExtContract.MAX_INK_POINTS) { SeamLimits.INK_TOO_LARGE }
                val x = FloatArray(n)
                val y = FloatArray(n)
                for (i in 0 until n) { x[i] = s.points[i].x; y[i] = s.points[i].y }
                out += InkStroke(x, y)
            }
            require(out.isNotEmpty()) { SeamLimits.INK_TOO_LARGE }
            return out
        }

        /** A recogniser's refusals as the seam's: not ready by its exact message, too much ink, else failed. */
        private fun <T> relay(block: () -> T): T = try {
            block()
        } catch (e: IllegalStateException) {
            if (e.message == ExtContract.NOT_READY) throw IllegalStateException(SeamLimits.RECOGNIZER_NOT_READY)
            Slog.d(TAG) { "the recogniser failed: ${e.message}" }
            throw IllegalStateException(SeamLimits.RECOGNITION_FAILED)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(SeamLimits.INK_TOO_LARGE)
        } catch (e: SecurityException) {
            Slog.d(TAG) { "the recogniser refused Soil" }
            throw IllegalStateException(SeamLimits.RECOGNITION_FAILED)
        } catch (e: RecognizerCallFailed) {
            Slog.d(TAG) { "the recogniser did not answer: ${e.message}" }
            throw IllegalStateException(SeamLimits.RECOGNITION_FAILED)
        }

        /** Hand a region back once the reply that carries it has been written. */
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean =
            try {
                super.onTransact(code, data, reply, flags)
            } finally {
                sent.get()?.let { runCatching { it.memory.close() } }
                sent.remove()
            }

        private val sent = ThreadLocal<SeamBytes?>()

        override fun openAppStore(schema: SeamSchema, owner: IBinder): ISeamStore = answered {
            val uid = Binder.getCallingUid()
            // The store is the caller's own: named after its package, which Android names, never
            // the schema's kind, so no app can ask for another's.
            val packageName = packageManager.getNameForUid(uid)?.substringBefore(':')
                ?: throw IllegalStateException("the caller has no package")
            AppStoreLease.open(this@SoilSeamService, packageName, schema, uid, owner, ::requireOpen)
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
        const val NO_SUCH_TEMPLATE = "there is no such template"
        const val MAX_COVER_BYTES = 1024 * 1024
        /** Pages one item may list: far past any real notebook, and a bound on one call's parcel. */
        const val MAX_PAGES = 20_000
        const val STATUS_TIMEOUT_MS = 2_000L
        const val INK_TIMEOUT_MS = 10_000L
        /** One call per line, and the first call after the recogniser's start also loads the model. */
        const val PAGE_TIMEOUT_MS = 30_000L
    }
}
