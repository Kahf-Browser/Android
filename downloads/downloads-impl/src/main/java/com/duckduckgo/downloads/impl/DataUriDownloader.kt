/*
 * Copyright (c) 2018 DuckDuckGo
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

import android.util.Base64
import android.webkit.URLUtil
import androidx.annotation.WorkerThread
import com.duckduckgo.common.utils.formatters.time.DatabaseDateFormatter
import com.duckduckgo.downloads.api.DownloadFailReason
import com.duckduckgo.downloads.api.DownloadFailReason.DataUriParseException
import com.duckduckgo.downloads.api.FileDownloader.PendingFileDownload
import com.duckduckgo.downloads.api.model.DownloadItem
import com.duckduckgo.downloads.impl.DataUriParser.GeneratedFilename
import com.duckduckgo.downloads.impl.DataUriParser.ParseResult
import com.duckduckgo.downloads.store.DownloadStatus.STARTED
import java.io.File
import java.io.IOException
import javax.inject.Inject
import logcat.asLog
import logcat.logcat

class DataUriDownloader @Inject constructor(
    private val dataUriParser: DataUriParser,
) {

    @WorkerThread
    fun download(
        pending: PendingFileDownload,
        callback: DownloadCallback,
    ) {
        try {
            when (val parsedDataUri = dataUriParser.generate(pending.url)) {
                is ParseResult.Invalid -> {
                    logcat { "Failed to extract data from data URI" }
                    callback.onError(url = pending.url, reason = DataUriParseException)
                    return
                }
                is ParseResult.ParsedDataUri -> {
                    val file = initialiseFilesOnDisk(pending, parsedDataUri.filename, parsedDataUri.mimeType)

                    callback.onStart(
                        DownloadItem(
                            downloadId = 0L,
                            downloadStatus = STARTED,
                            fileName = file.name,
                            contentLength = 0L,
                            filePath = file.absolutePath,
                            createdAt = DatabaseDateFormatter.timestamp(),
                        ),
                    )

                    runCatching {
                        writeBytesToFiles(parsedDataUri.data, file)
                    }
                        .onSuccess {
                            logcat { "Succeeded to decode Base64" }
                            callback.onSuccess(file = file, mimeType = parsedDataUri.mimeType)
                        }
                        .onFailure {
                            logcat { "Failed to decode Base64: ${it.asLog()}" }
                            callback.onError(url = pending.url, reason = DownloadFailReason.DataUriParseException)
                        }
                }
            }
        } catch (e: IOException) {
            logcat { "Failed to save data uri: ${e.asLog()}" }
            callback.onError(url = pending.url, reason = DownloadFailReason.DataUriParseException)
        }
    }

    private fun writeBytesToFiles(
        data: String?,
        file: File,
    ) {
        val imageByteArray = Base64.decode(data, Base64.DEFAULT)
        file.writeBytes(imageByteArray)
    }

    private fun initialiseFilesOnDisk(
        pending: PendingFileDownload,
        generatedFilename: GeneratedFilename,
        mimeType: String?,
    ): File {
        val downloadDirectory = pending.directory

        // Use filename from contentDisposition if available (e.g. for Service Worker downloads
        // where the original filename is in the Content-Disposition header but the URL is a data URI)
        val fileName = extractFilenameFromContentDisposition(pending.contentDisposition, mimeType)
            ?: generatedFilename.toString()

        val file = File(downloadDirectory, fileName)

        if (!downloadDirectory.exists()) downloadDirectory.mkdirs()
        if (!file.exists()) file.createNewFile()
        return file
    }

    private fun extractFilenameFromContentDisposition(contentDisposition: String?, mimeType: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val guessed = URLUtil.guessFileName("", contentDisposition, mimeType)
        // URLUtil.guessFileName returns "downloadfile" or "downloadfile.bin" as default fallback
        if (guessed.isNullOrBlank() || guessed == "downloadfile" || guessed == "downloadfile.bin") return null
        return guessed
    }
}
