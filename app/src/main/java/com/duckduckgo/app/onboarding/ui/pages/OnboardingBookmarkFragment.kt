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

package com.duckduckgo.app.onboarding.ui.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboardingBookmarkBinding
import com.duckduckgo.app.onboarding.model.PredefinedBookmark
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.app.onboarding.ui.page.PredefinedBookmarkAdapter
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.savedsites.api.SavedSitesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingBookmarkFragment: DuckDuckGoFragment(R.layout.fragment_onboarding_bookmark) {

    lateinit var binding: FragmentOnboardingBookmarkBinding
    private lateinit var rvAdapter: PredefinedBookmarkAdapter

    @Inject
    lateinit var savedSitesRepository: SavedSitesRepository

    @Inject
    lateinit var dispatcherProvide: DispatcherProvider

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnboardingBookmarkBinding.inflate(inflater, container, false)

        setupButtonClicks()
        setupRecyclerView()

        // To avoid the 'Skip' button being hidden behind the navigation bar
        (requireActivity() as DuckDuckGoActivity).getNavigationBarHeight {
            binding.guidelineBottom.setGuidelinePercent(
                if (it > 100) 0.8f else 0.9f
            )
        }

        // Alternative solution (need checking on both orientation and on multiple devices)
        /*(requireActivity() as DuckDuckGoActivity).getNavigationBarHeight { barHeight ->
            val pcOfNavbarHeight = ((barHeight.toDouble() / getDisplayHeightInPixel().toDouble()) * 100)

            binding.guidelineBottom.setGuidelinePercent(
                (1 - (pcOfNavbarHeight + 1) / 100).toFloat(),
            )
        }*/

        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(
            SELECTED_ITEMS,
            rvAdapter.getItems().withIndex().filter { it.value.selected }.map { it.index }.toIntArray()
        )
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.getIntArray(SELECTED_ITEMS)?.forEach {
            rvAdapter.getItems()[it].selected = true
        }
    }

    private fun setupRecyclerView() {
        val itemList = mutableListOf<PredefinedBookmark>()
        itemList.add(PredefinedBookmark(R.drawable.ic_youtube,"Youtube", "https://youtube.com"))
        itemList.add(PredefinedBookmark(R.drawable.ic_facebook,"Facebook", "https://facebook.com"))
        itemList.add(PredefinedBookmark(R.drawable.ic_twitter,"X", "https://x.com"))
        itemList.add(PredefinedBookmark(R.drawable.ic_gmail,"Gmail", "https://gmail.com"))

        rvAdapter = PredefinedBookmarkAdapter(itemList) { position->
            itemList[position].selected = !itemList[position].selected
            rvAdapter.notifyItemChanged(position)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager (context, 3)
            adapter = rvAdapter
        }

        binding.recyclerView
    }

    private fun setupButtonClicks() {
        binding.btnSkip.setOnClickListener {
            CoroutineScope(dispatcherProvide.io()).launch {
                addBookmarks(false)

                withContext(dispatcherProvide.main()) {
                    (requireActivity() as KahfOnboardingActivity).onContinueClicked()
                }
            }
        }

        binding.btnSetBookmark.setOnClickListener {
            CoroutineScope(dispatcherProvide.io()).launch {
                addBookmarks(true)

                withContext(dispatcherProvide.main()) {
                    (requireActivity() as KahfOnboardingActivity).onContinueClicked()
                }
            }
        }
    }

    private fun addBookmarks(continueClicked: Boolean) {
        val selectedBookmarks = if (continueClicked)
            rvAdapter.getItems().filter { it.selected }
        else emptyList()

        selectedBookmarks.toMutableList().let {
            it.add(PredefinedBookmark(R.drawable.ic_youtube,"Islam QA", "https://islamqa.info/en"))
            it.add(PredefinedBookmark(R.drawable.ic_youtube,"Quran.com", "https://quran.com/"))

            it.forEach { bookmark ->
                savedSitesRepository.insertFavorite(url = bookmark.url, title = bookmark.title)
            }
        }
    }

    companion object {
        const val SELECTED_ITEMS: String = "SELECTED_ITEMS"
    }
}
