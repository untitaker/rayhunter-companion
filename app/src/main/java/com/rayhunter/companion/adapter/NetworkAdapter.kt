package com.rayhunter.companion.adapter

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.rayhunter.companion.R
import com.rayhunter.companion.data.SavedNetwork

class NetworkAdapter(
    initialNetworks: List<SavedNetwork>,
    private val onDelete: (SavedNetwork) -> Unit,
    private val onBrowse: (SavedNetwork) -> Unit
) : RecyclerView.Adapter<NetworkAdapter.NetworkViewHolder>() {
    
    private val networks: MutableList<SavedNetwork> = initialNetworks.toMutableList()

    class NetworkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.tvNetworkSSID)
        val browseButton: Button = view.findViewById(R.id.btnBrowseNetwork)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network, parent, false)
        return NetworkViewHolder(view)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        Log.d("NetworkAdapter", "onBindViewHolder() called for position $position")
        val network = networks[position]
        Log.d("NetworkAdapter", "Binding network: ${network.id}:${network.ssid}")
        holder.ssidText.text = network.ssid
        
        holder.browseButton.setOnClickListener {
            onBrowse(network)
        }
        
        // Long press to delete with confirmation
        holder.itemView.setOnLongClickListener {
            showDeleteConfirmation(holder.itemView.context, network)
            true
        }
    }

    override fun getItemCount(): Int {
        Log.d("NetworkAdapter", "getItemCount() called, returning ${networks.size}")
        return networks.size
    }

    fun updateNetworks(newNetworks: List<SavedNetwork>) {
        Log.d("NetworkAdapter", "updateNetworks() called with ${newNetworks.size} networks")
        Log.d("NetworkAdapter", "Current networks count: ${networks.size}")
        Log.d("NetworkAdapter", "New networks: ${newNetworks.map { "${it.id}:${it.ssid}" }}")
        networks.clear()
        networks.addAll(newNetworks)
        Log.d("NetworkAdapter", "After update, networks count: ${networks.size}")
        notifyDataSetChanged()
        Log.d("NetworkAdapter", "notifyDataSetChanged() called")
    }


    
    private fun showDeleteConfirmation(context: Context, network: SavedNetwork) {
        AlertDialog.Builder(context)
            .setTitle("Delete Network")
            .setMessage("Are you sure you want to delete '${network.ssid}'?")
            .setPositiveButton("Delete") { _, _ ->
                onDelete(network)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}