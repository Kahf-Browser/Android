package com.duckduckgo.app.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import timber.log.Timber

class FirebaseAnalyticsService(private val firebaseAnalytics: FirebaseAnalytics) : AnalyticsService {

    override fun logEvent(
        event: AnalyticsEvent,
        params: Map<AnalyticsParam, String>?
    ) {
        if (!isLoggingEnabled) {
            return
        }

        val bundle = Bundle()
        params?.forEach { (key, value) ->
            if (value == "true" || value == "false") {
                bundle.putBoolean(key.name, value.toBoolean())
                return@forEach
            }
            if (value.toDoubleOrNull() != null) {
                bundle.putDouble(key.name, value.toDouble())
                return@forEach
            }
            if (value.toLongOrNull() != null) {
                bundle.putLong(key.name, value.toLong())
                return@forEach
            }
            if (value.toIntOrNull() != null) {
                bundle.putInt(key.name, value.toInt())
                return@forEach
            }
            bundle.putString(key.name, value)
        }
        firebaseAnalytics.logEvent(event.name, bundle)

        Timber.d("AnalyticsService -- successfully logged event: ${event.name}")
    }

    override fun setUserProperty(
        propertyName: String,
        value: String
    ) {
        if (!isLoggingEnabled) {
            return
        }

        firebaseAnalytics.setUserProperty(propertyName, value)
    }

    override fun setUserId(userId: String) {
        if (!isLoggingEnabled) {
            return
        }

        firebaseAnalytics.setUserId(userId)
    }
}
