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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.LanguageBottomSheetDialogFragmentBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LanguageSelectionBottomSheetDialogFragment: BottomSheetDialogFragment() {
    var onLanguageClicked: OnLanguageClickedListener? = null

    // Language list
    val supportedLanguages = listOf(
        Language("🇬🇧", "English", "en"),   // English
        Language("🇧🇩", "বাংলা", "bn"),   // Bangla
        Language("🇸🇦", "العربية", "ar"),   // Arabic
        Language("🇹🇷", "Türkçe", "tr")  ,   // Turkish
        Language("🇲🇾", "Bahasa Melayu", "ms"), // Malay
        Language("🇧🇬", "Български", "bg"), // Bulgarian
        Language("🇨🇿", "Čeština", "cs"),   // Czech
        Language("🇩🇰", "Dansk", "da"),     // Danish
        Language("🇩🇪", "Deutsch", "de"),   // German
        Language("🇬🇷", "Ελληνικά", "el"),  // Greek
        Language("🇪🇸", "Español", "es"),   // Spanish
        Language("🇪🇪", "Eesti", "et"),     // Estonian
        Language("🇫🇮", "Suomi", "fi"),     // Finnish
        Language("🇫🇷", "Français", "fr"),  // French
        Language("🇭🇷", "Hrvatski", "hr"),  // Croatian
        Language("🇭🇺", "Magyar", "hu"),    // Hungarian
        Language("🇮🇹", "Italiano", "it"),  // Italian
        Language("🇱🇹", "Lietuvių", "lt"),  // Lithuanian
        Language("🇱🇻", "Latviešu", "lv"),  // Latvian
        Language("🇳🇴", "Norsk Bokmål", "nb"), // Norwegian Bokmål
        Language("🇳🇱", "Nederlands", "nl"),   // Dutch
        Language("🇵🇱", "Polski", "pl"),    // Polish
        Language("🇵🇹", "Português", "pt"), // Portuguese
        Language("🇷🇴", "Română", "ro"),    // Romanian
        Language("🇷🇺", "Русский", "ru"),   // Russian
        Language("🇸🇰", "Slovenčina", "sk"),// Slovak
        Language("🇸🇮", "Slovenščina", "sl"),// Slovenian
        Language("🇸🇪", "Svenska", "sv")   // Swedish
    )


    private lateinit var binding: LanguageBottomSheetDialogFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.language_bottom_sheet_dialog_fragment, container, false)
        binding = LanguageBottomSheetDialogFragmentBinding.bind(view)
        return view
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = 1000
            }
        }

        binding.recyclerView.adapter = LanguageSelectionRecyclerViewAdapter(
            supportedLanguages, object : OnLanguageClickedListener {
                override fun onLanguageClicked(language: Language) {
                    onLanguageClicked?.onLanguageClicked(language)
                    dismiss()
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // binding.recyclerView.addItemDecoration(ItemOffsetDecoration(requireContext()))
    }

    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private val fragment = LanguageSelectionBottomSheetDialogFragment()
        private var onLanguageClicked: OnLanguageClickedListener? = null

        fun setListener(
            onLanguageClicked: OnLanguageClickedListener
        ): Builder {
            this.onLanguageClicked = onLanguageClicked
            return this
        }

        fun build(): LanguageSelectionBottomSheetDialogFragment {
            fragment.onLanguageClicked = onLanguageClicked
            return fragment
        }
    }

    class ItemOffsetDecoration(private val context: Context) : RecyclerView.ItemDecoration() {

        private val spacing: Int = dpToPx(10) // Convert dp to pixels

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            super.getItemOffsets(outRect, view, parent, state)
            val itemPosition = parent.getChildAdapterPosition(view)

            // Add top spacing for the first item
            if (itemPosition == 0) {
                outRect.top = spacing * 2
                outRect.bottom = spacing / 2
            }

            // Add bottom spacing for the last item
            if (itemPosition == parent.adapter?.itemCount?.minus(1)) {
                outRect.top = spacing / 2
                outRect.bottom = spacing * 2
            }

            // Add spacing between items
            if (itemPosition > 0 && itemPosition < (parent.adapter?.itemCount?.minus(1) ?: 0)) {
                outRect.top = spacing / 2
                outRect.bottom = spacing / 2
            }
        }

        private fun dpToPx(dp: Int): Int {
            val density = context.resources.displayMetrics.density
            return (dp * density).toInt()
        }
    }
}

