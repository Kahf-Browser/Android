package com.duckduckgo.app.browser.newtab

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.databinding.ItemHistoryEntryBinding
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.common.ui.menu.PopupMenu
import com.duckduckgo.history.api.HistoryEntry
import com.duckduckgo.saved.sites.impl.R
import kotlinx.coroutines.launch

class HistoryQuickAccessAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val faviconManager: FaviconManager,
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onDeleteItem: (HistoryEntry) -> Unit,
    private val onClearAll: () -> Unit,
) : ListAdapter<HistoryEntry, HistoryQuickAccessViewHolder>(HistoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryQuickAccessViewHolder {
        val binding = ItemHistoryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryQuickAccessViewHolder(binding, faviconManager, lifecycleOwner, onDeleteItem, onClearAll)
    }

    override fun onBindViewHolder(holder: HistoryQuickAccessViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onItemClick(item) }
    }
}

private class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryEntry>() {
    override fun areItemsTheSame(
        oldItem: HistoryEntry,
        newItem: HistoryEntry,
    ): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(
        oldItem: HistoryEntry,
        newItem: HistoryEntry,
    ): Boolean {
        return oldItem == newItem
    }
}

class HistoryQuickAccessViewHolder(
    val binding: ItemHistoryEntryBinding,
    val faviconManager: FaviconManager,
    val lifecycleOwner: LifecycleOwner,
    val onDeleteItem: (HistoryEntry) -> Unit,
    val onClearAll: () -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: HistoryEntry) {
        lifecycleOwner.lifecycleScope.launch {
            faviconManager.loadToViewMaybeFromRemoteWithPlaceholder(
                url = item.url.toString(),
                view = binding.historyItemImage,
                fetchFromRemote = true,
            )
        }
        binding.historyItemLabel.text = item.title

        binding.root.setOnLongClickListener {
            showOverFlowMenu(binding.root, binding, item)
            false
        }
    }

    private fun showOverFlowMenu(
        anchor: View,
        binding: ItemHistoryEntryBinding,
        favorite: HistoryEntry,
    ) {
        val popupMenu = PopupMenu(LayoutInflater.from(anchor.context), R.layout.popup_window_delete_history)
        val view = popupMenu.contentView
        popupMenu.apply {
            onMenuItemClicked(view.findViewById(R.id.removeFromHistory)) { onDeleteItem(favorite) }
            onMenuItemClicked(view.findViewById(R.id.delete)) { onClearAll() }
        }
        popupMenu.showAnchoredToView(binding.root, anchor)
    }
}


class SpaceItemDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.left = space
        outRect.right = space
        outRect.bottom = space
    }
}
