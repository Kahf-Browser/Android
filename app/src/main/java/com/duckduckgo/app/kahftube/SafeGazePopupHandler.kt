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

package com.duckduckgo.app.kahftube

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.SafeGazePopupBinding
import com.duckduckgo.app.kahftube.enums.PrivateDnsLevel
import com.duckduckgo.app.kahftube.enums.SafeGazeLevel
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistDao
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistEntity
import com.duckduckgo.common.ui.view.scaleIndependentTextSize
import com.duckduckgo.common.utils.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SafeGazePopupHandler(
    private val binding: SafeGazePopupBinding,
    private val currentUrl: String?,
    sharedPreferences: SharedPreferences,
    private val dispatcher: DispatcherProvider,
    private val sgWhitelistDao: SafeGazeWhitelistDao,
    onDnsModeChanged: (mode: PrivateDnsLevel) -> Unit,
    onSafeGazeModeChanged: (mode: SafeGazeLevel) -> Unit,
    onShareClicked: () -> Unit,
    onSupportClicked: () -> Unit,
    onThemeChanged: () -> Unit,
    onBlurEffectChanged: (sgLevel: SafeGazeLevel) -> Unit,
    onSgWhitelistUpdated: (host: String, isWhitelisted: Boolean) -> Unit,
) {
    init {
        var btnHigh: PopupButton? = null
        var btnMed: PopupButton? = null
        var btnLow: PopupButton? = null

        val preSelectedDns: PrivateDnsLevel = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
        val preSelectedSG: SafeGazeLevel = SafeGazeLevel.getCurrentLevel(sharedPreferences)

        btnHigh = PopupButton(
            binding.btnHigh,
            PrivateDnsLevel.High,
            preSelectedDns == PrivateDnsLevel.High,
        ) {
            btnHigh?.updateState(true)
            btnMed?.updateState(false)
            btnLow?.updateState(false)
            updateDescription(binding, PrivateDnsLevel.High)
            onDnsModeChanged(PrivateDnsLevel.High)
        }
        btnMed = PopupButton(
            binding.btnMedium,
            PrivateDnsLevel.Medium,
            preSelectedDns == PrivateDnsLevel.Medium,
        ) {
            btnHigh.updateState(false)
            btnMed?.updateState(true)
            btnLow?.updateState(false)
            updateDescription(binding, PrivateDnsLevel.Medium)
            onDnsModeChanged(PrivateDnsLevel.Medium)
        }

        btnLow = PopupButton(
            binding.btnLow,
            PrivateDnsLevel.Low,
            preSelectedDns == PrivateDnsLevel.Low,
        ) {
            btnHigh.updateState(false)
            btnMed.updateState(false)
            btnLow?.updateState(true)
            updateDescription(binding, PrivateDnsLevel.Low)
            onDnsModeChanged(PrivateDnsLevel.Low)
        }

        // set initially selected item
        updateDescription(binding, preSelectedDns)

        handleProgressBar()

        binding.btnShare.setOnClickListener { onShareClicked() }
        binding.btnSupport.setOnClickListener { onSupportClicked() }
        binding.btnTheme.setOnClickListener { onThemeChanged() }

        binding.switchKahdGuard.isChecked = preSelectedDns != PrivateDnsLevel.Off
        binding.switchSafeGaze.isChecked = preSelectedSG != SafeGazeLevel.Off
        binding.privateDnsGroup.isVisible = preSelectedDns != PrivateDnsLevel.Off

        // Set initially selected image blur effect
        binding.ivCheckGrey.isVisible = preSelectedSG == SafeGazeLevel.Blur
        binding.ivCheckPixelation.isVisible = preSelectedSG == SafeGazeLevel.Pixelation

        binding.ivBlurGreyContainer.setOnClickListener {
            onBlurEffectChanged(SafeGazeLevel.Blur)
            binding.ivCheckGrey.isVisible = true
            binding.ivCheckPixelation.isVisible = false
        }
        binding.ivBlurPixelationContainer.setOnClickListener {
            onBlurEffectChanged(SafeGazeLevel.Pixelation)
            binding.ivCheckGrey.isVisible = false
            binding.ivCheckPixelation.isVisible = true
        }

        // Toggle private dns (Kahf Guard)
        binding.switchKahdGuard.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                binding.privateDnsGroup.isVisible = false
                onDnsModeChanged(PrivateDnsLevel.Off)
            } else {
                binding.privateDnsGroup.isVisible = true
                btnHigh.performClick()
            }
        }

        // Toggle image blur (Safe Gaze)
        binding.switchSafeGaze.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                onSafeGazeModeChanged(SafeGazeLevel.Pixelation)
            } else {
                onSafeGazeModeChanged(SafeGazeLevel.Off)
            }
        }


        binding.btnToggleSiteBlur.let { btn->
            val host = currentUrl?.toUri()?.host ?: ""
            btn.isVisible = currentUrl != null && preSelectedSG != SafeGazeLevel.Off

            CoroutineScope(dispatcher.io()).launch {
                val isWhitelisted = sgWhitelistDao.isHostWhitelisted(host)

                withContext(dispatcher.main()) {
                    btn.text = btn.context.getString(
                        if (isWhitelisted) R.string.kahf_resume_blurring else R.string.kahf_pause_on_site,
                    )
                }
            }

            btn.setOnClickListener {
                CoroutineScope(dispatcher.io()).launch {
                    val isWhitelisted = sgWhitelistDao.isHostWhitelisted(host)
                    if (isWhitelisted) {
                        sgWhitelistDao.delete(host)
                    } else {
                        sgWhitelistDao.insert(SafeGazeWhitelistEntity(host))
                    }

                    withContext(dispatcher.main()) {
                        onSgWhitelistUpdated(host, false)
                    }
                }
            }
        }

        setFontSize()

        // Set build number
        binding.root.context.let {
            val versionName = it.packageManager.getPackageInfo(it.packageName, 0).versionName.toString()
            val buildNumber = it.packageManager.getPackageInfo(it.packageName, 0).versionCode.toString()
            binding.tvBuildNumber.text = it.getString(R.string.settingsVersionFull, versionName, buildNumber)
        }
    }

    private fun setFontSize() {
        with(binding) {
            // KahfGuard
            tvOnOff.scaleIndependentTextSize(14f)
            title.scaleIndependentTextSize(20f)
            tvDescription.scaleIndependentTextSize(14f)
            btnHigh.btnLabel.scaleIndependentTextSize(13f)
            btnMedium.btnLabel.scaleIndependentTextSize(13f)
            btnLow.btnLabel.scaleIndependentTextSize(13f)

            // SafeGaze
            tvOnOffImage.scaleIndependentTextSize(14f)
            tvDecent.scaleIndependentTextSize(18f)
            blueIndecentPhotosText.scaleIndependentTextSize(14f)
            btnToggleSiteBlur.scaleIndependentTextSize(12f)

            // Statistics
            statTitle.scaleIndependentTextSize(20f)
            siteBlockedCount.scaleIndependentTextSize(24f)
            imageBlurCount.scaleIndependentTextSize(24f)
            trackerBlockedCount.scaleIndependentTextSize(24f)
            adsBlockedLabel.scaleIndependentTextSize(14f)
            imageBlurLabel.scaleIndependentTextSize(14f)
            trackerBlockedLabel.scaleIndependentTextSize(14f)

            // Bottom buttons
            btnShare.scaleIndependentTextSize(13f)
            btnSupport.scaleIndependentTextSize(13f)
            btnTheme.scaleIndependentTextSize(13f)
            tvBuildNumber.scaleIndependentTextSize(11f)
        }
    }

    private fun updateDescription(binding: SafeGazePopupBinding, type: PrivateDnsLevel) {
        when (type) {
            PrivateDnsLevel.High -> {
                binding.tvDescription.text = binding.root.context.getString(R.string.kahf_mode_desc_high)
                binding.tvDescription.backgroundTintList =
                    ColorStateList.valueOf(
                        ContextCompat.getColor(binding.root.context, com.duckduckgo.mobile.android.R.color.kahf_green),
                    )
            }
            PrivateDnsLevel.Medium -> {
                binding.tvDescription.text = binding.root.context.getString(R.string.kahf_mode_desc_medium)
                binding.tvDescription.backgroundTintList =
                    ColorStateList.valueOf(
                        ContextCompat.getColor(binding.root.context, com.duckduckgo.mobile.android.R.color.kahf_orange),
                    )
            }
            PrivateDnsLevel.Low -> {
                binding.tvDescription.text = binding.root.context.getString(R.string.kahf_mode_desc_low)
                binding.tvDescription.backgroundTintList =
                    ColorStateList.valueOf(
                        ContextCompat.getColor(binding.root.context, com.duckduckgo.mobile.android.R.color.kahf_red),
                    )
            }
            else -> {
                // No op
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun handleProgressBar() {
        // No op
    }
}
