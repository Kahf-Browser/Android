package com.duckduckgo.app.onboarding.ui.pages

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboardingSafegazeBinding
import com.duckduckgo.app.browser.safe_gaze.SafeGazeJsInterface
import com.duckduckgo.app.kahftube.SafeGazeLevel
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.app.safegaze.genderdetection.GenderDetector
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.app.safegaze.poseDetection.MoveNetMultiPose
import com.duckduckgo.app.trackerdetection.db.KahfImageBlockedDao
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.SAFE_GAZE_MODE
import com.duckduckgo.common.utils.SAFE_GAZE_PREFERENCES
import com.duckduckgo.di.scopes.FragmentScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingSafeGazeFragment : DuckDuckGoFragment(R.layout.fragment_onboarding_safegaze) {

    @Inject
    lateinit var analytics: AnalyticsService

    @Inject
    lateinit var nsfwDetector: NsfwDetector

    @Inject
    lateinit var genderDetector: GenderDetector

    @Inject
    lateinit var poseDetector: MoveNetMultiPose

    @Inject
    lateinit var kahfImageBlockedDao: KahfImageBlockedDao

    @Inject
    lateinit var dispatcher: DispatcherProvider

    lateinit var binding: FragmentOnboardingSafegazeBinding

    private var userTriedToEnableSafeGaze = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnboardingSafegazeBinding.inflate(inflater, container, false)

        binding.btnSkip.setOnClickListener {
            (requireActivity() as KahfOnboardingActivity).onContinueClicked()
        }

        binding.btnDefaultBrowser.setOnClickListener {
            onEnableSafeGazeClicked()
        }

        // To avoid the 'Skip' button being hidden behind the navigation bar
        (requireActivity() as DuckDuckGoActivity).getNavigationBarHeight {
            binding.guidelineBottom.setGuidelinePercent(
                if (it > 100) 0.8f else 0.9f
            )
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!userTriedToEnableSafeGaze) {
            checkImageBlurCompatibility()
            userTriedToEnableSafeGaze = true
        }
    }

    private fun checkImageBlurCompatibility() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED).not()) {
            return
        }

        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            Timber.e("kLog Error checking hardware compatibility")

            CoroutineScope(dispatcher.main()).launch {
                binding.tvCompatibility.text = getString(R.string.kahf_onboarding_incompatible)
                binding.progressLoader.visibility = View.GONE
                binding.tvCompatibility.setTextColor(
                    ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_red)
                )
            }
        }

        CoroutineScope(dispatcher.io() + exceptionHandler).launch {
            val jsInterface = SafeGazeJsInterface(
                requireContext(), nsfwDetector, genderDetector, poseDetector, kahfImageBlockedDao, dispatcher,
                { _ -> // No op - onUpdateBlur
                },
                { _, _, _ -> // No op - onImageClassified
                },
            )

            // Wait for the View to be ready
            delay(500)
            val result = jsInterface.isHardwareCompatible()

            withContext(dispatcher.main()) {
                binding.tvCompatibility.text = getString(
                    if (result) {
                        R.string.kahf_onboarding_compatible
                    } else {
                        R.string.kahf_onboarding_slow
                    }
                )

                binding.tvCompatibility.setTextColor(
                    ContextCompat.getColor(requireContext(), if (result) {
                        com.duckduckgo.mobile.android.R.color.kahf_green
                    } else {
                        com.duckduckgo.mobile.android.R.color.kahf_orange
                    })
                )

                binding.progressLoader.visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SAVED_STATE_ENABLED_SG, userTriedToEnableSafeGaze)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        userTriedToEnableSafeGaze = savedInstanceState?.getBoolean(SAVED_STATE_ENABLED_SG) ?: false
    }

    private fun onEnableSafeGazeClicked() {
        val preferences = requireContext().getSharedPreferences(SAFE_GAZE_PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().putString(SAFE_GAZE_MODE, SafeGazeLevel.FullImage.name).apply()

        (requireActivity() as KahfOnboardingActivity).onContinueClicked()
    }

    companion object {
        private const val SAVED_STATE_ENABLED_SG = "SAVED_STATE_ENABLED_SG"
    }
}
