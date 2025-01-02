/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.onboarding.ui.page

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.databinding.PredefinedBookmarkItemBinding
import com.duckduckgo.app.onboarding.model.PredefinedBookmark

class PredefinedBookmarkAdapter(
    private val items: List<PredefinedBookmark>,
    private val onItemClick: (Int) -> Unit,
) : RecyclerView.Adapter<PredefinedBookmarkAdapter.ViewHolder>() {

    inner class ViewHolder(binding: PredefinedBookmarkItemBinding) : RecyclerView.ViewHolder(binding.root) {
        val mBinding = binding
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        val binding = PredefinedBookmarkItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.mBinding.logo.setImageResource(item.iconRes)
        holder.mBinding.root.setOnClickListener {
            onItemClick(position)
        }

        holder.mBinding.selectedIndicator.isVisible = item.selected
    }

    override fun getItemCount() = items.size

    fun getItems() = items
}
