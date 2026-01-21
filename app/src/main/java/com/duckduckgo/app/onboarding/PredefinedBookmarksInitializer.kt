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

package com.duckduckgo.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.savedsites.api.SavedSitesRepository
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Qualifier

/**
 * Data class representing a predefined bookmark to be added for all users.
 */
data class PredefinedBookmarkItem(
    val title: String,
    val url: String
)

/**
 * Lifecycle observer that adds predefined bookmarks on app startup.
 *
 * This initializer ensures that predefined bookmarks are available to both:
 * - New users (who go through onboarding)
 * - Existing users (who already have the app installed)
 *
 * It uses a version number stored in SharedPreferences to track which bookmarks
 * have been added. When the version number increases, new bookmarks are added
 * without duplicating existing ones.
 */
class PredefinedBookmarksInitializer(
    private val savedSitesRepository: SavedSitesRepository,
    private val sharedPreferences: SharedPreferences,
    private val appCoroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider
) : MainProcessLifecycleObserver {

    companion object {
        private const val PREFS_NAME = "predefined_bookmarks_prefs"
        private const val KEY_BOOKMARKS_VERSION = "bookmarks_version"

        /**
         * Current version of predefined bookmarks.
         * Increment this when adding new bookmarks to ensure they get added
         * for existing users on app update.
         */
        const val CURRENT_BOOKMARKS_VERSION = 1
    }

    /**
     * List of predefined bookmarks to be added.
     * These will be added as favorites for all users.
     */
    private val predefinedBookmarks = listOf(
        PredefinedBookmarkItem("Muslims Day", "https://muslimsday.com"),
        PredefinedBookmarkItem("Kahf Kids", "https://kahfkids.com"),
        PredefinedBookmarkItem("Kahf Guard", "https://kahfguard.com/"),
        PredefinedBookmarkItem("Hikmah", "https://hikmah.net/"),
        PredefinedBookmarkItem("Mahfil", "https://mahfil.net"),
        PredefinedBookmarkItem("Islam QA", "https://islamqa.info/en"),
    )

    override fun onCreate(owner: LifecycleOwner) {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            addPredefinedBookmarksIfNeeded()
        }
    }

    private fun addPredefinedBookmarksIfNeeded() {
        val storedVersion = sharedPreferences.getInt(KEY_BOOKMARKS_VERSION, 0)

        if (storedVersion < CURRENT_BOOKMARKS_VERSION) {
            Timber.d("PredefinedBookmarks: Adding predefined bookmarks (stored version: $storedVersion, current: $CURRENT_BOOKMARKS_VERSION)")
            addPredefinedBookmarks()
            sharedPreferences.edit {
                putInt(KEY_BOOKMARKS_VERSION, CURRENT_BOOKMARKS_VERSION)
            }
            Timber.d("PredefinedBookmarks: Successfully added predefined bookmarks")
        } else {
            Timber.d("PredefinedBookmarks: Bookmarks already at version $storedVersion, skipping")
        }
    }

    private fun addPredefinedBookmarks() {
        predefinedBookmarks.forEach { bookmark ->
            try {
                // Check if the bookmark already exists to avoid duplicates
                val existingFavorite = savedSitesRepository.getFavorite(bookmark.url)
                if (existingFavorite == null) {
                    savedSitesRepository.insertFavorite(
                        url = bookmark.url,
                        title = bookmark.title
                    )
                    Timber.d("PredefinedBookmarks: Added bookmark '${bookmark.title}'")
                } else {
                    Timber.d("PredefinedBookmarks: Bookmark '${bookmark.title}' already exists, skipping")
                }
            } catch (e: Exception) {
                Timber.e(e, "PredefinedBookmarks: Failed to add bookmark '${bookmark.title}'")
            }
        }
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PredefinedBookmarksPrefs

@Module
@ContributesTo(AppScope::class)
object PredefinedBookmarksModule {

    @Provides
    @PredefinedBookmarksPrefs
    fun providePredefinedBookmarksSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            "predefined_bookmarks_prefs",
            Context.MODE_PRIVATE
        )
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    @IntoSet
    fun providePredefinedBookmarksInitializer(
        savedSitesRepository: SavedSitesRepository,
        @PredefinedBookmarksPrefs sharedPreferences: SharedPreferences,
        @com.duckduckgo.app.di.AppCoroutineScope appCoroutineScope: CoroutineScope,
        dispatcherProvider: DispatcherProvider
    ): MainProcessLifecycleObserver {
        return PredefinedBookmarksInitializer(
            savedSitesRepository,
            sharedPreferences,
            appCoroutineScope,
            dispatcherProvider
        )
    }
}
