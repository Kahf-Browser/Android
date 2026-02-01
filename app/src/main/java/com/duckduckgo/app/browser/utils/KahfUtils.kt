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
import android.content.Context
import android.provider.Settings

fun extractBaseDomain(host: String): String {
    if (host.isBlank()) return host
    val parts = host.split(".")
    return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
}

fun buildMostVisitedSites(
    historyList: List<HistoryEntry>,
    maxItems: Int = 10
): List<HistoryEntry> {
    // Define default social media sites
    val now = LocalDateTime.now()
    val defaultSites = listOf(
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.hikmah.net"),
            title = "Hikmah",
            visits = List(7) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.mahfil.net"),
            title = "Mahfil",
            visits = List(6) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.facebook.com"),
            title = "Facebook",
            visits = List(5) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://x.com"),
            title = "X",
            visits = List(4) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.instagram.com"),
            title = "Instagram",
            visits = List(3) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.youtube.com"),
            title = "YouTube",
            visits = List(2) { now.minusDays(it.toLong()) }
        ),
        HistoryEntry.VisitedPage(
            url = Uri.parse("https://www.linkedin.com"),
            title = "LinkedIn",
            visits = List(1) { now.minusDays(it.toLong()) }
        )
    )

    // Get existing domains from history
    val existingDomains = historyList.map { extractBaseDomain(it.url.host.orEmpty()) }.toSet()

    // Add default sites that don't exist in history yet
    val defaultsToAdd = defaultSites.filter { defaultSite ->
        val defaultDomain = extractBaseDomain(defaultSite.url.host.orEmpty())
        defaultDomain !in existingDomains
    }

    // Merge history with default sites
    val mergedHistory = historyList + defaultsToAdd

    // Group by base domain (merge subdomains)
    val groupedByDomain = mergedHistory.groupBy { extractBaseDomain(it.url.host.orEmpty()) }

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

fun getNavigationMode(context: Context): Int {
    return try {
        Settings.Secure.getInt(context.contentResolver, "navigation_mode")
    } catch (e: Settings.SettingNotFoundException) {
        0 // Default to 3-button navigation if setting not found
    }
}

fun isGestureNavigation(context: Context): Boolean {
    return getNavigationMode(context) == 2
}

fun getNavigationBarHeight(context: Context): Int {
    val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
}

