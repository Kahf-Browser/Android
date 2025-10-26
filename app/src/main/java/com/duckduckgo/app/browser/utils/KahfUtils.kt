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

package com.duckduckgo.app.browser.utils

import com.duckduckgo.history.api.HistoryEntry
import kotlin.collections.component1
import kotlin.collections.component2

import android.net.Uri
import java.time.LocalDateTime

fun extractBaseDomain(host: String): String {
    if (host.isBlank()) return host
    val parts = host.split(".")
    return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
}

fun buildMostVisitedSites(
    historyList: List<HistoryEntry>,
    maxItems: Int = 10
): List<HistoryEntry> {

    // Group by base domain (merge subdomains)
    val groupedByDomain = historyList.groupBy { extractBaseDomain(it.url.host.orEmpty()) }

    return groupedByDomain.mapNotNull { (domain, entries) ->
        if (domain.isBlank()) return@mapNotNull null

        // Count visits per URL within this domain
        val urlFrequency = entries.associateBy(
            keySelector = { it.url },
            valueTransform = { it.visits.size }
        )

        // Find the URL with the highest number of visits
        val topUrlEntry = urlFrequency.maxByOrNull { it.value } ?: return@mapNotNull null
        val topUrl = topUrlEntry.key

        // Find the corresponding HistoryEntry
        val topEntry = entries.firstOrNull { it.url == topUrl } ?: return@mapNotNull null

        // Calculate total visits for the domain
        val totalVisits = entries.sumOf { it.visits.size }

        // Create a new HistoryEntry with all visits from this domain (optional choice)
        val combinedVisits = entries.flatMap { it.visits }.sortedDescending()

        // Return a new HistoryEntry.VisitedPage instance (merged summary)
        HistoryEntry.VisitedPage(
            url = topUrl,
            title = topEntry.title,
            visits = combinedVisits
        ) to totalVisits // temporarily pair with total visits for sorting
    }
        // Sort by total visits descending
        .sortedByDescending { it.second }
        // Take top N
        .take(maxItems)
        // Extract HistoryEntry only
        .map { it.first }
}
