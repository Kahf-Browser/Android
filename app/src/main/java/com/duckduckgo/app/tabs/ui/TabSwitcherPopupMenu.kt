/*
 * Copyright (c) 2022 DuckDuckGo
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

package com.duckduckgo.app.tabs.ui

import android.content.Context
import android.view.LayoutInflater
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.PopupWindowTabSwitcherBinding
import com.duckduckgo.common.ui.menu.PopupMenu
import com.duckduckgo.mobile.android.R.dimen

class TabSwitcherPopupMenu(
    context: Context,
    layoutInflater: LayoutInflater,
) : PopupMenu(
    layoutInflater,
    resourceId = R.layout.popup_window_tab_switcher,
    width = context.resources.getDimensionPixelSize(dimen.popupMenuWidth),
) {
    private val binding = PopupWindowTabSwitcherBinding.inflate(layoutInflater)

    init {
        contentView = binding.root
    }
}
