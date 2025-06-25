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

package com.duckduckgo.app.safegaze

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment

class DeviceLockAuthenticator(
    private val fragment: Fragment,
    private val requestCode: Int = REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL
) {

    companion object {
        const val REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL = 312
    }

    private val context: Context?
        get() = fragment.context

    private val keyguardManager: KeyguardManager?
    get() = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    fun isDeviceSecure(): Boolean {
        return keyguardManager?.isDeviceSecure == true
    }

    fun promptForAuthentication(title: String = "Unlock SafeGaze", description: String = "Please verify to continue", toastMessage: String = "") {
        val kManager = keyguardManager
        val ctx = context

        if (kManager == null || ctx == null) {
            //return if fragment not attached to a context or keyGuardManager is null
            return
        }

        if (!isDeviceSecure()) {
            Toast.makeText(ctx, toastMessage, Toast.LENGTH_LONG).show()
            return
        }

        val intent = kManager.createConfirmDeviceCredentialIntent(title, description)
        if (intent != null) {
            fragment.startActivityForResult(intent, requestCode)
        } else {
            Toast.makeText(ctx, "Unable to show safeGaze settings", Toast.LENGTH_LONG).show()
        }
    }

    fun handleDeviceLockAuthenticatorActivityResult(
        requestCode: Int,
        resultCode: Int,
        onAuthenticated: () -> Unit,
        onFailed: () -> Unit = {}
    ) {
        if (requestCode == this.requestCode) {
            if (resultCode == Activity.RESULT_OK) {
                onAuthenticated()
            } else {
                onFailed()
            }
        }
    }
}

