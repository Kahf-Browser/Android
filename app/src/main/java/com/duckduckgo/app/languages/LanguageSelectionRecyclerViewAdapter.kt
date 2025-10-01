/*
 * Copyright (c) 2025 DuckDuckGo
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

package com.duckduckgo.app.languages

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.databinding.ItemLanguageHolderBinding

class LanguageSelectionRecyclerViewAdapter(
    private val languageList: List<Language>,
    private val listener: OnLanguageClickedListener
): RecyclerView.Adapter<LanguageSelectionRecyclerViewAdapter.LanguageViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): LanguageViewHolder {
        val binding = ItemLanguageHolderBinding.inflate(
            android.view.LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LanguageViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: LanguageViewHolder,
        position: Int
    ) {
        holder.bindLanguage(languageList[position], listener)
    }

    override fun getItemCount(): Int {
        return languageList.size
    }

    class LanguageViewHolder(private val binding: ItemLanguageHolderBinding): RecyclerView.ViewHolder(binding.root) {
        fun bindLanguage(language: Language, listener: OnLanguageClickedListener) {
            binding.textFlag.text = language.flag
            binding.textLanguageName.text = language.name
            binding.root.setOnClickListener {
                listener.onLanguageClicked(language = language)
            }
        }
    }
}

