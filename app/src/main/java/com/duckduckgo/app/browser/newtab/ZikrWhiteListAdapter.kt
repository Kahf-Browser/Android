package com.duckduckgo.app.browser.newtab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.databinding.ItemAddWhitelistBinding
import com.duckduckgo.app.browser.databinding.ItemHistoryEntryBinding
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.app.primaryDomain
import com.duckduckgo.common.ui.menu.PopupMenu
import com.duckduckgo.saved.sites.impl.R
import com.duckduckgo.saved.sites.impl.databinding.DialogAddWhitelistItemBinding
import kotlinx.coroutines.launch

class ZikrWhiteListAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val faviconManager: FaviconManager,
    private val onItemClick: (WhiteListItem) -> Unit,
    private val onDeleteItem: (WhiteListItem) -> Unit,
    private val onAddClick: (title: String, url: String) -> Unit,
) : ListAdapter<WhiteListItem, RecyclerView.ViewHolder>(WhiteListDiffCallback()) {

    companion object {
        const val VIEW_TYPE_ITEM = 1
        const val VIEW_TYPE_ADD = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isPlus) VIEW_TYPE_ADD else VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ADD -> {
                val binding = ItemAddWhitelistBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AddWhiteListViewHolder(binding, faviconManager, lifecycleOwner, onAddClick)
            }
            else -> {
                val binding = ItemHistoryEntryBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                ZikrWhiteListViewHolder(binding, faviconManager, lifecycleOwner, onDeleteItem)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is AddWhiteListViewHolder -> {
                holder.bind(item)
                holder.binding.historyItemLabel.text = holder.binding.root.context.getString(R.string.zikr_add_white_list)
            }
            is ZikrWhiteListViewHolder -> {
                holder.bind(item)
                holder.itemView.setOnClickListener { onItemClick(item) }
            }
        }
    }
}

private class WhiteListDiffCallback : DiffUtil.ItemCallback<WhiteListItem>() {
    override fun areItemsTheSame(
        oldItem: WhiteListItem,
        newItem: WhiteListItem,
    ): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(
        oldItem: WhiteListItem,
        newItem: WhiteListItem,
    ): Boolean {
        return oldItem == newItem
    }
}

class AddWhiteListViewHolder(
    val binding: ItemAddWhitelistBinding,
    val faviconManager: FaviconManager,
    val lifecycleOwner: LifecycleOwner,
    val onAddClick: (title: String, url: String) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: WhiteListItem) {
        binding.quickAccessFaviconCard.setOnClickListener {
            showPopup()
        }
    }

    private fun showPopup() {
        val context = binding.root.context
        val dialogBuilder = android.app.AlertDialog.Builder(context)
        val dialogView = DialogAddWhitelistItemBinding.inflate(LayoutInflater.from(context))

        val titleInput = dialogView.editTextName
        val urlInput = dialogView.editTextUrl

        dialogBuilder.setView(dialogView.root)
        dialogBuilder.setPositiveButton(android.R.string.ok) { _, _ ->
            val title = titleInput.text.toString()
            val url = urlInput.text.toString()
            if (title.isNotEmpty() && url.isNotEmpty()) {
                onAddClick(title.trim(), url.primaryDomain())
            }
        }
        dialogBuilder.setNegativeButton(android.R.string.cancel) { dialog, _ ->
            dialog.dismiss()
        }
        dialogBuilder.show()
    }
}

class ZikrWhiteListViewHolder(
    val binding: ItemHistoryEntryBinding,
    val faviconManager: FaviconManager,
    val lifecycleOwner: LifecycleOwner,
    val onDeleteItem: (WhiteListItem) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: WhiteListItem) {
        lifecycleOwner.lifecycleScope.launch {
            faviconManager.getFaviconFromGlide("https://${item.url}".toUri())?.let { bitmap ->
                binding.historyItemImage.setImageBitmap(bitmap)
            }
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
        favorite: WhiteListItem,
    ) {
        val popupMenu = PopupMenu(LayoutInflater.from(anchor.context), R.layout.popup_window_remove_white_list)
        val view = popupMenu.contentView
        popupMenu.apply {
            onMenuItemClicked(view.findViewById(R.id.remove)) { onDeleteItem(favorite) }
        }
        popupMenu.showAnchoredToView(binding.root, anchor)
    }
}

