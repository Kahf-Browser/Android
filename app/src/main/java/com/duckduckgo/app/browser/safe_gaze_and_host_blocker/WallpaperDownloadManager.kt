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

package com.duckduckgo.app.browser.safe_gaze_and_host_blocker

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.duckduckgo.common.utils.extensions.md5
import com.google.gson.Gson
import com.google.gson.JsonArray
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object WallpaperDownloadManager {

    /**
     * If there are multiple images in the list, randomly download only 3 to avoid high data consumption at first launch
     */
    private fun downloadImages(context: Context, urls: List<String>) {

        val shuffledUrls = urls.shuffled() // Shuffle the list of URLs to pick randomly
        var downloadedCount = 0

        for (url in shuffledUrls) {
            val fileName = url.md5()
            val file =  File("${context.filesDir}/wp/$fileName")

            if (file.parentFile?.exists() == false) {
                file.parentFile?.mkdirs()
            }

            // Check if the file already exists
            if (file.exists()) {
                Timber.d("fLog File already exists: ${file.absolutePath}")
                continue
            }

            // Download and save the image
            try {
                val connection: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                val inputStream: InputStream = connection.inputStream
                val bitmap: Bitmap = BitmapFactory.decodeStream(inputStream)

                val outputStream = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.flush()
                outputStream.close()

                Timber.d("fLog Image saved: ${file.absolutePath}")

                downloadedCount++
                if (downloadedCount >= 3) {
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.d("fLog Failed to download image from: $url")
            }
        }
    }

    fun fetchWallpapers(context: Context){
        val jsonUrl = URL("https://raw.githubusercontent.com/Kahf-Browser/public/main/wallpapers/labels.json")

        try {
            val gson = Gson()
            val jsonString = jsonUrl.readBytes().decodeToString()

            // Save the JSON file to the disk
            val file = File("${context.filesDir}/labels.json")
            if (file.parentFile?.exists() == false) {
                file.parentFile?.mkdirs()
            }
            file.writeText(jsonString)

            // Parse the Image download Urls
            val jsonArray: JsonArray = gson.fromJson(jsonString, JsonArray::class.java)

            Timber.d("fLog Wallpaper's list downloaded successfully.")
            downloadImages(context, jsonArray.map { it.asJsonObject.get("download_url").asString })
            deleteMarkedWallpaper(context, jsonArray)
        } catch (e: IOException) {
            Timber.d("fLog Error downloading wallpaper's list: $e")
        }
    }

    private fun deleteMarkedWallpaper(context: Context, jsonArray: JsonArray) {
        for (element in jsonArray) {
            val jsonObject = element.asJsonObject

            if (jsonObject.has("remove") && jsonObject.get("remove").asBoolean) {
                val url = jsonObject.get("download_url").asString
                val fileName = url.md5()
                val file = File("${context.filesDir}/wp/$fileName")

                if (file.exists()) {
                    val deleted = file.delete()
                    Timber.d("fLog Deleted wallpaper: ${file.absolutePath}: $deleted")
                }
            }
        }
    }

}

