package com.duckduckgo.app.prayers.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.batoulapps.adhan.Madhab
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.prayers.adapters.MadhabAsrTimeRecyclerAdapter.Holder
import com.duckduckgo.app.prayers.landing.SharedPrefKey
import com.duckduckgo.app.prayers.listeners.OnMadhabMethodClickedListener

class MadhabAsrTimeRecyclerAdapter(
    private val dataSet: Map<Madhab, String>,
    private val context: Context?,
    private val onClickListener: OnMadhabMethodClickedListener
) : RecyclerView.Adapter<Holder>() {

    private val sharedPrefName = "PrayersPreferences"
    private val defaultMadhabMethod = "HANAFI"

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): Holder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_prayers_page_calculation_method, parent, false)
        return Holder(itemView)
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int
    ) {
        val currentValue = dataSet.values.toList()[position]
        val currentKey = dataSet.keys.toList()[position]
        holder.calculationMethodName.text = currentValue
        holder.container.setOnClickListener {
            onClickListener.onMadhabMethodClicked(currentKey)
            notifyDataSetChanged()
        }
        val savedCalculationMethod = context?.getSharedPreferences(sharedPrefName, Context.MODE_PRIVATE)?.getString(
            SharedPrefKey.MADHAB_METHOD_KEY.value, defaultMadhabMethod,
        )
        if (savedCalculationMethod == currentKey.name) {
            holder.backgroundView.background = ContextCompat.getDrawable(context!!, R.drawable.adhan_and_notification_selected_background)
        } else {
            holder.backgroundView.background = ContextCompat.getDrawable(context!!, R.drawable.common_rounded_background)
        }
    }

    override fun getItemCount() = dataSet.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: ConstraintLayout = itemView.findViewById(R.id.container_view)
        val backgroundView: LinearLayout = itemView.findViewById(R.id.background_view)
        val calculationMethodName: TextView = itemView.findViewById(R.id.calculation_method_name)
    }
}
