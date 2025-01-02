package com.duckduckgo.app.prayers.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.AdhanAndNavigationBottomSheetFragmentBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.duckduckgo.app.prayers.constants.PrayersConstants.NotificationTypes
import com.duckduckgo.app.prayers.listeners.OnNotificationTypeSelectedListener
import com.google.android.material.bottomsheet.BottomSheetBehavior

class AdhanAndNotificationBottomSheetFragment(
    private val notificationType: String,
    private val onNotificationTypeSelectedListener: OnNotificationTypeSelectedListener
): BottomSheetDialogFragment() {

    private lateinit var binding: AdhanAndNavigationBottomSheetFragmentBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.adhan_and_navigation_bottom_sheet_fragment, container, false)
        binding = AdhanAndNavigationBottomSheetFragmentBinding.bind(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = 1000
            }
        }

        arrangeCells()
    }

    private fun arrangeCells() {
        binding.apply {
            when (notificationType) {
                NotificationTypes.MUTED -> {
                    silent.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_selected_background)
                    notification.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)
                    // adhan.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)

                    silentIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                    notificationIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                    // adhanIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))

                    silentText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                    notificationText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                    // adhanText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                }
                NotificationTypes.UNMUTED -> {
                    silent.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)
                    notification.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_selected_background)
                    // adhan.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)

                    silentIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                    notificationIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                    // adhanIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))

                    silentText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                    notificationText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                    // adhanText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                }
                // NotificationTypes.VOICE_ENABLED -> {
                //     silent.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)
                //     notification.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_unselected_background)
                //     adhan.background = ContextCompat.getDrawable(requireContext(), R.drawable.adhan_and_notification_selected_background)
                //
                //     silentIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                //     notificationIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                //     adhanIcon.setColorFilter(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                //
                //     silentText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                //     notificationText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_deselected_icon_gray))
                //     adhanText.setTextColor(ContextCompat.getColor(requireContext(), com.duckduckgo.mobile.android.R.color.kahf_selected_icon_blue))
                // }
            }

            silent.setOnClickListener {
                onNotificationTypeSelectedListener.onNotificationTypeSelected(NotificationTypes.MUTED)
                dismiss()
            }

            notification.setOnClickListener {
                onNotificationTypeSelectedListener.onNotificationTypeSelected(NotificationTypes.UNMUTED)
                dismiss()
            }

            // adhan.setOnClickListener {
            //     onNotificationTypeSelectedListener.onNotificationTypeSelected(NotificationTypes.VOICE_ENABLED)
            //     dismiss()
            // }
        }
    }
}
