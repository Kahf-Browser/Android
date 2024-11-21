package com.duckduckgo.app.analytics

interface AnalyticsService {
    val isLoggingEnabled: Boolean
        get() = true

    fun logEvent(event: AnalyticsEvent, params: Map<AnalyticsParam, String>? = null)
    fun setUserProperty(propertyName: String, value: String)
    fun setUserId(userId: String)
}
