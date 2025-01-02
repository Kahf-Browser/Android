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

package com.duckduckgo.app.browser.menu.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.duckduckgo.app.browser.menu.fragments.BookmarkFragment
import com.duckduckgo.app.browser.menu.fragments.MenuFragment
import com.duckduckgo.common.ui.DuckDuckGoActivity

class ViewPagerAdapter(
    activity: DuckDuckGoActivity,
    private val displayedInCustomTabScreen: Boolean
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = 5

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MenuFragment.newInstance(displayedInCustomTabScreen)
            1 -> BookmarkFragment()
            2 -> MenuFragment.newInstance(displayedInCustomTabScreen)
            3 -> MenuFragment.newInstance(displayedInCustomTabScreen)
            4 -> MenuFragment.newInstance(displayedInCustomTabScreen)
            else -> MenuFragment.newInstance(displayedInCustomTabScreen)
        }

        /*return when (position) {
            0 -> MenuFragment()
            1 -> BookmarkFragment()
            2 -> HistoryFragment()
            3 -> SettingsFragment()
            4 -> AccountFragment()
            else -> MenuFragment()
        }*/
    }
}
