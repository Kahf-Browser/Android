/*
 * Copyright (c) 2022 DuckDuckGo
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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.duckduckgo.downloads.api.DownloadLocationPreferences
import java.io.File
import java.io.IOException
import javax.inject.Inject
import logcat.asLog
import logcat.logcat

class DownloadFileCopier @Inject constructor(
    private val context: Context,
    private val downloadLocationPreferences: DownloadLocationPreferences,
) {

    sealed class CopyResult {
        data class WithFile(val file: File) : CopyResult()
        data class WithUri(val file: File, val contentUri: Uri) : CopyResult()
    }

    /**
     * Copies a cached/temp file to the user's target download directory.
     * On Android Q+ uses MediaStore for public directories and SAF for custom directories.
     * On older versions falls back to direct file copy.
     *
     * @return CopyResult on success, null on failure
     */
    fun copyToTargetDirectory(cachedFile: File, fileName: String, targetDir: File, mimeType: String?): CopyResult? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyViaMediaStore(cachedFile, fileName, targetDir, mimeType)
            } else {
                CopyResult.WithFile(copyDirectly(cachedFile, fileName, targetDir))
            }
        } catch (e: Exception) {
            logcat { "Failed to copy file to target directory: ${e.asLog()}" }
            null
        }
    }

    private fun copyViaMediaStore(cachedFile: File, fileName: String, targetDir: File, mimeType: String?): CopyResult {
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        val isWithinDownloads = if (targetDir.absolutePath.startsWith(externalRoot)) {
            val subPath = targetDir.absolutePath.removePrefix(externalRoot).removePrefix(File.separator)
            val downloadsDir = Environment.DIRECTORY_DOWNLOADS
            subPath.isEmpty() || subPath == downloadsDir || subPath.startsWith(downloadsDir + File.separator)
        } else {
            false
        }

        // For custom directories outside Downloads, use SAF (DocumentFile) via the persisted tree URI
        if (!isWithinDownloads) {
            val treeUriString = downloadLocationPreferences.getDownloadDirectoryTreeUri()
            if (treeUriString != null) {
                val treeUri = Uri.parse(treeUriString)
                val documentFile = DocumentFile.fromTreeUri(context, treeUri)
                if (documentFile != null && documentFile.canWrite()) {
                    val newFile = documentFile.createFile(mimeType ?: "application/octet-stream", fileName)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                            cachedFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        } ?: throw IOException("Failed to open output stream for SAF file $fileName")
                        return CopyResult.WithUri(File(targetDir, fileName), newFile.uri)
                    }
                }
            }
            // If SAF fails, fall through to MediaStore with Downloads as fallback
            logcat { "SAF write failed for custom directory, falling back to MediaStore Downloads" }
        }

        val relativePath = if (targetDir.absolutePath.startsWith(externalRoot)) {
            val subPath = targetDir.absolutePath.removePrefix(externalRoot).removePrefix(File.separator)
            subPath.ifEmpty { Environment.DIRECTORY_DOWNLOADS }
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            if (mimeType != null) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for $fileName")

        val outputStream = resolver.openOutputStream(uri)
            ?: throw IOException("Failed to open output stream for MediaStore entry $fileName")
        try {
            outputStream.use { os ->
                cachedFile.inputStream().use { inputStream ->
                    inputStream.copyTo(os)
                }
            }
        } finally {
            val clearPending = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, clearPending, null, null)
        }

        val actualDir = Environment.getExternalStoragePublicDirectory(relativePath)
        return CopyResult.WithUri(File(actualDir, fileName), uri)
    }

    private fun copyDirectly(cachedFile: File, fileName: String, targetDir: File): File {
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, fileName)
        cachedFile.copyTo(targetFile, overwrite = true)
        return targetFile
    }
}
