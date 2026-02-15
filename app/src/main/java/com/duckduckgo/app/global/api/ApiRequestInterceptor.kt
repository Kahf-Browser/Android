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

package com.duckduckgo.app.global.api

import android.content.Context
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.common.utils.AppUrl
import com.duckduckgo.user.agent.api.UserAgentProvider
import okhttp3.Interceptor
import okhttp3.Response

class ApiRequestInterceptor(
    context: Context,
    private val userAgentProvider: UserAgentProvider,
    private val appBuildConfig: AppBuildConfig,
) : Interceptor {

    private val userAgent: String by lazy {
        "ddg_android/${appBuildConfig.versionName} (${context.applicationInfo.packageName}; Android API ${android.os.Build.VERSION.SDK_INT})"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()

        val url = chain.request().url.toString()
        if (url.startsWith("${AppUrl.Url.PIXEL}/t/rq_")) {
            // user agent for re-query pixels needs to be the same for the webview
            request.addHeader(Header.USER_AGENT, userAgentProvider.userAgent())
        } else if (isInternalUrl(url)) {
            request.addHeader(Header.USER_AGENT, userAgent)
        } else {
            // For external URLs (e.g. file downloads from CDNs), use a proper browser
            // User-Agent so that bot-protection / anti-hotlink systems accept the request.
            request.addHeader(Header.USER_AGENT, userAgentProvider.userAgent(url))
        }

        return chain.proceed(request.build())
    }

    private fun isInternalUrl(url: String): Boolean {
        return url.startsWith(AppUrl.Url.API) ||
            url.startsWith(AppUrl.Url.PIXEL) ||
            url.contains("duckduckgo.com") ||
            url.contains("kahfbrowser.com")
    }
}
