package com.rayhunter.companion.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rayhunter.companion.R
import com.rayhunter.companion.data.ScannedNetwork

class ScannedNetworkAdapter(
    initialNetworks: List<ScannedNetwork>,
    private val onSelect: (ScannedNetwork) -> Unit
) : RecyclerView.Adapter<ScannedNetworkAdapter.ViewHolder>() {
    
    private val networks = mutableListOf<ScannedNetwork>()
    
    init {
        networks.addAll(initialNetworks)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.tvScannedSSID)
        
        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSelect(networks[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scanned_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val network = networks[position]
        holder.ssidText.text = network.ssid
    }

    override fun getItemCount() = networks.size

    fun updateNetworks(newNetworks: List<ScannedNetwork>) {
        networks.clear()
        networks.addAll(newNetworks)
        notifyDataSetChanged()
    }
}