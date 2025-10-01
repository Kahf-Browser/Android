package com.duckduckgo.app.onboarding.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.FragmentOnboarding1Binding
import com.duckduckgo.app.languages.Language
import com.duckduckgo.app.languages.LanguageSelectionBottomSheetDialogFragment
import com.duckduckgo.app.languages.OnLanguageClickedListener
import com.duckduckgo.app.onboarding.ui.KahfOnboardingActivity
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.ui.LanguageManager
import com.duckduckgo.di.scopes.FragmentScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import timber.log.Timber
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class OnboardingFragment1 : DuckDuckGoFragment(R.layout.fragment_onboarding1) {

    @Inject
    lateinit var appBuildConfig: AppBuildConfig

    lateinit var binding: FragmentOnboarding1Binding

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnboarding1Binding.inflate(inflater, container, false)

        binding.btnContinue.setOnClickListener {
            Timber.d("saved Lang: ${getSavedLanguage(requireContext())}")
            if (getSavedLanguage(requireContext()) == "xx") {
                showLanguageSelectionBottomSheet()
            } else {
                (requireActivity() as KahfOnboardingActivity).onContinueClicked()
            }
        }

        return binding.root
    }

    private fun getSavedLanguage(context: Context?): String {
        val prefs = context?.getSharedPreferences("settings", MODE_PRIVATE)
        return prefs?.getString("language", "xx") ?: "xx"
    }

    private fun showLanguageSelectionBottomSheet() {
        LanguageSelectionBottomSheetDialogFragment
            .builder()
            .setListener(
                object : OnLanguageClickedListener {
                    override fun onLanguageClicked(language: Language) {
                        switchLanguage(language.code)
                        (parentFragmentManager.findFragmentByTag("langBs") as BottomSheetDialogFragment).dismiss()
                    }
                },
            )
            .build()
            .show(parentFragmentManager, "langBs")
    }

    private fun switchLanguage(lang: String) {
        // Save preference
        context?.getSharedPreferences("settings", MODE_PRIVATE)?.edit {
            putString("language", lang)
        }

        // Apply immediately
        LanguageManager.setLocale(requireContext(), lang)

        // Restart app (so all Activities update)
        val intent = Intent(requireActivity(), KahfOnboardingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }


    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        requestNotificationsPermissions()
    }

    @SuppressLint("InlinedApi")
    private fun requestNotificationsPermissions() {
        if (appBuildConfig.sdkInt >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
