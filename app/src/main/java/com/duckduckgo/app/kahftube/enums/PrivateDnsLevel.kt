package com.duckduckgo.app.kahftube.enums

import android.content.SharedPreferences
import com.duckduckgo.common.utils.KAHF_GUARD_DEFAULT
import com.duckduckgo.common.utils.KAHF_GUARD_INTENSITY

sealed class PrivateDnsLevel(val name: String, val url: String, val dnsServerIps: Array<String>) {
    data object High : PrivateDnsLevel("High", "high.kahfguard.com", arrayOf("51.142.0.101", "51.142.0.102"))
    data object Medium : PrivateDnsLevel("Medium", "medium.kahfguard.com", arrayOf("51.142.0.99", "51.142.0.100"))
    data object Low : PrivateDnsLevel("Low", "low.kahfguard.com", arrayOf("51.142.0.97", "51.142.0.98"))
    data object Off : PrivateDnsLevel("Off", "dns.google", arrayOf("8.8.8.8", "8.8.4.4"))

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

        fun isEnabled(name: String) = get(name) != Off
    }
}
