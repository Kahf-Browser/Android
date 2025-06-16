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

package com.duckduckgo.app.ads

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.app.browser.BrowserTabFragment.Companion.COOLDOWN_DURATION
import com.duckduckgo.app.browser.BrowserTabFragment.Companion.DISPLAY_DURATION
import com.duckduckgo.app.browser.BrowserTabFragment.Companion.HIDE_DURATION
import com.duckduckgo.app.browser.BrowserTabFragment.Companion.INITIAL_DELAY
import com.duckduckgo.app.browser.BrowserTabFragment.Companion.SHOW_DURATION
import com.kahfads.sdk.KahfAdConfig
import com.kahfads.sdk.KahfAdType

class AdsManager(val context: Context) : DefaultLifecycleObserver {

    // Animation and timing control
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var cooldownRunnable: Runnable? = null

    // Bottom Ads view State management
    private var isAdsVisible = false
    private var isInCooldown = false
    private var isAnimating = false
    // private var isFirstTimeScrolling =

    private var adsCommunicator: AdsCommunicator? = null

    fun setupBottomSlidableKahfAdsView(
        adsView: ImageView,
        rootContainer: ConstraintLayout
    ) {
        // Initially position overlay below screen
        adsView.post {
            val screenHeight = rootContainer.height
            adsView.translationY = screenHeight.toFloat()
            adsView.visibility = INVISIBLE
        }
    }

    fun setupEndSlidableKahfAdsView(
        adsView: ImageView,
        rootContainer: ConstraintLayout
    ) {
        // Initially position overlay end of screen
        adsView.post {
            val screenWidth = rootContainer.width
            adsView.translationX = screenWidth.toFloat()
            adsView.visibility = INVISIBLE
        }
    }

    fun setAdsManagerCommunicator(adsCommunicator: AdsCommunicator) {
        this.adsCommunicator = adsCommunicator
    }

    fun handleScrollDetected(adsView: ImageView, adsSlidingDirection: AdsSlidingDirection) {
        // Only show if conditions are met
        if (!isInCooldown && !isAdsVisible && !isAnimating) {
            if (adsSlidingDirection == AdsSlidingDirection.END) {
                showKahfEndAdsViewWithAnim(adsView = adsView)
            } else if (adsSlidingDirection == AdsSlidingDirection.DOWN) {
                showKahfBottomAdsViewWithAnim(adsView = adsView)
            }
        }
    }

    fun handleBottomNavAndWebViewTransition(deltaY: Int) {
    }

    private fun showKahfBottomAdsViewWithAnim(adsView: ImageView) {
        if (isAdsVisible || isAnimating) return

        adsCommunicator?.resumeAd()
        isAnimating = true
        isAdsVisible = true

        // Cancel any pending hide operation
        hideRunnable?.let { handler.removeCallbacks(it) }

        Handler(Looper.myLooper() ?: Looper.getMainLooper()).postDelayed(
            kotlinx.coroutines.Runnable {
                // Smooth slide up animation with bounce effect
                adsView.visibility = VISIBLE
                val animator = ObjectAnimator.ofFloat(
                    adsView, "translationY",
                    adsView.translationY, 0f,
                )
                animator.apply {
                    duration = SHOW_DURATION
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                showKahfAds(
                                    kahfAdsView = adsView,
                                    config = KahfAdConfig(
                                        adType = KahfAdType.BANNER_AD_640_200,
                                        divId = "home_banner",
                                        screenName = "HomeView",
                                        refreshRateInMillis = 20_000,
                                    ),
                                )
                                scheduleHideOverlay(adsView = adsView, direction = AdsSlidingDirection.DOWN)
                            }
                        },
                    )
                    start()
                }

                // Add subtle fade in for better UX
                adsView.alpha = 0.8f
                adsView.animate()
                    .alpha(1.0f)
                    .setDuration(SHOW_DURATION)
                    .start()
            },
            INITIAL_DELAY,/*if (isFirstTimeScrolling) INITIAL_DELAY else 0L*/
        )
    }

    private fun showKahfEndAdsViewWithAnim(adsView: ImageView) {
        if (isAdsVisible || isAnimating) return

        adsCommunicator?.resumeAd()
        isAnimating = true
        isAdsVisible = true

        // Cancel any pending hide operation
        hideRunnable?.let { handler.removeCallbacks(it) }

        Handler(Looper.myLooper() ?: Looper.getMainLooper()).postDelayed(
            kotlinx.coroutines.Runnable {
                val gapFromEdge = 6.dpToPx()
                // Smooth slide up animation with bounce effect
                adsView.visibility = VISIBLE
                val animator = ObjectAnimator.ofFloat(
                    adsView, "translationX",
                    adsView.translationX, 0f - gapFromEdge,
                )
                animator.apply {
                    duration = SHOW_DURATION
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                showKahfAds(
                                    kahfAdsView = adsView,
                                    config = KahfAdConfig(
                                        adType = KahfAdType.BANNER_AD_640_200,
                                        divId = "home_banner",
                                        screenName = "HomeView",
                                        refreshRateInMillis = 20_000,
                                    ),
                                )
                                scheduleHideOverlay(adsView = adsView, direction = AdsSlidingDirection.END)
                            }
                        },
                    )
                    start()
                }

                // Add subtle fade in for better UX
                adsView.alpha = 0.8f
                adsView.animate()
                    .alpha(1.0f)
                    .setDuration(SHOW_DURATION)
                    .start()
            },
            INITIAL_DELAY,/*if (isFirstTimeScrolling) INITIAL_DELAY else 0L*/
        )
    }

    private fun automaticallyHideKahfBottomAdsView(adsView: ImageView) {
        if (!isAdsVisible || isAnimating) return

        isAnimating = true

        // Smooth slide down animation
        val animator = ObjectAnimator.ofFloat(
            adsView, "translationY",
            0f, adsView.height.toFloat() + 50f,
        ) // Extra 50px for complete hide
        animator.apply {
            duration = HIDE_DURATION
            interpolator = android.view.animation.AccelerateInterpolator()
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimating = false
                        isAdsVisible = false
                        adsView.visibility = INVISIBLE
                        adsCommunicator?.pauseAd()
                        startCooldownPeriod(adsView = adsView)
                    }
                },
            )
            start()
        }

        // Add fade out effect
        adsView.animate()
            .alpha(0.6f)
            .setDuration(HIDE_DURATION)
            .start()
    }

    private fun automaticallyHideKahfEndAdsView(adsView: ImageView) {
        if (!isAdsVisible || isAnimating) return

        isAnimating = true

        // Smooth slide down animation
        val animator = ObjectAnimator.ofFloat(
            adsView, "translationX",
            0f, adsView.width.toFloat() + 50f,
        ) // Extra 50px for complete hide
        animator.apply {
            duration = HIDE_DURATION
            interpolator = android.view.animation.AccelerateInterpolator()
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimating = false
                        isAdsVisible = false
                        adsView.visibility = INVISIBLE
                        adsCommunicator?.pauseAd()
                        startCooldownPeriod(adsView = adsView)
                    }
                },
            )
            start()
        }

        // Add fade out effect
        adsView.animate()
            .alpha(0.6f)
            .setDuration(HIDE_DURATION)
            .start()
    }

    private fun closeKahfAdsView(
        adsView: ImageView,
        direction: AdsSlidingDirection
    ) {
        // Cancel scheduled hide and hide immediately
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideAdBasedOnDirection(
            adsView = adsView,
            direction = direction,
        )
    }

    private fun scheduleHideOverlay(
        adsView: ImageView,
        direction: AdsSlidingDirection
    ) {
        hideRunnable = kotlinx.coroutines.Runnable {
            hideAdBasedOnDirection(
                adsView = adsView,
                direction = direction,
            )
        }
        handler.postDelayed(hideRunnable!!, DISPLAY_DURATION)
    }

    private fun hideAdBasedOnDirection(
        adsView: ImageView,
        direction: AdsSlidingDirection
    ) {
        when (direction) {
            AdsSlidingDirection.UP -> {
                automaticallyHideKahfBottomAdsView(adsView = adsView)
            }

            AdsSlidingDirection.DOWN -> {
                automaticallyHideKahfBottomAdsView(adsView = adsView)
            }

            AdsSlidingDirection.START -> {
                automaticallyHideKahfEndAdsView(adsView = adsView)
            }

            AdsSlidingDirection.END -> {
                automaticallyHideKahfEndAdsView(adsView = adsView)
            }

            else -> {
            }
        }
    }

    private fun startCooldownPeriod(adsView: ImageView) {
        isInCooldown = true
        cooldownRunnable = kotlinx.coroutines.Runnable {
            isInCooldown = false
            // isFirstTimeScrolling = false
            // Reset overlay position for next show
            adsView.post {
                adsView.translationY = adsView.height.toFloat() + 50f
                adsView.alpha = 1.0f
            }
        }
        handler.postDelayed(cooldownRunnable!!, COOLDOWN_DURATION)
    }

    private fun showKahfAds(
        kahfAdsView: ImageView,
        config: KahfAdConfig
    ) {
        /*kahfAdsView.apply {
            loadAd(config)
            setAdClickListener {
                closeKahfAdsSlidingView()
                viewModel.onUserSubmittedQuery(it)
            }
            setAdImpressionListener(
                object : AdImpressionListener {
                    override fun onAdClicked() {
                        Timber.i("adLog onAdClicked")
                        analyticsService.logEvent(AnalyticsEvent.BannerAdClicked)
                    }

                    override fun onAdFailedToLoad(error: Error) {
                        Timber.i("adLog onAdFailedToLoad ${error.message}")
                        when (error.type) {
                            ErrorType.TIMEOUT -> {
                                analyticsService.logEvent(AnalyticsEvent.AdTimeout)
                            }

                            ErrorType.NO_AD_FOUND -> {
                                analyticsService.logEvent(AnalyticsEvent.AdNotFound)
                            }

                            ErrorType.SERVER_ERROR -> {
                                analyticsService.logEvent(AnalyticsEvent.AdServerError)
                            }

                            else -> {
                                // No op
                            }
                        }
                    }

                    override fun onAdLoaded() {
                        if (webView?.isVisible != false) {
                            Timber.d("adLog ad loaded but webView is visible. Pause ad refresh $tabId")
                            viewModel.pauseAdRefresh()
                        } else {
                            Timber.i("adLog onAdLoaded $tabId")
                            analyticsService.logEvent(AnalyticsEvent.BannerAdImpression)
                        }
                    }
                },
            )
        }*/
    }

    fun removeHandlers() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        cooldownRunnable?.let { handler.removeCallbacks(it) }
    }

    fun setupCloseButton() {
        // closeButton.setOnClickListener {
        //     manualHideOverlay()
        // }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        adsCommunicator = null
        super.onDestroy(owner)
    }

    enum class AdsSlidingDirection {
        UP,
        DOWN,
        START,
        END,
        UNKNOWN
    }

    private fun Int.dpToPx(): Float {
        return this * context.resources.displayMetrics.density
    }
}
