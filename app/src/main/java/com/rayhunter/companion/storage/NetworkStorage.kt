package com.rayhunter.companion.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rayhunter.companion.data.SavedNetwork

class NetworkStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("simple_wifi_networks", Context.MODE_PRIVATE)
    
    init {
        Log.d("NetworkStorage", "initialized with context: ${context.javaClass.simpleName}")
    }
    
    fun saveNetwork(ssid: String, password: String): Boolean {
        Log.d("NetworkStorage", "saveNetwork() called with SSID: $ssid")
        val editor = prefs.edit()
        val id = System.currentTimeMillis()
        editor.putString("network_${id}_ssid", ssid)
        editor.putString("network_${id}_password", password)
        editor.putLong("network_${id}_id", id)
        
        // Also save the list of IDs
        val existingIds = getNetworkIds().toMutableSet()
        Log.d("NetworkStorage", "Existing IDs before save: $existingIds")
        existingIds.add(id)
        editor.putStringSet("network_ids", existingIds.map { it.toString() }.toSet())
        
        val result = editor.commit()
        Log.d("NetworkStorage", "Save result: $result, new ID: $id")
        return result
    }
    
    fun getAllNetworks(): List<SavedNetwork> {
        Log.d("NetworkStorage", "getAllNetworks() called")
        val networks = mutableListOf<SavedNetwork>()
        val ids = getNetworkIds()
        Log.d("NetworkStorage", "Found ${ids.size} network IDs: $ids")
        
        for (id in ids) {
            val ssid = prefs.getString("network_${id}_ssid", null)
            val password = prefs.getString("network_${id}_password", null)
            Log.d("NetworkStorage", "Processing ID $id - SSID: $ssid, Password exists: ${password != null}")
            
            if (ssid != null && password != null) {
                networks.add(SavedNetwork(id = id, ssid = ssid, password = password))
            }
        }
        
        Log.d("NetworkStorage", "Returning ${networks.size} networks")
        return networks
    }
    
    fun deleteNetwork(id: Long): Boolean {
        Log.d("NetworkStorage", "deleteNetwork() called with ID: $id")
        val editor = prefs.edit()
        editor.remove("network_${id}_ssid")
        editor.remove("network_${id}_password")
        editor.remove("network_${id}_id")
        
        val existingIds = getNetworkIds().toMutableSet()
        existingIds.remove(id)
        editor.putStringSet("network_ids", existingIds.map { it.toString() }.toSet())
        
        val result = editor.commit()
        Log.d("NetworkStorage", "Delete result: $result")
        return result
    }
    
    
    private fun getNetworkIds(): Set<Long> {
        val idsSet = prefs.getStringSet("network_ids", emptySet()) ?: emptySet()
        return idsSet.mapNotNull { it.toLongOrNull() }.toSet()
    }
}