package com.duckduckgo.app.onboarding.ui.pages

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboarding2Binding
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserDetector
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserSystemSettings
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.common.ui.DuckDuckGoActivity
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.di.scopes.FragmentScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingFragment2 : DuckDuckGoFragment(R.layout.fragment_onboarding2) {

    @Inject
    lateinit var defaultWebBrowserCapability: DefaultBrowserDetector

    @Inject
    lateinit var analytics: AnalyticsService

    lateinit var binding: FragmentOnboarding2Binding

    private var userTriedToSetDDGAsDefault = false

    private val defaultBrowserSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (defaultWebBrowserCapability.isDefaultBrowser()) {
            lifecycleScope.launch {
                delay(250)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    (requireActivity() as KahfOnboardingActivity).onContinueClicked()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnboarding2Binding.inflate(inflater, container, false)

        binding.btnSkip.setOnClickListener {
            (requireActivity() as KahfOnboardingActivity).onContinueClicked()
        }

        binding.btnDefaultBrowser.setOnClickListener {
            onLaunchDefaultBrowserSettingsClicked()
        }

        return binding.root
    }

    override fun onResume() {
        if (userTriedToSetDDGAsDefault && defaultWebBrowserCapability.isDefaultBrowser()) {
            analytics.logEvent(AnalyticsEvent.SetAsDefaultBrowser)
        }
        super.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SAVED_STATE_LAUNCHED_DEFAULT, userTriedToSetDDGAsDefault)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        userTriedToSetDDGAsDefault = savedInstanceState?.getBoolean(SAVED_STATE_LAUNCHED_DEFAULT) ?: false
    }

    private fun onLaunchDefaultBrowserSettingsClicked() {
        userTriedToSetDDGAsDefault = true
        val intent = DefaultBrowserSystemSettings.intent()
        try {
            defaultBrowserSettingsLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, getString(R.string.cannotLaunchDefaultAppSettings))
        }
    }

    companion object {
        private const val SAVED_STATE_LAUNCHED_DEFAULT = "SAVED_STATE_LAUNCHED_DEFAULT"
    }
}
