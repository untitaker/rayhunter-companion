package com.rayhunter.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rayhunter.companion.adapter.NetworkAdapter
import com.rayhunter.companion.data.SavedNetwork
import com.rayhunter.companion.databinding.ActivityMainBinding
import com.rayhunter.companion.storage.NetworkStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkStorage: NetworkStorage
    private lateinit var networkAdapter: NetworkAdapter
    
    private var savedNetworks = mutableListOf<SavedNetwork>()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Location permission required for WiFi operations", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupUI()
        checkPermissions()
        loadSavedNetworks()
    }

    private fun initializeComponents() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkStorage = NetworkStorage(this)
        
        networkAdapter = NetworkAdapter(
            savedNetworks,
            onDelete = { network -> deleteNetwork(network) },
            onBrowse = { network -> browseNetwork(network) }
        )
        
        binding.rvSavedNetworks.layoutManager = LinearLayoutManager(this)
        binding.rvSavedNetworks.adapter = networkAdapter
    }

    private fun setupUI() {
        binding.btnAddNetwork.setOnClickListener {
            addNewNetwork()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadSavedNetworks() {
        Log.d("MainActivity", "loadSavedNetworks() called")
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("MainActivity", "About to call networkStorage.getAllNetworks()")
            val loadedNetworks = networkStorage.getAllNetworks()
            Log.d("MainActivity", "Loaded ${loadedNetworks.size} networks from storage")
            withContext(Dispatchers.Main) {
                Log.d("MainActivity", "Switching to Main thread, updating UI")
                savedNetworks.clear()
                savedNetworks.addAll(loadedNetworks)
                Log.d("MainActivity", "savedNetworks now contains ${savedNetworks.size} networks")
                networkAdapter.updateNetworks(savedNetworks)
                Log.d("MainActivity", "Called networkAdapter.updateNetworks()")
            }
        }
    }

    private fun addNewNetwork() {
        val ssid = binding.etNewSSID.text.toString().trim()
        val password = binding.etNewPassword.text.toString()

        if (ssid.isEmpty()) {
            Toast.makeText(this, "Please enter SSID", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("MainActivity", "addNewNetwork() called with SSID: $ssid")
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("MainActivity", "About to call networkStorage.saveNetwork()")
            val success = networkStorage.saveNetwork(ssid, password)
            Log.d("MainActivity", "saveNetwork returned: $success")
            
            withContext(Dispatchers.Main) {
                if (success) {
                    binding.etNewSSID.text?.clear()
                    binding.etNewPassword.text?.clear()
                    Toast.makeText(this@MainActivity, "Network added", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "About to call loadSavedNetworks() after successful save")
                    loadSavedNetworks()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save network", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteNetwork(network: SavedNetwork) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = networkStorage.deleteNetwork(network.id)
            withContext(Dispatchers.Main) {
                if (success) {
                    loadSavedNetworks()
                    Toast.makeText(this@MainActivity, "Network removed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to remove network", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    
    private fun browseNetwork(network: SavedNetwork) {
        Log.d("MainActivity", "browseNetwork() called for ${network.ssid}")
        
        // Show connecting status
        Toast.makeText(this, "Connecting to ${network.ssid}...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            val connected = connectToWifiSuspend(network.ssid, network.password)
            
            if (connected) {
                // Launch WebView immediately
                Toast.makeText(this@MainActivity, "Connected to ${network.ssid}", Toast.LENGTH_SHORT).show()
                
                val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_NETWORK_SSID, network.ssid)
                    putExtra(WebViewActivity.EXTRA_NETWORK_URL, "http://192.168.0.1:8080")
                    putExtra(WebViewActivity.EXTRA_NETWORK_PASSWORD, network.password)
                }
                startActivity(intent)
                
            } else {
                Toast.makeText(this@MainActivity, "Failed to connect to ${network.ssid}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private suspend fun connectToWifiSuspend(ssid: String, password: String): Boolean {
        return try {
                val wifiNetworkSpecifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(wifiNetworkSpecifier)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()

                var connected = false
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connected = true
                    }
                    override fun onUnavailable() {
                        connected = false
                    }
                }

                connectivityManager.requestNetwork(networkRequest, callback)
                delay(8000)
                connectivityManager.unregisterNetworkCallback(callback)
                
                connected
        } catch (e: Exception) {
            false
        }
    }



}
