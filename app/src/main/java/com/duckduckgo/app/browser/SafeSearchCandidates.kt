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

package com.duckduckgo.app.browser

import com.duckduckgo.common.utils.AppUrl

private val ssCandidateGoogle = SafeSearchCandidate("google.com", pathContains = listOf("search"), queryParam = "q", exclude = listOf("captcha", "accounts.google.com/", "${AppUrl.ParamKey.SAFE}=strict"))

val safeSearchCandidates = listOf(
    ssCandidateGoogle,
    ssCandidateGoogle.copy(domain = "google.de"),
    ssCandidateGoogle.copy(domain = "google.ca"),
    ssCandidateGoogle.copy(domain = "google.pl"),
    ssCandidateGoogle.copy(domain = "google.co.uk"),

    SafeSearchCandidate("bing.com", pathContains = listOf("search", "account/general"), queryParam = "q"),
    SafeSearchCandidate("ecosia.org", queryParam = "q"),
    SafeSearchCandidate("duckduckgo.com", queryParam = "q", exclude = listOf("ia=chat", "duckchat")),
    SafeSearchCandidate("ask.com", queryParam = "q"),
    SafeSearchCandidate("search.yahoo.com", queryParam = "p"), // intentionally kept 'p'
    SafeSearchCandidate("search.brave.com", queryParam = ""), // not working even in system level private DNS
    // SafeSearchCandidate("youtube.com", exclude = listOf("youtubei/v1", "accounts.youtube.com/")),
)
