/*
 * Copyright (c) 2019 DuckDuckGo
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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.common.utils.baseHost
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.downloads.api.DownloadConfirmationDialogListener
import com.duckduckgo.downloads.api.DownloadLocationPreferences
import com.duckduckgo.downloads.api.FileDownloader.PendingFileDownload
import com.duckduckgo.downloads.impl.RealDownloadConfirmation.Companion.PENDING_DOWNLOAD_BUNDLE_KEY
import com.duckduckgo.downloads.impl.databinding.DownloadConfirmationBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.android.support.AndroidSupportInjection
import java.io.File
import javax.inject.Inject
import logcat.logcat
import com.duckduckgo.downloads.impl.isDataUrl

@InjectWith(FragmentScope::class)
class DownloadConfirmationFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.DownloadsBottomSheetDialogTheme

    val listener: DownloadConfirmationDialogListener
        get() {
            return if (parentFragment != null) {
                parentFragment as DownloadConfirmationDialogListener
            } else {
                activity as DownloadConfirmationDialogListener
            }
        }

    @Inject
    lateinit var filenameExtractor: FilenameExtractor

    @Inject
    lateinit var downloadLocationPreferences: DownloadLocationPreferences

    private var file: File? = null
    private var selectedDirectory: File? = null

    private val pendingDownload: PendingFileDownload by lazy {
        requireArguments()[PENDING_DOWNLOAD_BUNDLE_KEY] as PendingFileDownload
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = DownloadConfirmationBinding.inflate(inflater, container, false)
        setupDownload()
        setupViews(binding)
        return binding.root
    }

    private fun setupDownload() {
        selectedDirectory = downloadLocationPreferences.getDownloadDirectory()
        file = if (!pendingDownload.isDataUrl) {
            when (val filenameExtraction = filenameExtractor.extract(pendingDownload)) {
                is FilenameExtractor.FilenameExtractionResult.Guess -> null
                is FilenameExtractor.FilenameExtractionResult.Extracted -> File(selectedDirectory, filenameExtraction.filename)
            }
        } else {
            null
        }
    }

    private fun setupViews(binding: DownloadConfirmationBinding) {
        (dialog as BottomSheetDialog).behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val fileName = file?.name ?: ""
        binding.downloadMessage.text = fileName
        binding.downloadMessageSubtitle.run {
            val host = runCatching { Uri.parse(pendingDownload.url).baseHost }.getOrNull()
            isVisible = !host.isNullOrBlank()
            text = getString(R.string.downloadConfirmationSubtitle, host)
        }

        updateLocationLabel(binding)

        binding.downloadLocationPicker.setClickListener {
            openDirectoryPicker()
        }

        binding.rememberLocationCheckbox.isChecked = downloadLocationPreferences.shouldRememberLocation()

        binding.continueDownload.setOnClickListener {
            val remember = binding.rememberLocationCheckbox.isChecked
            downloadLocationPreferences.setRememberLocation(remember)
            selectedDirectory?.let { dir ->
                downloadLocationPreferences.setDownloadDirectory(dir.absolutePath)
            }
            val updatedDownload = pendingDownload.copy(
                directory = selectedDirectory ?: pendingDownload.directory,
            )
            listener.continueDownload(updatedDownload)
            dismiss()
        }
        binding.cancel.setOnClickListener {
            logcat { "Cancelled download for url ${pendingDownload.url}" }
            listener.cancelDownload()
            dismiss()
        }
    }

    private fun updateLocationLabel(binding: DownloadConfirmationBinding) {
        val dirName = selectedDirectory?.name ?: getString(R.string.downloadLocationDefault)
        binding.downloadLocationPicker.setPrimaryText(
            getString(R.string.downloadLocationLabel, dirName),
        )
    }

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_DIRECTORY_PICKER)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DIRECTORY_PICKER && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                handleSelectedDirectory(uri)
            }
        }
    }

    private fun handleSelectedDirectory(uri: Uri) {
        // Take persistable permission so we can access this directory in the future
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val path = getPathFromTreeUri(uri)
        if (path != null) {
            selectedDirectory = File(path)
            // Save preference and start download immediately after folder selection
            downloadLocationPreferences.setDownloadDirectory(path)
            downloadLocationPreferences.setDownloadDirectoryTreeUri(uri.toString())
            view?.let { rootView ->
                val binding = DownloadConfirmationBinding.bind(rootView)
                val remember = binding.rememberLocationCheckbox.isChecked
                downloadLocationPreferences.setRememberLocation(remember)
            }
            val updatedDownload = pendingDownload.copy(
                directory = selectedDirectory ?: pendingDownload.directory,
            )
            listener.continueDownload(updatedDownload)
            dismiss()
        }
    }

    private fun getPathFromTreeUri(uri: Uri): String? {
        val docId = uri.lastPathSegment ?: return null
        // Tree URIs have format "primary:Download/subfolder" or "home:subfolder"
        val split = docId.split(":")
        return if (split.size >= 2) {
            val storageType = split[0]
            val relativePath = split[1]
            if (storageType == "primary" || storageType.endsWith("primary")) {
                File(android.os.Environment.getExternalStorageDirectory(), relativePath).absolutePath
            } else {
                // For non-primary storage, fall back to a reasonable path
                File("/storage/$storageType", relativePath).absolutePath
            }
        } else {
            null
        }
    }

    companion object {
        private const val REQUEST_CODE_DIRECTORY_PICKER = 1001
    }
}
