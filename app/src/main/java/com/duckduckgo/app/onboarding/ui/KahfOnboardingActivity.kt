/*
 * Copyright (c) 2019 DuckDuckGo
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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.databinding.ActivityKahfOnboardingBinding
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserDetector
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.di.scopes.ActivityScope
import javax.inject.Inject

@InjectWith(ActivityScope::class)
class KahfOnboardingActivity : DuckDuckGoActivity() {

    @Inject
    lateinit var defaultWebBrowserCapability: DefaultBrowserDetector

    private lateinit var viewPageAdapter: OnboardingAdapter

    private val viewModel: OnboardingViewModel by bindViewModel()

    private val binding: ActivityKahfOnboardingBinding by viewBinding()

    private val viewPager
        get() = binding.viewPager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        configurePager()

        val startPage = intent.getIntExtra(EXTRA_START_PAGE, 0)
        if (startPage > 0 && startPage < viewPageAdapter.itemCount) {
            viewPager.setCurrentItem(startPage, false)
        }
    }

    fun onContinueClicked() {
        val next = viewPager.currentItem + 1
        if (next < viewPager.adapter!!.itemCount) {
            viewPager.setCurrentItem(next, true)
        } else {
            onOnboardingDone()
        }
    }

    private fun onOnboardingDone() {
        viewModel.onOnboardingDone()
        startActivity(BrowserActivity.intent(this@KahfOnboardingActivity))
        finish()
    }

    private fun configurePager() {
        /*val showDefaultBrowserPage =
            defaultWebBrowserCapability.deviceSupportsDefaultBrowserConfiguration() && !defaultWebBrowserCapability.isDefaultBrowser()*/

        viewPageAdapter = OnboardingAdapter(
            supportFragmentManager,
            lifecycle
        )
        viewPager.offscreenPageLimit = 1
        viewPager.adapter = viewPageAdapter
        viewPager.setUserInputEnabled(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val currentPage = viewPager.currentItem
        if (currentPage == 0) {
            finish()
        } else {
            viewPager.setCurrentItem(currentPage - 1, true)
        }
    }

    companion object {
        const val EXTRA_START_PAGE = "EXTRA_START_PAGE"

        fun intent(context: Context): Intent {
            return Intent(context, KahfOnboardingActivity::class.java)
        }
    }
}


