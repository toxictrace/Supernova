package com.supernova.launcher.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.search.AppMatcher

// A separate app-only search surface. The global search keeps its own
// providers, results, recent queries and keyboard behavior.
class AppSearchController(
    private val activity: Activity,
    private val viewModel: LauncherViewModel,
    private val onLaunchApp: (LaunchableApp) -> Unit
) {
    private var dialog: Dialog? = null
    private var query = ""
    private var selectedTab = TAB_FREQUENT

    fun open() {
        dialog?.dismiss()
        query = ""
        selectedTab = TAB_FREQUENT

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.surface))
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
        }
        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.search_apps_hint)
            setSingleLine(true)
            textSize = 16f
            setTextColor(activity.getColor(R.color.textPrimary))
            setHintTextColor(activity.getColor(R.color.textSecondary))
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val play = ImageView(activity).apply {
            contentDescription = activity.getString(R.string.app_search_play_store)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            val icon = runCatching {
                activity.packageManager.getApplicationIcon("com.android.vending")
            }.getOrElse {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    activity, R.drawable.ic_play_store
                )
            }
            setImageDrawable(icon)
            setOnClickListener { openPlayStore(input.text?.toString().orEmpty()) }
        }
        searchRow.addView(input)
        searchRow.addView(play)
        root.addView(searchRow)

        val tabs = TabLayout(activity).apply {
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            setTabTextColors(activity.getColor(R.color.textSecondary), activity.getColor(R.color.textPrimary))
            setSelectedTabIndicatorColor(com.supernova.launcher.ui.theme.CustomAccent.of(this))
            addTab(newTab().setText(R.string.app_search_frequent))
            addTab(newTab().setText(R.string.app_search_recent))
            addTab(newTab().setText(R.string.app_search_updated))
        }
        root.addView(tabs)

        val list = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, 4)
            adapter = AppAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(440)
        ))

        val adapter = list.adapter as AppAdapter
        fun refresh() {
            val apps = appsForTab(selectedTab)
            adapter.submit(
                if (query.isBlank()) apps
                else AppMatcher.filterAny(apps, query) { listOf(it.label, it.packageName) }
            )
            list.scrollToPosition(0)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnEditorActionListener { _, _, _ ->
            input.clearFocus()
            true
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        refresh()
        dialog = Dialog(activity).also {
            it.setContentView(root)
            it.setOnDismissListener { dialog = null }
            it.show()
            it.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0.28f)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                attributes = attributes.apply { y = dp(12) }
                setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
        }
        input.requestFocus()
    }

    private fun appsForTab(tab: Int): List<LaunchableApp> {
        val apps = viewModel.searchableApps()
        return when (tab) {
            TAB_RECENT -> viewModel.recentApps(apps.size)
            TAB_UPDATED -> apps.sortedWith(
                compareByDescending<LaunchableApp> { maxOf(it.updateTime, it.installTime) }
                    .thenBy { it.label.lowercase() }
            )
            else -> {
                val counts = viewModel.launchCounts()
                apps.sortedWith(
                    compareByDescending<LaunchableApp> { counts[viewModel.hiddenKey(it)] ?: 0 }
                        .thenBy { it.label.lowercase() }
                )
            }
        }
    }

    private fun openPlayStore(text: String) {
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://search?q=" + Uri.encode(text) + "&c=apps")
        )
        try {
            activity.startActivity(market)
        } catch (_: android.content.ActivityNotFoundException) {
            val web = Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(text) + "&c=apps")
            activity.startActivity(Intent(Intent.ACTION_VIEW, web))
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<LaunchableApp> = emptyList()

        fun submit(next: List<LaunchableApp>) {
            items = next
            notifyDataSetChanged()
        }

        inner class Holder(
            val cell: LinearLayout,
            val icon: ImageView,
            val label: TextView
        ) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
                )
            }
            val icon = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val label = TextView(activity).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                textSize = 11f
                setTextColor(activity.getColor(R.color.textPrimary))
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(icon)
            cell.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return Holder(cell, icon, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.label.text = app.label
            holder.icon.setImageDrawable(viewModel.appCatalog().iconFor(app))
            holder.cell.contentDescription = app.label
            holder.cell.setOnClickListener {
                dialog?.dismiss()
                onLaunchApp(app)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_FREQUENT = 0
        private const val TAB_RECENT = 1
        private const val TAB_UPDATED = 2
    }
}
