package com.duckduckgo.app.analytics

interface AnalyticsService {
    val isLoggingEnabled: Boolean
        get() = true

    fun logEvent(eventName: String, params: Map<String, String>? = null)
    fun setUserProperty(propertyName: String, value: String)
    fun setUserId(userId: String)
}
