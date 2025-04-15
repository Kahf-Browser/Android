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

package com.duckduckgo.app.browser.omnibar

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.duckduckgo.app.browser.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.animation.ValueAnimator
import com.duckduckgo.app.browser.omnibar.ScrollAwareBottomNavigationViewBehavior.Companion.DURATION

class ScrollAwareBottomNavigationViewBehavior(
    context: Context,
    attrs: AttributeSet
) : CoordinatorLayout.Behavior<BottomNavigationView>(context, attrs) {

    companion object {
        const val DURATION = 100L
    }

    private var webViewContainer: View? = null

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: BottomNavigationView,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        webViewContainer = webViewContainer ?: coordinatorLayout.findViewById(R.id.webViewContainer)
        return axes == ViewCompat.SCROLL_AXIS_VERTICAL
    }

    override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: BottomNavigationView,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        if (dyConsumed > 0) {
            showBottomNav(child)
        } else if (dyConsumed < 0 && child.isShown) {
            hideBottomNav(child)
        }
    }

    fun hideBottomNav(navBar: BottomNavigationView) {
        webViewContainer?.let {
            val layoutParams = it.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            val currentMargin = layoutParams.bottomMargin

            // Don't animate if margin is already 0
            if (currentMargin <= 0) return@let

            ValueAnimator.ofInt(currentMargin, 0).apply {
                duration = DURATION
                addUpdateListener { animator ->
                    layoutParams.bottomMargin = animator.animatedValue as Int
                    it.layoutParams = layoutParams
                }
                start()
            }
        }
        navBar.animate().translationY(navBar.height.toFloat()).setDuration(DURATION)
    }

    fun showBottomNav(navBar: BottomNavigationView) {
        webViewContainer?.let {
            val layoutParams = it.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            val currentMargin = layoutParams.bottomMargin
            val targetMargin = navBar.height

            // Don't animate if margin is already at target value
            if (currentMargin >= targetMargin) return@let

            ValueAnimator.ofInt(currentMargin, targetMargin).apply {
                duration = DURATION
                addUpdateListener { animator ->
                    layoutParams.bottomMargin = animator.animatedValue as Int
                    it.layoutParams = layoutParams
                }
                start()
            }
        }
        navBar.animate().translationY(0f).setDuration(DURATION)
    }
}

fun BottomNavigationView.hideIt() {
    val behavior = (layoutParams as? CoordinatorLayout.LayoutParams)?.behavior
    if (behavior is ScrollAwareBottomNavigationViewBehavior) {
        behavior.hideBottomNav(this)
    } else {
        animate().translationY(height.toFloat()).setDuration(DURATION)
    }
}

fun BottomNavigationView.showIt() {
    val behavior = (layoutParams as? CoordinatorLayout.LayoutParams)?.behavior
    if (behavior is ScrollAwareBottomNavigationViewBehavior) {
        behavior.showBottomNav(this)
    } else {
        animate().translationY(0f).setDuration(DURATION)
    }
}
