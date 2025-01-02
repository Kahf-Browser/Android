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

package com.duckduckgo.app.prayers.fragments

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Madhab
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.MadhabAndCalculationSelectionBsFragmentBinding
import com.duckduckgo.app.prayers.adapters.CalculationMethodRecyclerAdapter
import com.duckduckgo.app.prayers.adapters.MadhabAsrTimeRecyclerAdapter
import com.duckduckgo.app.prayers.listeners.OnCalculationMethodClickedListener
import com.duckduckgo.app.prayers.listeners.OnMadhabMethodClickedListener
import com.duckduckgo.common.ui.view.toDp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MadhabAndCalculationSelectionBsFragment: BottomSheetDialogFragment() {
    var madhabOptions: MutableMap<Madhab, String>? = null
    var calculationOptions: MutableMap<CalculationMethod, String>? = null
    var onMadhabClicked: OnMadhabMethodClickedListener? = null
    var onCalculationClicked: OnCalculationMethodClickedListener? = null

    private lateinit var binding: MadhabAndCalculationSelectionBsFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.madhab_and_calculation_selection_bs_fragment, container, false)
        binding = MadhabAndCalculationSelectionBsFragmentBinding.bind(view)
        return view
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = 1000
            }
        }

        if (madhabOptions != null) {
            binding.title.text = getString(R.string.kahf_madhab_asr_time)

            binding.recyclerView.adapter = MadhabAsrTimeRecyclerAdapter(
                madhabOptions!!, requireContext(), onMadhabClicked!!,
            )
        } else if (calculationOptions != null) {
            binding.title.text = getString(R.string.kahf_calculation_method)

            binding.recyclerView.adapter = CalculationMethodRecyclerAdapter(
                calculationOptions!!, requireContext(), onCalculationClicked!!,
            )

            binding.recyclerView.setPadding(0, 0, 0, 400.toDp())
        } else {
            throw IllegalArgumentException("MadhabOptions and CalculationOptions cannot be null at the same time")
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(ItemOffsetDecoration(requireContext()))
    }

    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private val fragment = MadhabAndCalculationSelectionBsFragment()
        private var madhabOptions: MutableMap<Madhab, String>? = null
        private var calculationOptions: MutableMap<CalculationMethod, String>? = null
        private var onMadhabClicked: OnMadhabMethodClickedListener? = null
        private var onCalculationClicked: OnCalculationMethodClickedListener? = null

        fun setMadhabOptions(
            options: MutableMap<Madhab, String>,
            onMadhabClicked: OnMadhabMethodClickedListener
        ): Builder {
            this.madhabOptions = options
            this.onMadhabClicked = onMadhabClicked
            return this
        }

        fun setCalculationOptions(
            options: MutableMap<CalculationMethod, String>,
            onCalculationClicked: OnCalculationMethodClickedListener
        ): Builder {
            this.calculationOptions = options
            this.onCalculationClicked = onCalculationClicked
            return this
        }

        fun build(): MadhabAndCalculationSelectionBsFragment {
            fragment.madhabOptions = madhabOptions
            fragment.calculationOptions = calculationOptions
            fragment.onMadhabClicked = onMadhabClicked
            fragment.onCalculationClicked = onCalculationClicked
            return fragment
        }
    }

    class ItemOffsetDecoration(private val context: Context) : RecyclerView.ItemDecoration() {

        private val spacing: Int = dpToPx(10) // Convert dp to pixels

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            super.getItemOffsets(outRect, view, parent, state)
            val itemPosition = parent.getChildAdapterPosition(view)

            // Add top spacing for the first item
            if (itemPosition == 0) {
                outRect.top = spacing * 2
                outRect.bottom = spacing / 2
            }

            // Add bottom spacing for the last item
            if (itemPosition == parent.adapter?.itemCount?.minus(1)) {
                outRect.top = spacing / 2
                outRect.bottom = spacing * 2
            }

            // Add spacing between items
            if (itemPosition > 0 && itemPosition < (parent.adapter?.itemCount?.minus(1) ?: 0)) {
                outRect.top = spacing / 2
                outRect.bottom = spacing / 2
            }
        }

        private fun dpToPx(dp: Int): Int {
            val density = context.resources.displayMetrics.density
            return (dp * density).toInt()
        }
    }
}
