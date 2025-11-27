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

package com.duckduckgo.app.browser.safebrowsing

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.ViewSafeBrowsingWarningBannerBinding
import com.duckduckgo.safebrowsing.api.ThreatType

/**
 * Custom view for displaying Safe Browsing threat warnings
 *
 * Shows a prominent banner when a dangerous site is detected,
 * with options to go back or proceed anyway.
 */
class SafeBrowsingWarningBanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: ViewSafeBrowsingWarningBannerBinding

    var onGoBackClicked: (() -> Unit)? = null
    var onProceedAnywayClicked: (() -> Unit)? = null
    var onCloseClicked: (() -> Unit)? = null

    init {
        binding = ViewSafeBrowsingWarningBannerBinding.inflate(
            LayoutInflater.from(context),
            this,
            true
        )

        setupClickListeners()
        visibility = View.GONE
    }

    private fun setupClickListeners() {
        binding.closeButton.setOnClickListener {
            onCloseClicked?.invoke()
            hide()
        }
    }

    /**
     * Show the warning banner with threat information
     *
     * @param threatType The type of threat detected
     * @param url The URL that was flagged (optional, for logging)
     */
    fun show(threatType: ThreatType, url: String? = null) {
        // Set threat-specific description
        val descriptionText = when (threatType) {
            ThreatType.PHISHING -> context.getString(R.string.safe_browsing_threat_phishing)
            ThreatType.MALWARE -> context.getString(R.string.safe_browsing_threat_malware)
            ThreatType.UNWANTED_SOFTWARE -> context.getString(R.string.safe_browsing_threat_unwanted_software)
            ThreatType.UNKNOWN -> context.getString(R.string.safe_browsing_threat_unknown)
        }
        binding.threatDescription.text = descriptionText

        // Animate banner sliding down
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = -height.toFloat()

            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /**
     * Hide the warning banner with animation
     */
    fun hide() {
        if (visibility == View.VISIBLE) {
            animate()
                .alpha(0f)
                .translationY(-height.toFloat())
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    visibility = View.GONE
                }
                .start()
        }
    }

    /**
     * Hide immediately without animation
     */
    fun hideImmediate() {
        visibility = View.GONE
        clearAnimation()
    }
}
