package com.duckduckgo.app.onboarding.ui.pages

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboardingSafegazeBinding
import com.duckduckgo.app.kahftube.SafeGazeLevel
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.app.settings.db.SettingsSharedPreferences
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.utils.SAFE_GAZE_MODE
import com.duckduckgo.common.utils.SAFE_GAZE_PREFERENCES
import com.duckduckgo.di.scopes.FragmentScope
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingSafeGazeFragment : DuckDuckGoFragment(R.layout.fragment_onboarding_safegaze) {

    @Inject
    lateinit var analytics: AnalyticsService

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
