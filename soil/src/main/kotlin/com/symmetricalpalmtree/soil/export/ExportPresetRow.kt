package com.symmetricalpalmtree.soil.export

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.item.ItemNames
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.templates.NameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The presets row of the export screen: None and every preset for the formats in front, a
 *  long press to rename or delete, and the Save button under them. */
class ExportPresetRow(private val activity: AppCompatActivity, private val panel: ExportPanel, private val container: LinearLayout, private val host: Host) {

    interface Host {
        fun currentState(): ExportPresets.State?
        fun listedPackages(): Set<String>
        fun applyPreset(state: ExportPresets.State)
        fun presetsChanged()
    }

    var selectedId: String? = null
    private var rows: List<ExportPresets.Row> = emptyList()
    var listed: List<ExportPresets.Row> = emptyList()
        private set

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) { runCatching { ExportPresetStore().all() }.onFailure { Slog.d(TAG) { "presets unreadable: ${it.javaClass.simpleName}" } }.getOrDefault(emptyList()) }
        recut()
    }

    fun recut() {
        listed = ExportPresets.listable(rows, host.listedPackages())
        if (listed.none { it.id == selectedId }) selectedId = null
    }

    fun render() {
        container.removeAllViews()
        val state = host.currentState()
        container.visibility = if (listed.isNotEmpty() || state != null) View.VISIBLE else View.GONE
        if (listed.isNotEmpty()) {
            container.addView(panel.caption(activity.getString(R.string.export_preset_caption)))
            val none = selectedId == null
            container.addView(panel.choice(activity.getString(R.string.export_preset_none), none) { if (!none) { selectedId = null; render() } })
            for (item in listed) {
                val checked = item.id == selectedId
                container.addView(panel.choice(item.name, checked, onLongPress = { sheet(item) }) { if (!checked) pick(item) })
            }
        }
        if (state != null) container.addView(saveButton())
    }

    /** A hand on any row un-selects the preset: what shows is no longer the preset's. */
    fun onHandChange() {
        if (selectedId == null) return
        selectedId = null
        render()
    }

    private fun pick(item: ExportPresets.Row) {
        selectedId = item.id
        host.applyPreset(ExportPresets.apply(item.preset))
    }

    private fun saveButton(): View = AppCompatButton(activity).apply {
        val d = activity.resources.displayMetrics.density
        text = activity.getString(R.string.export_preset_save_action)
        background = ColorDrawable(Color.TRANSPARENT)
        setTextColor(ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
        textSize = 14f
        isAllCaps = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        setOnClickListener { askSaveName() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun askSaveName() {
        if (activity.isFinishing || activity.isDestroyed) return
        var accepting = false
        NameDialog.show(activity, R.string.export_preset_save_title, R.string.export_preset_save_confirm, "", R.string.export_preset_name_hint) { name, dismiss ->
            if (accepting) return@show
            val clean = runCatching { ItemNames.clean(name) }.getOrNull()
            if (clean == null) { Dialogs.problem(activity, R.string.name_problem_title, R.string.name_empty); return@show }
            accepting = true
            activity.lifecycleScope.launch {
                val state = host.currentState()
                if (state == null || activity.isFinishing || activity.isDestroyed) { dismiss(); return@launch }
                val taken = withContext(Dispatchers.IO) { ExportPresetStore().nameTaken(clean) }
                if (taken) { accepting = false; Dialogs.problem(activity, R.string.export_preset_exists_title, R.string.export_preset_exists_body); return@launch }
                val id = withContext(Dispatchers.IO) { ExportPresetStore().create(clean, ExportPresets.capture(state)) }
                dismiss()
                selectedId = id
                reload()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                host.presetsChanged()
                Toast.makeText(activity, R.string.export_preset_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sheet(item: ExportPresets.Row) {
        if (activity.isFinishing || activity.isDestroyed) return
        ActionSheetDialog(activity).title(item.name)
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_edit, activity.getString(R.string.export_preset_rename)) { askRename(item) }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, activity.getString(R.string.export_preset_delete)) { confirmDelete(item) }
            .show()
    }

    private fun askRename(item: ExportPresets.Row) {
        if (activity.isFinishing || activity.isDestroyed) return
        var accepting = false
        NameDialog.show(activity, R.string.export_preset_rename_title, R.string.action_rename, item.name, R.string.export_preset_name_hint) { name, dismiss ->
            if (accepting) return@show
            if (name == item.name) { dismiss(); return@show }
            val clean = runCatching { ItemNames.clean(name) }.getOrNull()
            if (clean == null) { Dialogs.problem(activity, R.string.name_problem_title, R.string.name_empty); return@show }
            accepting = true
            activity.lifecycleScope.launch {
                val taken = withContext(Dispatchers.IO) { ExportPresetStore().nameTaken(clean, exceptId = item.id) }
                if (taken) { accepting = false; if (!activity.isFinishing && !activity.isDestroyed) Dialogs.problem(activity, R.string.export_preset_exists_title, R.string.export_preset_exists_body); return@launch }
                withContext(Dispatchers.IO) { ExportPresetStore().rename(item.id, clean) }
                dismiss()
                reload()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                host.presetsChanged()
            }
        }
    }

    private fun confirmDelete(item: ExportPresets.Row) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.export_preset_delete_title, item.name))
                .setMessage(R.string.export_preset_delete_body)
                .setPositiveButton(R.string.export_preset_delete_confirm) { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ExportPresetStore().delete(item.id) }
                        reload()
                        if (activity.isFinishing || activity.isDestroyed) return@launch
                        host.presetsChanged()
                    }
                }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    private companion object { const val TAG = "ExportPresetRow" }
}
