/*
 * Copyright (c) 2018 DuckDuckGo
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

package com.duckduckgo.app.onboarding.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.duckduckgo.app.onboarding.ui.pages.OnboardingBookmarkFragment
import com.duckduckgo.app.onboarding.ui.pages.OnboardingFragment1
import com.duckduckgo.app.onboarding.ui.pages.OnboardingFragment2
import com.duckduckgo.app.onboarding.ui.pages.OnboardingSafeGazeFragment
import com.duckduckgo.common.ui.DuckDuckGoFragment

class OnboardingAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
) : FragmentStateAdapter(fragmentManager, lifecycle) {
    val pages = mutableListOf<Fragment>()

    init {
        pages.add(OnboardingFragment1())
        pages.add(OnboardingSafeGazeFragment())
    }

    override fun getItemCount(): Int {
        return pages.size
    }

    override fun createFragment(position: Int): Fragment {
        return pages[position]
    }
}
