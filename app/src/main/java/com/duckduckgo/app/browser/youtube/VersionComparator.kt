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

package com.duckduckgo.app.browser.youtube

import timber.log.Timber

/**
 * Utility for comparing semantic version strings (e.g., "1.2.3")
 *
 * Semantic versioning format: MAJOR.MINOR.PATCH
 * - MAJOR: Incompatible API changes
 * - MINOR: Backwards-compatible functionality additions
 * - PATCH: Backwards-compatible bug fixes
 */
object VersionComparator {

    /**
     * Compare two version strings
     *
     * @param version1 First version string (e.g., "1.2.3")
     * @param version2 Second version string (e.g., "1.2.4")
     * @return -1 if version1 < version2, 0 if equal, 1 if version1 > version2
     *
     * Examples:
     * - compare("1.2.3", "1.2.4") returns -1
     * - compare("1.2.3", "1.2.3") returns 0
     * - compare("2.0.0", "1.9.9") returns 1
     */
    fun compare(version1: String, version2: String): Int {
        try {
            val v1Parts = version1.split(".").map { it.toIntOrNull() ?: 0 }
            val v2Parts = version2.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(v1Parts.size, v2Parts.size)

            for (i in 0 until maxLength) {
                val v1Part = v1Parts.getOrElse(i) { 0 }
                val v2Part = v2Parts.getOrElse(i) { 0 }

                when {
                    v1Part < v2Part -> return -1
                    v1Part > v2Part -> return 1
                }
            }

            return 0
        } catch (e: Exception) {
            Timber.e(e, "Error comparing versions: $version1 vs $version2")
            return 0
        }
    }

    /**
     * Check if version1 is greater than version2
     *
     * @param version1 First version string
     * @param version2 Second version string
     * @return true if version1 > version2
     */
    fun isGreaterThan(version1: String, version2: String): Boolean {
        return compare(version1, version2) > 0
    }

    /**
     * Check if a version string is valid
     *
     * @param version Version string to validate
     * @return true if valid semantic version format
     */
    fun isValid(version: String): Boolean {
        return try {
            val parts = version.split(".")
            parts.size in 1..3 && parts.all { it.toIntOrNull() != null }
        } catch (e: Exception) {
            false
        }
    }
}
