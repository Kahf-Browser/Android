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

package com.duckduckgo.app.browser.safe_gaze

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class DeviceLockAuthenticator(
    private val fragment: Fragment,
    private val onAuthenticated: () -> Unit,
    private val onFailed: () -> Unit = {}
) {
    private var launcher: ActivityResultLauncher<Intent>? = null

    init {
        // Register the launcher safely with the fragment's lifecycle
        launcher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                onAuthenticated()
            } else {
                onFailed()
            }
        }
    }

    fun isDeviceSecure(): Boolean {
        val context = fragment.context ?: return false
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    fun promptForAuthentication(
        title: String = "Unlock SafeGaze",
        description: String = "Please verify to continue"
    ) {
        val context = fragment.context
        val keyguardManager = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        if (keyguardManager == null || !keyguardManager.isDeviceSecure) {
            context?.let {
                Toast.makeText(it, it.getString(com.duckduckgo.mobile.android.R.string.kahf_no_device_credential), Toast.LENGTH_LONG).show()
            }
            return
        }

        val intent = keyguardManager.createConfirmDeviceCredentialIntent(title, description)
        if (intent != null) {
            launcher?.launch(intent)
        } else {
            context.let {
                Toast.makeText(it, "Unable to verify at this moment. Please try later", Toast.LENGTH_LONG).show()
            }
        }
    }
}
