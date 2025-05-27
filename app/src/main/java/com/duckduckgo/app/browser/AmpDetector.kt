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

package com.duckduckgo.app.browser

import androidx.core.net.toUri
import javax.inject.Inject

class AmpDetector @Inject constructor() {

    fun isAmpUrl(url: String) =
        url.contains("cdn.ampproject.org") && url.toUri().host != "cdn.ampproject.org"

    fun extractOriginalUrlFromAmp(ampUrl: String): String {
        return try {
            val uri = ampUrl.toUri()

            // Handle cdn.ampproject.org format
            if (uri.host?.contains("cdn.ampproject.org") == true) {
                val pathSegments = uri.pathSegments
                val index = pathSegments.indexOf("v")
                if (index != -1 && pathSegments.size > index + 2) {
                    val isSecure = pathSegments[index + 1] == "s"
                    val originalUrlPath = pathSegments.subList(index + 2, pathSegments.size).joinToString("/")
                    val scheme = if (isSecure) "https://" else "http://"
                    return scheme + originalUrlPath
                }
            }

            // Handle Google AMP format
            if (uri.host?.contains("google.com") == true && uri.path?.contains("/amp/") == true) {
                val path = uri.path ?: return ampUrl
                val prefix = "/amp/s/"
                return if (path.contains(prefix)) {
                    "https://" + path.substringAfter(prefix)
                } else {
                    "http://" + path.substringAfter("/amp/")
                }
            }

            // Fallback
            ampUrl
        } catch (e: Exception) {
            ampUrl // fallback
        }
    }
}
