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

package com.duckduckgo.app.browser.safebrowsing.di

import com.duckduckgo.app.browser.safebrowsing.AppSafeBrowsingApiKeyProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.safebrowsing.impl.SafeBrowsingApiKeyProvider
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn

/**
 * Dagger module for app-specific Safe Browsing dependencies
 */
@Module
@ContributesTo(AppScope::class)
class AppSafeBrowsingModule {

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideSafeBrowsingApiKeyProvider(): SafeBrowsingApiKeyProvider {
        return AppSafeBrowsingApiKeyProvider()
    }
}
