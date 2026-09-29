package com.symmetricalpalmtree.soil.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.databinding.ActivityHiddenAppsBinding
import com.symmetricalpalmtree.soil.databinding.RowHiddenAppBinding
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.shell.AppEntry
import com.symmetricalpalmtree.soil.shell.AppList
import com.symmetricalpalmtree.soil.shell.HiddenApps
import com.symmetricalpalmtree.soil.shell.Paging
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * **The hidden apps**: every app the person has hidden, each with a Show button that puts it back
 * in the app drawer and the side menu. Fixed pages; nothing scrolls.
 *
 * It needs no key, like the drawer it serves.
 */
class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenAppsBinding
    private var apps: List<AppEntry> = emptyList()
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnPrev.setOnClickListener { turnTo(page - 1) }
        binding.btnNext.setOnClickListener { turnTo(page + 1) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AppList.apps, HiddenApps.hidden, HiddenApps::hiddenOf).collect {
                    apps = it
                    // Posted, never run inside the layout pass itself: a view added during a
                    // pass is not laid out by it, and stays unseen until something else redraws.
                    binding.rows.doOnLayout { rows -> rows.post { render() } }
                }
            }
        }
    }

    private fun perPage(): Int =
        Paging.fit(binding.rows.height, resources.getDimensionPixelSize(R.dimen.menu_row_height))

    private fun turnTo(wanted: Int) {
        val to = Paging.clamp(wanted, apps.size, perPage())
        if (to == page) return
        page = to
        render()
    }

    private fun render() {
        val perPage = perPage()
        page = Paging.clamp(page, apps.size, perPage)
        binding.emptyMessage.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        binding.rows.removeAllViews()
        for (app in Paging.slice(apps, page, perPage)) {
            val row = RowHiddenAppBinding.inflate(LayoutInflater.from(this), binding.rows, false)
            row.icon.setImageDrawable(app.icon)
            row.label.text = app.label
            row.btnShow.contentDescription = getString(R.string.hidden_show_named, app.label)
            row.btnShow.setOnClickListener { HiddenApps.show(this, app) }
            binding.rows.addView(row.root)
        }
        val pages = Paging.pageCount(apps.size, perPage)
        // A pager over one page is two buttons that do nothing: it is not shown.
        binding.bottomBar.visibility = if (pages > 1) View.VISIBLE else View.GONE
        binding.pageText.text = getString(R.string.page_of, page + 1, pages)
    }
}
