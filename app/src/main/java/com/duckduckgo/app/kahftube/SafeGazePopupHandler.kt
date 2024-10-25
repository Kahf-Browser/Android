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
import android.content.SharedPreferences.Editor
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.SafeGazePopupBinding
import com.duckduckgo.app.browser.safe_gaze.SafeGazeJsInterface
import com.duckduckgo.common.ui.view.scaleIndependentTextSize
import com.duckduckgo.common.utils.KAHF_GUARD_DEFAULT
import com.duckduckgo.common.utils.SAFE_GAZE_MODE
import com.duckduckgo.common.utils.SAFE_GAZE_BLUR_PROGRESS
import com.duckduckgo.common.utils.SAFE_GAZE_DEFAULT_BLUR_VALUE
import com.duckduckgo.common.utils.KAHF_GUARD_INTENSITY
import com.duckduckgo.common.utils.SAFE_GAZE_DEFAULT
import com.hoko.blur.HokoBlur

class SafeGazePopupHandler(
    private val binding: SafeGazePopupBinding,
    private val sharedPreferences: SharedPreferences,
    private val safeGazeInterface: SafeGazeJsInterface,
    val editor: Editor,
    onDnsModeChanged: (mode: PrivateDnsLevel) -> Unit,
    onSafeGazeModeChanged: (mode: SafeGazeLevel) -> Unit,
    onShareClicked: () -> Unit,
    onSupportClicked: () -> Unit,
    onThemeChanged: () -> Unit
) {
    init {
        var btnHigh: PopupButton? = null
        var btnMed: PopupButton? = null
        var btnLow: PopupButton? = null

        val preSelectedDns: PrivateDnsLevel = PrivateDnsLevel.get(sharedPreferences.getString(KAHF_GUARD_INTENSITY, KAHF_GUARD_DEFAULT)!!)
        val preSelectedSG: SafeGazeLevel = SafeGazeLevel.get(sharedPreferences.getString(SAFE_GAZE_MODE, SAFE_GAZE_DEFAULT)!!)

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
        loadImageWithBlur(
            sharedPreferences.getInt(SAFE_GAZE_BLUR_PROGRESS, SAFE_GAZE_DEFAULT_BLUR_VALUE),
            binding.ivFullBlur,
        )

        binding.btnShare.setOnClickListener { onShareClicked() }
        binding.btnSupport.setOnClickListener { onSupportClicked() }
        binding.btnTheme.setOnClickListener { onThemeChanged() }

        binding.switchKahdGuard.isChecked = preSelectedDns != PrivateDnsLevel.Off
        binding.switchSafeGaze.isChecked = preSelectedSG != SafeGazeLevel.Off
        binding.privateDnsGroup.isVisible = preSelectedDns != PrivateDnsLevel.Off
        binding.safeGazeGroup.isVisible = preSelectedSG != SafeGazeLevel.Off

        // Toggle private dns (Kahf Guard)
        binding.switchKahdGuard.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                binding.privateDnsGroup.isVisible = false
                onDnsModeChanged(PrivateDnsLevel.Off)
            } else {
                binding.privateDnsGroup.isVisible = true
                btnLow.performClick()
            }
        }

        // Toggle image blur (Safe Gaze)
        binding.switchSafeGaze.setOnCheckedChangeListener { _, isChecked ->
            binding.safeGazeGroup.isVisible = isChecked
            if (isChecked) {
                onSafeGazeModeChanged(SafeGazeLevel.FullImage)
            } else {
                onSafeGazeModeChanged(SafeGazeLevel.Off)
            }
        }

        setFontSize()

        // Set build number
        binding.root.context.let {
            binding.tvBuildNumber.text = it.packageManager.getPackageInfo(it.packageName, 0).versionCode.toString()
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
            fullImageText.scaleIndependentTextSize(12f)
            fullImageTextLine2.scaleIndependentTextSize(12f)
            tvHumanOnlyLine2.scaleIndependentTextSize(12f)
            tvHumanOnly.scaleIndependentTextSize(12f)

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
            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun handleProgressBar() {
        val progress = sharedPreferences.getInt(SAFE_GAZE_BLUR_PROGRESS, SAFE_GAZE_DEFAULT_BLUR_VALUE)
        binding.progressBar.progress = progress
        binding.blurSeekbar.progress = progress
        binding.blurSeekbar.setOnSeekBarChangeListener(
            object : OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    binding.progressBar.progress = progress
                    binding.blurSeekbar.progress = progress
                    safeGazeInterface.updateBlur(progress.toFloat())
                    loadImageWithBlur(progress, binding.ivFullBlur)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    saveProgressToSharedPreferences(seekBar.progress)
                }
            },
        )
    }

    private fun saveProgressToSharedPreferences(progress: Int) {
        editor.putInt(SAFE_GAZE_BLUR_PROGRESS, progress)
        editor.apply()
    }

    private fun loadImageWithBlur(intensity: Int, imageView: ImageView) {
        val bitmap = BitmapFactory.decodeResource(binding.root.context.resources, R.drawable.full_image_blur)

        // Apply blur (4px to 8px)
        val blurValue = linearInterpolation(intensity.toFloat(), outputRange = Pair(4f, 8f)).toInt()
        val blurredBitmap = HokoBlur.with(binding.root.context)
            .radius(blurValue)
            .sampleFactor(1.0f)
            .forceCopy(false)
            .processor()
            .blur(bitmap)

        // Grayscale (100%)
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)

        // Contrast (500%)
        val scale = linearInterpolation(intensity.toFloat(), outputRange = Pair(8.5f, 10f))
        val translate = (-.5f * scale + .5f) * 255f
        colorMatrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        // Brightness (45% to 60%)
        val brightnessValue = linearInterpolation(intensity.toFloat(), outputRange = Pair(0.45f, 0.6f))
        colorMatrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    brightnessValue, 0f, 0f, 0f, 0f,
                    0f, brightnessValue, 0f, 0f, 0f,
                    0f, 0f, brightnessValue, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        val colorFilter = ColorMatrixColorFilter(colorMatrix)
        imageView.colorFilter = colorFilter
        imageView.setImageBitmap(blurredBitmap)
    }

    private fun linearInterpolation(
        value: Float,
        inputRange: Pair<Float, Float> = Pair(0f, 100f),
        outputRange: Pair<Float, Float> = Pair(2f, 8f)
    ): Float {
        // Calculate the ratio of the value within the input range
        val ratio = (value - inputRange.first) / (inputRange.second - inputRange.first)
        // Map the ratio to the output range
        return outputRange.first + ratio * (outputRange.second - outputRange.first)
    }
}
