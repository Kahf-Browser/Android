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

package com.duckduckgo.app.browser.menu.fragments

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.duckduckgo.adclick.store.AdClickDatabase.Companion.MIGRATION_1_2
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.BrowserTabFragment
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentMenuBinding
import com.duckduckgo.app.browser.menu.adapter.MenuItem
import com.duckduckgo.app.browser.menu.adapter.MenuRvAdapter
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.di.scopes.FragmentScope
import kotlin.properties.Delegates
import com.duckduckgo.autofill.impl.R as RRR
import com.duckduckgo.mobile.android.R as RR

@InjectWith(FragmentScope::class)
class MenuFragment : DuckDuckGoFragment(R.layout.fragment_menu) {

    lateinit var binding: FragmentMenuBinding
    private var displayedInCustomTabScreen by Delegates.notNull<Boolean>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMenuBinding.inflate(inflater, container, false)
        displayedInCustomTabScreen = arguments?.getBoolean(DISPLAYS_IN_CUSTOM_TAB_SCREEN) ?: false
        configureRecyclerView()

        return binding.root
    }

    private fun configureRecyclerView() {
        binding.rvMenu.layoutManager = LinearLayoutManager(context)

        val itemList = mutableListOf<MenuItem>()

        /*BrowserTabFragment.viewStateForMenuActivity?.apply {
            // New Tab
            if (browserShowing && !displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_add_16, getString(R.string.newTabMenuItem), R.id.newTabMenuItem))

            // Share
            if (canSharePage)
                itemList.add(MenuItem(RR.drawable.ic_share_android_16, getString(R.string.shareMenuTitle), R.id.sharePageMenuItem))

            // Add bookmark
            if (canSaveSite && !displayedInCustomTabScreen) {
                itemList.add(MenuItem(
                    if (bookmark != null) RR.drawable.ic_bookmark_solid_16 else RR.drawable.ic_bookmark_16,
                    getString(if (bookmark != null) R.string.editBookmarkMenuTitle else R.string.addBookmarkMenuTitle),
                    R.id.addBookmarksMenuItem,
                ))
            }

            // Bookmarks menu
            if (!displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_library_16, getString(R.string.bookmarksMenuTitle), R.id.bookmarksMenuItem))

            // Fireproof
            if (canFireproofSite && !displayedInCustomTabScreen) {
                itemList.add(MenuItem(
                    if (isFireproofWebsite) RR.drawable.ic_fire_16 else RR.drawable.ic_fireproofed_16,
                    getString(
                        if (isFireproofWebsite) R.string.fireproofWebsiteMenuTitleRemove else R.string.fireproofWebsiteMenuTitleAdd,
                    ),
                    R.id.fireproofWebsiteMenuItem,
                ))
            }

            // Desktop site
            if (canChangeBrowsingMode) {
                itemList.add(MenuItem(
                    if (isDesktopBrowsingMode) RR.drawable.ic_device_mobile_16 else RR.drawable.ic_device_desktop_16,
                    getString(
                        if (isDesktopBrowsingMode) R.string.requestMobileSiteMenuTitle else R.string.requestDesktopSiteMenuTitle,
                    ),
                    R.id.changeBrowserModeMenuItem,
                ))
            }

            // Find in page
            if (canFindInPage)
                itemList.add(MenuItem(RR.drawable.ic_find_search_16, getString(R.string.findInPageMenuTitle), R.id.findInPageMenuItem))

            // Open in app
            if (previousAppLink != null)
                itemList.add(MenuItem(RR.drawable.ic_open_in_app_android_alt_16, getString(R.string.appLinkMenuItemTitle), R.id.openInAppMenuItem))

            // Print page
            if (canPrintPage && !displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_print_16, getString(R.string.printMenuTitle), R.id.printPageMenuItem))

            // Add to home
            if (addToHomeVisible && addToHomeEnabled && !displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_add_to_home_16, getString(R.string.addToHome), R.id.addToHomeMenuItem))

            // Privacy protection
            if (canChangePrivacyProtection) {
                itemList.add(MenuItem(
                    if (isPrivacyProtectionDisabled) RR.drawable.ic_protections_16 else RR.drawable.ic_protections_blocked_16,
                    getString(
                        if (isPrivacyProtectionDisabled) R.string.enablePrivacyProtection else R.string.disablePrivacyProtection,
                    ),
                    R.id.privacyProtectionMenuItem,
                ))
            }

            // Feedback
            if (canReportSite && !displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_feedback_16, getString(R.string.brokenSiteReportBrokenSiteMenuItem), R.id.brokenSiteMenuItem))

            // Password
            if (showAutofill && !displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_key_16, getString(RRR.string.autofillManagementScreenTitle), R.id.autofillMenuItem))

            // Download
            if (!displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_downloads_16, getString(R.string.downloadsMenuTitle), R.id.downloadsMenuItem))

            // Settings
            if (!displayedInCustomTabScreen)
                itemList.add(MenuItem(RR.drawable.ic_settings_16, getString(R.string.settingsMenuItemTitle), R.id.settingsMenuItem))

        }*/

        binding.rvMenu.adapter = MenuRvAdapter(itemList) {
            val intent = Intent()
            intent.putExtra("menu_id", it.menuId)

            requireActivity().apply {
                setResult(RESULT_OK, intent)
                finish()
            }
        }
    }

    companion object {
        private const val DISPLAYS_IN_CUSTOM_TAB_SCREEN = "displayedInCustomTabScreen"

        fun newInstance(displayedInCustomTabScreen: Boolean): MenuFragment {
            return MenuFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(DISPLAYS_IN_CUSTOM_TAB_SCREEN, displayedInCustomTabScreen)
                }
            }
        }
    }
}
