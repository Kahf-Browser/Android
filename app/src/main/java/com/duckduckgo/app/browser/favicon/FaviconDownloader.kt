/*
 * Copyright (c) 2020 DuckDuckGo
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

package com.duckduckgo.app.browser.favicon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.duckduckgo.common.utils.DispatcherProvider
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

interface FaviconDownloader {
    suspend fun getFaviconFromDisk(file: File): Bitmap?
    suspend fun getFaviconFromDisk(
        file: File,
        cornerRadius: Int,
        width: Int,
        height: Int,
    ): Bitmap?

    suspend fun getFaviconFromUrl(uri: Uri): Bitmap?

    suspend fun getFaviconFromGoogleApi(url: String): Bitmap?
}

class GlideFaviconDownloader @Inject constructor(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : FaviconDownloader {

    override suspend fun getFaviconFromDisk(file: File): Bitmap? {
        return withContext(dispatcherProvider.io()) {
            return@withContext runCatching {
                Glide.with(context)
                    .asBitmap()
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .submit()
                    .get()
            }.getOrNull()
        }
    }

    override suspend fun getFaviconFromDisk(
        file: File,
        cornerRadius: Int,
        width: Int,
        height: Int,
    ): Bitmap? {
        return withContext(dispatcherProvider.io()) {
            return@withContext runCatching {
                Glide.with(context)
                    .asBitmap()
                    .load(file)
                    .transform(RoundedCorners(cornerRadius))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .submit(width, height)
                    .get()
            }.getOrNull()
        }
    }

    override suspend fun getFaviconFromUrl(uri: Uri): Bitmap? {
        return withContext(dispatcherProvider.io()) {
            return@withContext runCatching {
                Glide.with(context)
                    .asBitmap()
                    .load(uri)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .submit()
                    .get()
            }.getOrNull()
        }
    }

    override suspend fun getFaviconFromGoogleApi(url: String): Bitmap? {
        return withContext(dispatcherProvider.io()) {
            // Try Google's favicon service first
            val googleUri = "https://www.google.com/s2/favicons?domain=$url&sz=64".toUri()
            var icon = loadBitmapFromUrl(googleUri)

            // Fall back to Clearbit if Google's service doesn't return a favicon
            if (icon == null) {
                // FIXME ClearBit API will shut down on December 1, 2025. Use BrandFetch
                val clearBitUri = "https://logo.clearbit.com/$url".toUri()
                icon = loadBitmapFromUrl(clearBitUri)
            }

            icon
        }
    }

    private fun loadBitmapFromUrl(uri: Uri): Bitmap? {
        return runCatching {
            Glide.with(context)
                .asBitmap()
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(true)
                .submit()
                .get()
        }.getOrNull()
    }
}
