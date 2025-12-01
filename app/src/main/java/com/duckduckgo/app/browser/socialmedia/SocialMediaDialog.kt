package com.duckduckgo.app.browser.socialmedia

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.WindowManager
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.DialogSocialMediaSelectorBinding

/**
 * Dialog for selecting social media platforms
 */
class SocialMediaDialog(
    private val context: Context,
    private val onPlatformSelected: (SocialMediaManager.SocialMediaPlatform) -> Unit
) {

    private var dialog: Dialog? = null

    fun show() {
        val binding = DialogSocialMediaSelectorBinding.inflate(LayoutInflater.from(context))

        dialog = Dialog(context).apply {
            setContentView(binding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setCancelable(true)
        }

        // Set click listeners for each social media platform
        binding.facebookButton.setOnClickListener {
            handlePlatformClick(SocialMediaManager.SocialMediaPlatform.FACEBOOK)
        }

        binding.twitterButton.setOnClickListener {
            handlePlatformClick(SocialMediaManager.SocialMediaPlatform.TWITTER)
        }

        binding.instagramButton.setOnClickListener {
            handlePlatformClick(SocialMediaManager.SocialMediaPlatform.INSTAGRAM)
        }

        binding.youtubeButton.setOnClickListener {
            handlePlatformClick(SocialMediaManager.SocialMediaPlatform.YOUTUBE)
        }

        binding.linkedinButton.setOnClickListener {
            handlePlatformClick(SocialMediaManager.SocialMediaPlatform.LINKEDIN)
        }

        dialog?.show()
    }

    private fun handlePlatformClick(platform: SocialMediaManager.SocialMediaPlatform) {
        onPlatformSelected(platform)
        dismiss()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun isShowing(): Boolean = dialog?.isShowing == true
}
