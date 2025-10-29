/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.common.ui

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.duckduckgo.common.ui.DuckDuckGoTheme.DARK
import com.duckduckgo.common.ui.DuckDuckGoTheme.LIGHT
import com.duckduckgo.common.ui.store.ThemingDataStore
import com.duckduckgo.mobile.android.R
import dagger.android.AndroidInjection
import dagger.android.DaggerActivity
import java.util.Locale
import javax.inject.Inject

abstract class DuckDuckGoActivity : DaggerActivity() {

    @Inject lateinit var viewModelFactory: ViewModelProvider.NewInstanceFactory

    @Inject lateinit var themingDataStore: ThemingDataStore

    private var themeChangeReceiver: BroadcastReceiver? = null

    private lateinit var biometricPrompt: BiometricPrompt
    private val promptInfo: BiometricPrompt.PromptInfo by lazy {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.kahf_safegaze_unlock))
            .setAllowedAuthenticators(
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
    }

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        onCreate(savedInstanceState, true)
    }

    override fun attachBaseContext(newBase: Context?) {
        val lang = getSavedLanguage(newBase) // from SharedPreferences
        val context = if (VERSION.SDK_INT < VERSION_CODES.TIRAMISU) {
            updateBaseContextLocale(newBase, lang)
        } else newBase

        super.attachBaseContext(context)
    }

    private fun getSavedLanguage(context: Context?): String {
        val prefs = context?.getSharedPreferences("settings", MODE_PRIVATE)
        return prefs?.getString("language", "en") ?: "en"
    }

    private fun updateBaseContextLocale(context: Context?, lang: String): Context? {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = context?.resources?.configuration
        config?.setLocale(locale)
        return context?.createConfigurationContext(config!!)
    }

    /**
     * We need to conditionally defer the Dagger initialization in certain places. So if this method
     * is called from an Activity with daggerInject=false, you'll probably need to call
     * daggerInject() directly.
     */
    fun onCreate(
        savedInstanceState: Bundle?,
        daggerInject: Boolean = true,
    ) {
        if (daggerInject) daggerInject()
        themeChangeReceiver = applyTheme(themingDataStore.theme)
        super.onCreate(savedInstanceState)
        // 1. Tell the window to draw behind the system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. Get the root view of the activity's content
        val contentView = findViewById<View>(android.R.id.content)

        // 3. Set a listener to handle insets
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, windowInsets ->
            // Get the insets for the system bars (status bar, navigation bar)
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply the insets as padding to the root view.
            // This pushes your content down from the status bar and up from the navigation bar.
            view.setPadding(insets.left, insets.top, insets.right, 0)

            // Return the insets so that other views can also process them if needed.
            windowInsets
        }
    }

    protected fun daggerInject() {
        AndroidInjection.inject(this, bindingKey = DaggerActivity::class.java)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        themeChangeReceiver?.let {
            LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(it)
        }
        super.onDestroy()
    }

    fun setupToolbar(toolbar: Toolbar) {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left_24)
    }

    fun setTranslucentStatusBarAndNavBar() {
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            window.clearFlags(LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            @Suppress("DEPRECATION")
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
        }
    }

    fun isDarkThemeEnabled(): Boolean {
        return when (themingDataStore.theme) {
            DuckDuckGoTheme.SYSTEM_DEFAULT -> {
                val uiManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                when (uiManager.nightMode) {
                    UiModeManager.MODE_NIGHT_YES -> true
                    else -> false
                }
            }
            DARK -> true
            else -> false
        }
    }

    fun toggleTheme() {
        val currentTheme = themingDataStore.theme
        val newTheme = when (currentTheme) {
            LIGHT -> DARK
            DARK -> LIGHT
            DuckDuckGoTheme.SYSTEM_DEFAULT -> {
                if (isDarkThemeEnabled()) LIGHT else DARK
            }
        }
        themingDataStore.theme = newTheme
        sendThemeChangedBroadcast()
    }

    fun isAnySecurityEnabled(): Boolean {
        val biometricManager = BiometricManager.from(this@DuckDuckGoActivity)
        return biometricManager.canAuthenticate(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(function: (authenticated: Boolean, msgId: Int) -> Unit) {
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    function.invoke(true, 0)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)

                    val messageResId = when (errorCode) {
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                            R.string.kahf_no_device_credential
                        BiometricPrompt.ERROR_NO_BIOMETRICS ->
                            R.string.kahf_no_biometrics
                        BiometricPrompt.ERROR_HW_NOT_PRESENT ->
                            R.string.kahf_no_biometric_hardware
                        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                            R.string.kahf_biometric_unavailable
                        BiometricPrompt.ERROR_LOCKOUT ->
                            R.string.kahf_too_many_attempts
                        else -> R.string.kahf_authentication_error
                    }

                    function.invoke(false, messageResId)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    function.invoke(false, R.string.kahf_not_recognized)
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

    protected inline fun <reified V : ViewModel> bindViewModel() = lazy {
        ViewModelProvider(this, viewModelFactory).get(V::class.java)
    }
}
