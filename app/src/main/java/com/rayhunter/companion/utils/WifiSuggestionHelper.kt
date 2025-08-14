package com.rayhunter.companion.utils

import android.content.Context
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

class WifiSuggestionHelper(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    @RequiresApi(Build.VERSION_CODES.Q)
    fun suggestNetwork(ssid: String, password: String?): Boolean {
        return try {
            val suggestionBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            
            if (!password.isNullOrEmpty()) {
                suggestionBuilder.setWpa2Passphrase(password)
            }
            
            val status = wifiManager.addNetworkSuggestions(listOf(suggestionBuilder.build()))
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS || 
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        } catch (e: Exception) {
            false
        }
    }
    
    fun canUseSuggestions(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}