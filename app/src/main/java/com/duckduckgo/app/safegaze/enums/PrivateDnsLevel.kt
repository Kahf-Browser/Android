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

package com.duckduckgo.app.safegaze.enums

import android.content.SharedPreferences
import com.duckduckgo.common.utils.KAHF_GUARD_DEFAULT
import com.duckduckgo.common.utils.KAHF_GUARD_INTENSITY

sealed class PrivateDnsLevel(val name: String, val url: String) {
    data object High : PrivateDnsLevel("High", "high.kahfguard.com")
    data object Medium : PrivateDnsLevel("Medium", "medium.kahfguard.com")
    data object Low : PrivateDnsLevel("Low", "low.kahfguard.com")
    data object Off : PrivateDnsLevel("Off", "dns.google")

    companion object {
        fun get(name: String) = when (name) {
            "High" -> High
            "Medium" -> Medium
            "Low" -> Low
            else -> Off
        }

        fun getCurrentLevel(pref: SharedPreferences): PrivateDnsLevel {
            val currentMode = pref.getString(KAHF_GUARD_INTENSITY, KAHF_GUARD_DEFAULT) ?: KAHF_GUARD_DEFAULT
            return get(currentMode)
        }

        fun updateLevel(pref: SharedPreferences, level: PrivateDnsLevel) {
            pref.edit().putString(KAHF_GUARD_INTENSITY, level.name).apply()
        }

        fun isEnabled(name: String) = get(name) != Off
    }
}
