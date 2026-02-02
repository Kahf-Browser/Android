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

package com.duckduckgo.downloads.impl

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.downloads.api.DownloadLocationPreferences
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import java.io.File
import javax.inject.Inject

@ContributesBinding(AppScope::class)
@SingleInstanceIn(AppScope::class)
class DownloadLocationPreferencesImpl @Inject constructor(
    private val context: Context,
) : DownloadLocationPreferences {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getDownloadDirectory(): File {
        val customPath = prefs.getString(KEY_DOWNLOAD_DIRECTORY, null)
        return if (customPath != null) {
            File(customPath).also { if (!it.exists()) it.mkdirs() }
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
    }

    override fun setDownloadDirectory(path: String) {
        prefs.edit { putString(KEY_DOWNLOAD_DIRECTORY, path) }
    }

    override fun shouldRememberLocation(): Boolean {
        return prefs.getBoolean(KEY_REMEMBER_LOCATION, false)
    }

    override fun setRememberLocation(remember: Boolean) {
        prefs.edit { putBoolean(KEY_REMEMBER_LOCATION, remember) }
    }

    override fun hasCustomDownloadDirectory(): Boolean {
        return prefs.getString(KEY_DOWNLOAD_DIRECTORY, null) != null
    }

    override fun getDownloadDirectoryPath(): String {
        return prefs.getString(KEY_DOWNLOAD_DIRECTORY, null)
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    override fun getDownloadDirectoryTreeUri(): String? {
        return prefs.getString(KEY_DOWNLOAD_DIRECTORY_TREE_URI, null)
    }

    override fun setDownloadDirectoryTreeUri(uri: String?) {
        prefs.edit { putString(KEY_DOWNLOAD_DIRECTORY_TREE_URI, uri) }
    }

    companion object {
        private const val PREFS_NAME = "com.duckduckgo.downloads.location"
        private const val KEY_DOWNLOAD_DIRECTORY = "download_directory"
        private const val KEY_REMEMBER_LOCATION = "remember_location"
        private const val KEY_DOWNLOAD_DIRECTORY_TREE_URI = "download_directory_tree_uri"
    }
}
