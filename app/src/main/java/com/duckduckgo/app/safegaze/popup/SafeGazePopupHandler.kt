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

package com.duckduckgo.app.safegaze.popup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.SafeGazePopupBinding
import com.duckduckgo.app.isAutoPlayVideoEnabled
import com.duckduckgo.app.isSgLockEnabled
import com.duckduckgo.app.safegaze.enums.PrivateDnsLevel
import com.duckduckgo.app.safegaze.enums.SafeGazeLevel
import com.duckduckgo.app.setAutoPlayVideoEnabled
import com.duckduckgo.app.setSgLockMode
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistDao
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistEntity
import com.duckduckgo.common.ui.view.scaleIndependentTextSize
import com.duckduckgo.common.utils.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class SafeGazePopupHandler(
    private val binding: SafeGazePopupBinding,
    private val currentUrl: String?,
    sharedPreferences: SharedPreferences,
    private val dispatcher: DispatcherProvider,
    private val sgWhitelistDao: SafeGazeWhitelistDao,
    onDnsModeChanged: (mode: PrivateDnsLevel) -> Unit,
    onSafeGazeModeChanged: (mode: SafeGazeLevel) -> Unit,
    onVideoBlurModeChanged: (mode: SafeGazeLevel) -> Unit,
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
        val preSelectedSG: SafeGazeLevel = SafeGazeLevel.getImageBlurLevel(sharedPreferences)
        val preSelectedVideoBlurLevel: SafeGazeLevel = SafeGazeLevel.getVideoBlurLevel(sharedPreferences, "init")

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

        binding.btnShare.setOnClickListener { onShareClicked() }
        binding.btnSupport.setOnClickListener { onSupportClicked() }
        binding.btnTheme.setOnClickListener { onThemeChanged() }

        binding.switchKahdGuard.isChecked = preSelectedDns != PrivateDnsLevel.Off
        binding.switchSafeGaze.isChecked = preSelectedSG != SafeGazeLevel.Off
        binding.switchVideoBlur.isChecked = preSelectedVideoBlurLevel != SafeGazeLevel.Off
        binding.privateDnsGroup.isVisible = preSelectedDns != PrivateDnsLevel.Off

        // Set initially selected image blur effect
        binding.ivCheckPixelationWithoutFaceBlur.isVisible = preSelectedSG == SafeGazeLevel.PixelationWithoutFaceBlur
        binding.ivCheckPixelationWithoutHeadBlur.isVisible = preSelectedSG == SafeGazeLevel.PixelationWithoutHeadBlur
        binding.ivCheckSolidWithFaceBlur.isVisible = preSelectedSG == SafeGazeLevel.SolidWithFaceBlur
        binding.ivCheckSolidWithoutFaceBlur.isVisible = preSelectedSG == SafeGazeLevel.SolidWithoutFaceBlur

        binding.ivPixelationWithoutFaceBlur.setOnClickListener {
            Log.d("SafeGazeLog", "click ivPixelationWithoutFaceBlur")
            onBlurEffectChanged(SafeGazeLevel.PixelationWithoutFaceBlur)
            binding.ivCheckPixelationWithoutFaceBlur.isVisible = true
            binding.ivCheckPixelationWithoutHeadBlur.isVisible = false
            binding.ivCheckSolidWithFaceBlur.isVisible = false
            binding.ivCheckSolidWithoutFaceBlur.isVisible = false
        }

        binding.ivPixelationWithoutHeadBlur.setOnClickListener {
            Log.d("SafeGazeLog", "click ivPixelationWithoutHeadBlur")
            onBlurEffectChanged(SafeGazeLevel.PixelationWithoutHeadBlur)
            binding.ivCheckPixelationWithoutFaceBlur.isVisible = false
            binding.ivCheckPixelationWithoutHeadBlur.isVisible = true
            binding.ivCheckSolidWithFaceBlur.isVisible = false
            binding.ivCheckSolidWithoutFaceBlur.isVisible = false
        }

        binding.ivSolidWithFaceBlur.setOnClickListener {
            Log.d("SafeGazeLog", "click ivSolidWithFaceBlur")
            onBlurEffectChanged(SafeGazeLevel.SolidWithFaceBlur)
            binding.ivCheckSolidWithFaceBlur.isVisible = true
            binding.ivCheckSolidWithoutFaceBlur.isVisible = false
            binding.ivCheckPixelationWithoutFaceBlur.isVisible = false
            binding.ivCheckPixelationWithoutHeadBlur.isVisible = false
        }

        binding.ivSolidWithoutFaceBlur.setOnClickListener {
            Log.d("SafeGazeLog", "click ivSolidWithoutFaceBlur")
            onBlurEffectChanged(SafeGazeLevel.SolidWithoutFaceBlur)
            binding.ivCheckSolidWithFaceBlur.isVisible = false
            binding.ivCheckSolidWithoutFaceBlur.isVisible = true
            binding.ivCheckPixelationWithoutFaceBlur.isVisible = false
            binding.ivCheckPixelationWithoutHeadBlur.isVisible = false
        }

        // Toggle private dns (Kahf Guard)
        binding.switchKahdGuard.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                binding.privateDnsGroup.isVisible = false
                onDnsModeChanged(PrivateDnsLevel.Off)
            } else {
                binding.privateDnsGroup.isVisible = true
                btnMed.performClick()
            }
        }

        // Toggle image blur (Safe Gaze)
        binding.switchSafeGaze.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                onSafeGazeModeChanged(SafeGazeLevel.PixelationWithoutFaceBlur)
            } else {
                onSafeGazeModeChanged(SafeGazeLevel.Off)
            }
        }
        binding.switchVideoBlur.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                onVideoBlurModeChanged(SafeGazeLevel.PixelationWithoutFaceBlur)
            } else {
                onVideoBlurModeChanged(SafeGazeLevel.Off)
            }
        }


        binding.btnPauseOnSite.let { btn->
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

        //Autoplay video
        binding.switchStopAutoplay.apply {
            isChecked = sharedPreferences.isAutoPlayVideoEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                sharedPreferences.setAutoPlayVideoEnabled(isChecked)
            }
        }

        // Toggle biometric lock
        binding.switchPreventTurningOff.isChecked = sharedPreferences.isSgLockEnabled()
        binding.switchPreventTurningOff.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.setSgLockMode(isChecked)
        }

        setFontSize()

        // Set build number
        binding.root.context.let {
            CoroutineScope(dispatcher.main()).launch {
                val versionName = it.packageManager.getPackageInfo(it.packageName, 0).versionName.toString()
                val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.packageManager.getPackageInfo(it.packageName, 0).longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    it.packageManager.getPackageInfo(it.packageName, 0).versionCode.toString()
                }
                val jsVersion = getJsVersion(it)

                binding.tvBuildNumber.text = it.getString(R.string.settingsVersionFull, versionName, buildNumber, jsVersion)
            }
        }
    }

    private fun setFontSize() {
        with(binding) {
            // KahfGuard
            title.scaleIndependentTextSize(20f)
            tvDescription.scaleIndependentTextSize(14f)
            btnHigh.btnLabel.scaleIndependentTextSize(13f)
            btnMedium.btnLabel.scaleIndependentTextSize(13f)
            btnLow.btnLabel.scaleIndependentTextSize(13f)

            // SafeGaze
            tvDecent.scaleIndependentTextSize(18f)
            tvDecentDescription.scaleIndependentTextSize(14f)
            btnPauseOnSite.scaleIndependentTextSize(12f)

            // SafeGaze Lock
            tvPreventTurningOff.scaleIndependentTextSize(18f)
            tvPreventDescription.scaleIndependentTextSize(14f)

            // Statistics
            tvHarmAvoided.scaleIndependentTextSize(20f)
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

    @WorkerThread
    private suspend fun getJsVersion(context: Context): String {
        return withContext(dispatcher.io()) {
            val firstLine = try {
                val inputStream = context.assets.open("video_filter.js")
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readLine() ?: ""
                }
            } catch (e: IOException) {
                ""
            }

            firstLine.takeIf { it.startsWith("// v") }?.substringAfter("// v") ?: "1.0.0"
        }
    }
}
