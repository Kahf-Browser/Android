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

package com.duckduckgo.safebrowsing.api

/**
 * Represents the type of threat detected by Google Safe Browsing
 */
enum class ThreatType {
    /**
     * Phishing attack - attempts to steal credentials or sensitive information
     * Corresponds to SafeBrowsingThreat.TYPE_SOCIAL_ENGINEERING
     */
    PHISHING,

    /**
     * Malware or potentially harmful application
     * Corresponds to SafeBrowsingThreat.TYPE_POTENTIALLY_HARMFUL_APPLICATION
     */
    MALWARE,

    /**
     * Unwanted software
     */
    UNWANTED_SOFTWARE,

    /**
     * Unknown or unclassified threat
     */
    UNKNOWN
}
