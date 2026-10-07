package com.symmetricalpalmtree.soil.library

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexSchema
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.data.item.ItemNames
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.templates.NameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * **New document**: a name, prefilled from the folder's naming scheme (else the date and time),
 * and nothing else to choose: a document has no paper. Create makes the item in Soil, in the
 * folder, and opens it in its app. The file is made here with the app's schema, empty: an app
 * finding no body makes it.
 */
object NewDocument {

    private const val TAG = "NewDocument"

    fun ask(activity: AppCompatActivity, folderId: String) {
        activity.lifecycleScope.launch {
            val prefill = withContext(Dispatchers.IO) {
                val store = LibraryStore()
                val scheme = store.resolve(folderId) { it.scheme }
                SchemePrefill.expand(scheme, System.currentTimeMillis()) { store.items(folderId).map { it.name } }
                    ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            }
            // Guarded by a flag, never a disabled button.
            var creating = false
            NameDialog.show(activity, R.string.new_document_title, R.string.new_notebook_create, initial = prefill, hintRes = R.string.new_notebook_hint) { typed, dismiss ->
                if (creating) return@show
                val name = runCatching { ItemNames.clean(typed) }.getOrNull() ?: run {
                    Dialogs.problem(activity, R.string.name_problem_title, R.string.name_empty)
                    return@show
                }
                creating = true
                // "Creating…" the moment the name is accepted: the wait is seconds of key work
                // and an app launch, and nothing on the glass for that long reads as a hang.
                dismiss()
                val wait = Dialogs.style(
                    androidx.appcompat.app.AlertDialog.Builder(activity).setMessage(R.string.creating).setCancelable(false).create(),
                ).also { it.show() }
                activity.lifecycleScope.launch {
                    try {
                        create(activity, folderId, name)
                    } finally {
                        creating = false
                        runCatching { wait.dismiss() }
                    }
                }
            }
        }
    }

    /** The row and the file, then the app. */
    private suspend fun create(activity: AppCompatActivity, folderId: String, name: String) {
        try {
            val id = withContext(Dispatchers.IO) {
                if (ItemApps.find(activity, IndexSchema.KIND_DOCUMENT) == null) return@withContext null
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                // The file first: a row with no file is an item that cannot be opened.
                ItemFiles.createEmpty(activity, id, name, now, IndexSchema.KIND_DOCUMENT)
                IndexStore().insert(id, IndexSchema.KIND_DOCUMENT, name, now, folderId)
                id
            }
            if (id == null) {
                Dialogs.problem(activity, activity.getString(R.string.item_no_app_title), activity.getString(R.string.item_no_app_body, name))
                return
            }
            ItemSessions.changed()
            if (ItemApps.open(activity, id, IndexSchema.KIND_DOCUMENT) != ItemApps.Opened.YES) {
                Dialogs.problem(activity, activity.getString(R.string.item_open_failed_title), activity.getString(R.string.item_open_failed_body, name))
            }
        } catch (e: Exception) {
            Log.w(TAG, "the document could not be made: ${e.javaClass.simpleName}")
            Dialogs.problem(activity, R.string.new_document_failed_title, R.string.new_notebook_failed_body)
        }
    }
}
