/*
 * Copyright (c) 2017 DuckDuckGo
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

package com.duckduckgo.app.about

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.ContributeToActivityStarter
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.about.AboutDuckDuckGoViewModel.Command
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.ActivityAboutDuckDuckGoBinding
import com.duckduckgo.browser.api.ui.BrowserScreens.WebViewActivityWithParams
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.common.utils.AppUrl.Url
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.navigation.api.GlobalActivityStarter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@InjectWith(ActivityScope::class)
@ContributeToActivityStarter(AboutScreenNoParams::class)
class AboutDuckDuckGoActivity : DuckDuckGoActivity() {

    private val viewModel: AboutDuckDuckGoViewModel by bindViewModel()
    private val binding: ActivityAboutDuckDuckGoBinding by viewBinding()

    private val feedbackFlow = registerForActivityResult(FeedbackContract()) { resultOk ->
        if (resultOk) {
            Snackbar.make(
                binding.root,
                R.string.thanksForTheFeedback,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    @Inject
    lateinit var globalActivityStarter: GlobalActivityStarter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        setupToolbar(binding.includeToolbar.toolbar)
        setupWebView(binding.aboutWebView)

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetEasterEggCounter()
    }

    private fun setupWebView(aboutWebView: WebView) {
        // Block the WebView from going to another page
        aboutWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.host != "kahfbrowser.com"
            }
        }
    }

    private fun observeViewModel() {
        viewModel.viewState()
            .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
            .onEach { viewState ->
                viewState.let {
                    binding.aboutWebView.loadUrl(ABOUT_US_URL)
                }
            }.launchIn(lifecycleScope)

        viewModel.commands()
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { processCommand(it) }
            .launchIn(lifecycleScope)
    }

    private fun processCommand(it: Command) {
        when (it) {
            is Command.LaunchBrowserWithLearnMoreUrl -> launchBrowserScreen()
            is Command.LaunchWebViewWithPrivacyPolicyUrl -> launchWebViewScreen()
            is Command.LaunchBrowserWithPrivacyProtectionsUrl -> launchPrivacyProtectionsScreen()
            is Command.LaunchFeedback -> launchFeedback()
        }
    }

    private fun launchBrowserScreen() {
        startActivity(BrowserActivity.intent(this, Url.ABOUT))
        finish()
    }

    private fun launchWebViewScreen() {
        globalActivityStarter.start(
            this,
            WebViewActivityWithParams(
                url = PRIVACY_POLICY_WEB_LINK,
                screenTitle = getString(R.string.settingsPrivacyPolicyAsil),
            ),
        )
    }

    private fun launchPrivacyProtectionsScreen() {
        globalActivityStarter.start(
            this,
            WebViewActivityWithParams(
                url = PRIVACY_PROTECTIONS_WEB_LINK,
                screenTitle = getString(R.string.settingsAboutAsil),
            ),
        )
    }

    private fun launchFeedback() {
        feedbackFlow.launch(null)
    }

    companion object {
        private const val ABOUT_US_URL = "https://kahfbrowser.com/about/"
        private const val PRIVACY_POLICY_WEB_LINK = "https://asil.co/privacy"
        private const val PRIVACY_PROTECTIONS_WEB_LINK = "https://asil.co/asil-help-pages/privacy/web-tracking-protections/"
    }
}
