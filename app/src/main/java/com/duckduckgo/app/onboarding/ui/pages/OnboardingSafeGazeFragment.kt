package com.duckduckgo.app.onboarding.ui.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboardingSafegazeBinding
import com.duckduckgo.app.browser.safe_gaze.isHardwareCompatible
import com.duckduckgo.app.isZikrTab
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.app.safegaze.enums.SafeGazeLevel
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.di.scopes.FragmentScope
import io.kahf.kahf_segmentation.ImageProcessor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingSafeGazeFragment : DuckDuckGoFragment(R.layout.fragment_onboarding_safegaze) {

    @Inject
    lateinit var analytics: AnalyticsService

    @Inject
    lateinit var nsfwDetector: NsfwDetector

    @Inject
    lateinit var dispatcher: DispatcherProvider

    @Inject
    lateinit var spProvider: SharedPreferencesProvider

    @Inject
    lateinit var imageProcessor: ImageProcessor

    lateinit var binding: FragmentOnboardingSafegazeBinding

    private var hardwareCompatibilityChecked = false
    private var textColor: Int = 0
    private var text: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnboardingSafegazeBinding.inflate(inflater, container, false)

        val isZikrTab = isZikrTab()

        binding.btnSkip.isVisible = isZikrTab.not()
        binding.btnSkip.setOnClickListener {
            if (hardwareCompatibilityChecked) {
                analytics.logEvent(AnalyticsEvent.OnboardSkipDecentInternet)
                // Turn off SafeGaze
                SafeGazeLevel.updateLevel(spProvider.getKahfSharedPreferences(), SafeGazeLevel.Off)
                (requireActivity() as KahfOnboardingActivity).onContinueClicked()
            }
        }

        binding.btnEnableSafeGaze.isVisible = isZikrTab.not()
        binding.btnEnableSafeGaze.setOnClickListener {
            if (hardwareCompatibilityChecked) {
                analytics.logEvent(AnalyticsEvent.OnboardEnabledDecentInternet)
                onEnableSafeGazeClicked()
            }
        }

        binding.btnNext.isVisible = isZikrTab
        binding.btnNext.setOnClickListener {
            if (hardwareCompatibilityChecked) {
                onEnableSafeGazeClicked()
            }
        }

        return binding.root
    }

    private fun setCompatibilityViewState() {
        binding.progressLoader.visibility = if (hardwareCompatibilityChecked) View.GONE else View.VISIBLE

        if (text != "") {
            binding.tvCompatibility.text = text
            binding.tvCompatibility.setTextColor(textColor)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hardwareCompatibilityChecked) {
            checkImageBlurCompatibility()
        }
        setCompatibilityViewState()
    }

    private fun checkImageBlurCompatibility() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED).not()) {
            return
        }

        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            Timber.e("kLog Error checking hardware compatibility")

            lifecycleScope.launch(dispatcher.main()) {
                binding.tvCompatibility.text = getString(R.string.kahf_onboarding_incompatible).also { text = it }
                binding.progressLoader.visibility = View.GONE
                binding.tvCompatibility.setTextColor(
                    ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_red).also { textColor = it }
                )
            }
            hardwareCompatibilityChecked = true
        }

        lifecycleScope.launch(dispatcher.io() + exceptionHandler) {
            // Wait for the View to be ready
            delay(500)
            isHardwareCompatible(requireContext(), nsfwDetector, imageProcessor) { result ->
                CoroutineScope(dispatcher.main()).launch {
                    binding.tvCompatibility.text = getString(
                        if (result) {
                            R.string.kahf_onboarding_compatible
                        } else {
                            R.string.kahf_onboarding_slow
                        }
                    ).also { text = it }

                    binding.tvCompatibility.setTextColor(
                        ContextCompat.getColor(requireContext(), if (result) {
                            com.duckduckgo.mobile.android.R.color.kahf_green
                        } else {
                            com.duckduckgo.mobile.android.R.color.kahf_orange
                        }).also { textColor = it }
                    )

                    binding.progressLoader.visibility = View.GONE
                }
                hardwareCompatibilityChecked = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SAVED_STATE_COMP_CHECKED, hardwareCompatibilityChecked)
        outState.putInt(SAVED_STATE_COMP_TEXT_COLOR, textColor)
        outState.putString(SAVED_STATE_COMP_TEXT_LABEL, text)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        hardwareCompatibilityChecked = savedInstanceState?.getBoolean(SAVED_STATE_COMP_CHECKED) ?: false
        textColor = savedInstanceState?.getInt(SAVED_STATE_COMP_TEXT_COLOR) ?: 0
        text = savedInstanceState?.getString(SAVED_STATE_COMP_TEXT_LABEL) ?: ""
    }

    private fun onEnableSafeGazeClicked() {
        SafeGazeLevel.updateLevel(spProvider.getKahfSharedPreferences(), SafeGazeLevel.Pixelation)
        (requireActivity() as KahfOnboardingActivity).onContinueClicked()
    }

    companion object {
        private const val SAVED_STATE_COMP_CHECKED = "SAVED_STATE_COMP_CHECKED"
        private const val SAVED_STATE_COMP_TEXT_COLOR = "SAVED_STATE_COMP_TEXT_COLOR"
        private const val SAVED_STATE_COMP_TEXT_LABEL = "SAVED_STATE_COMP_TEXT_LABEL"
    }
}
