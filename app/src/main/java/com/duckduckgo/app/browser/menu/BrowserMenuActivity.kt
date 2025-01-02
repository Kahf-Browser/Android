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

package com.duckduckgo.app.browser.menu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.ActivityMenuBinding
import com.duckduckgo.app.browser.menu.adapter.ViewPagerAdapter
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.di.scopes.ActivityScope
import com.google.android.material.tabs.TabLayoutMediator

@InjectWith(ActivityScope::class)
class BrowserMenuActivity : DuckDuckGoActivity() {

    private val viewModel: MenuViewModel by bindViewModel()
    private val binding: ActivityMenuBinding by viewBinding()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setTranslucentStatusBarAndNavBar()
        configureViewPager()
    }

    private fun configureViewPager() {
        binding.viewPager.adapter = ViewPagerAdapter(this, intent.getBooleanExtra(EXTRA_KEY_TAB_DISPLAYED, true))

        val tabIcons = listOf(
            R.drawable.ic_menu,
            R.drawable.ic_bookmark,
            R.drawable.ic_tab_history,
            R.drawable.ic_tab_settings,
            R.drawable.ic_account,
        )

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setIcon(tabIcons[position])
        }.attach()
    }

    companion object {
        fun intent(
            context: Context,
            displayedInCustomTabScreen: Boolean
        ): Intent {
            val intent = Intent(context, BrowserMenuActivity::class.java)
            intent.putExtra(EXTRA_KEY_TAB_DISPLAYED, displayedInCustomTabScreen)

            return intent
        }

        const val EXTRA_KEY_TAB_DISPLAYED = "displayedInCustomTabScreen"
    }
}
