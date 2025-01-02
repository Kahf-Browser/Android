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
import com.batoulapps.adhan.CalculationMethod
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.prayers.adapters.CalculationMethodRecyclerAdapter.Holder
import com.duckduckgo.app.prayers.landing.SharedPrefKey
import com.duckduckgo.app.prayers.listeners.OnCalculationMethodClickedListener

class CalculationMethodRecyclerAdapter(
    private val dataSet: Map<CalculationMethod, String>,
    private val context: Context?,
    private val onClickListener: OnCalculationMethodClickedListener
) : RecyclerView.Adapter<Holder>() {

    private val sharedPrefName = "PrayersPreferences"
    private val sharedPrefKey = "calculationMethod"
    private val defaultCalculationMethod = "MUSLIM_WORLD_LEAGUE"
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
            onClickListener.onCalculationMethodClicked(currentKey)
            notifyDataSetChanged()
        }
        val savedCalculationMethod = context?.getSharedPreferences(sharedPrefName, Context.MODE_PRIVATE)?.getString(
            SharedPrefKey.CALCULATION_METHOD_KEY.value, defaultCalculationMethod,
        )
        if (savedCalculationMethod == currentKey.name) {
            holder.backgroundView.background = ContextCompat.getDrawable(context!!, R.drawable.adhan_and_notification_selected_background)
        } else {
            holder.backgroundView.background = ContextCompat.getDrawable(context!!, R.drawable.common_rounded_background)
        }
    }

    override fun getItemCount(): Int {
        return dataSet.values.size
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: ConstraintLayout = itemView.findViewById(R.id.container_view)
        val backgroundView: LinearLayout = itemView.findViewById(R.id.background_view)
        val calculationMethodName: TextView = itemView.findViewById(R.id.calculation_method_name)
    }
}
