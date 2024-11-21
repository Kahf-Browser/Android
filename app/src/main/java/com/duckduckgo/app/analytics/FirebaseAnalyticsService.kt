package com.duckduckgo.app.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import timber.log.Timber

class FirebaseAnalyticsService(private val firebaseAnalytics: FirebaseAnalytics) : AnalyticsService {

    override fun logEvent(
        eventName: String,
        params: Map<String, String>?
    ) {
        if (!isLoggingEnabled) {
            return
        }

        val bundle = Bundle()
        params?.forEach { (key, value) ->
            bundle.putString(key, value)
        }
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.ADD_TO_CART, bundle)

        Timber.d("analog -- successfully logged event: $eventName")
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
