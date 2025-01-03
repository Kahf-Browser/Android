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

package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.icu.text.SimpleDateFormat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.duckduckgo.common.utils.SAFE_GAZE_JS_FILENAME
import com.duckduckgo.common.utils.SAFE_GAZE_JS_URL_DEV
import com.duckduckgo.common.utils.SAFE_GAZE_JS_URL_PROD
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

class JsDownloadWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    override fun doWork(): Result {
        return try {
            fetchJs()
            Timber.d("SafeGazeJs: File downloaded and overwritten successfully.")
            Result.success()
        } catch (e: Exception) {
            Timber.e("SafeGazeJs: Error writing to the local js file: $e")
            Result.failure()
        }
    }

    private fun fetchJs() {
        val localJsFile = File("${applicationContext.filesDir}/$SAFE_GAZE_JS_FILENAME")

        if (localJsFile.parentFile?.exists() == false) {
            localJsFile.parentFile?.mkdirs()
        }

        // get package name
        val jsFileData = URL(
            if (applicationContext.packageName == "io.kahf.browser") SAFE_GAZE_JS_URL_PROD else SAFE_GAZE_JS_URL_DEV
        ).readBytes()

        FileOutputStream(localJsFile).use { fos ->
            fos.write(jsFileData)
        }

        val timestamp = "\n// Downloaded at: ${SimpleDateFormat("dd.MM.yy hh:mm:ss", Locale.US).format(System.currentTimeMillis())}\n//--eof--\n"
        localJsFile.appendText(timestamp, charset = Charsets.UTF_8)
    }
}
