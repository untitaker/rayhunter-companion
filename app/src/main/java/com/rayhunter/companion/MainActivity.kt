package com.rayhunter.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rayhunter.companion.adapter.NetworkAdapter
import com.rayhunter.companion.adapter.ScannedNetworkAdapter
import com.rayhunter.companion.data.SavedNetwork
import com.rayhunter.companion.data.ScannedNetwork
import com.rayhunter.companion.databinding.ActivityMainBinding
import com.rayhunter.companion.databinding.DialogPasswordInputBinding
import com.rayhunter.companion.databinding.DialogScanNetworksBinding
import com.rayhunter.companion.storage.NetworkStorage
import com.rayhunter.companion.utils.WifiSuggestionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var networkStorage: NetworkStorage
    private lateinit var networkAdapter: NetworkAdapter
    private lateinit var wifiSuggestionHelper: WifiSuggestionHelper
    
    private var savedNetworks = mutableListOf<SavedNetwork>()
    private var scanDialog: AlertDialog? = null

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
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        networkStorage = NetworkStorage(this)
        wifiSuggestionHelper = WifiSuggestionHelper(this)
        
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
        
        binding.btnScanNetworks.setOnClickListener {
            showScanDialog()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    
    private fun showScanDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        
        if (!wifiManager.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("WiFi Disabled")
                .setMessage("WiFi needs to be enabled to scan for networks.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        
        val binding = DialogScanNetworksBinding.inflate(layoutInflater)
        val scanResults = mutableListOf<ScannedNetwork>()
        
        val adapter = ScannedNetworkAdapter(scanResults) { network ->
            scanDialog?.dismiss()
            showPasswordDialog(network)
        }
        
        binding.rvScannedNetworks.layoutManager = LinearLayoutManager(this)
        binding.rvScannedNetworks.adapter = adapter
        binding.pbScanning.visibility = android.view.View.VISIBLE
        
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val results = wifiManager.scanResults
                if (!results.isNullOrEmpty()) {
                    val uniqueNetworks = mutableMapOf<String, ScannedNetwork>()
                    
                    for (result in results) {
                        val ssid = result.SSID ?: ""
                        if (ssid.isNotEmpty() && uniqueNetworks[ssid] == null) {
                            uniqueNetworks[ssid] = ScannedNetwork(ssid = ssid)
                        }
                    }
                    
                    scanResults.clear()
                    scanResults.addAll(uniqueNetworks.values.sortedBy { it.ssid })
                    adapter.updateNetworks(scanResults)
                    
                    binding.pbScanning.visibility = android.view.View.GONE
                    if (scanResults.isEmpty()) {
                        binding.tvNoNetworks.visibility = android.view.View.VISIBLE
                    } else {
                        binding.rvScannedNetworks.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }
        
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        
        scanDialog = AlertDialog.Builder(this)
            .setView(binding.root)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                try {
                    unregisterReceiver(scanReceiver)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            .create()
            
        scanDialog?.show()
        wifiManager.startScan()
        
        // Use cached results immediately
        lifecycleScope.launch {
            delay(200)
            scanReceiver.onReceive(this@MainActivity, Intent())
        }
    }
    
    private fun showPasswordDialog(network: ScannedNetwork) {
        val dialogBinding = DialogPasswordInputBinding.inflate(layoutInflater)
        dialogBinding.tvPasswordDialogSSID.text = network.ssid
        
        AlertDialog.Builder(this)
            .setTitle("Enter Password")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val password = dialogBinding.etDialogPassword.text.toString().trim()
                saveNetwork(network.ssid, password)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveNetwork(ssid: String, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = networkStorage.saveNetwork(ssid, password)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifiSuggestionHelper.canUseSuggestions()) {
                wifiSuggestionHelper.suggestNetwork(ssid, password)
            }
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Network added: $ssid", Toast.LENGTH_SHORT).show()
                    loadSavedNetworks()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save network", Toast.LENGTH_SHORT).show()
                }
            }
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
                Toast.makeText(this@MainActivity, "Connected to ${network.ssid}", Toast.LENGTH_SHORT).show()
                
                // Wait for DHCP to get gateway IP
                try {
                    val gatewayIP = waitForValidDHCP()
                    val url = "http://$gatewayIP:8080"
                    
                    val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                        putExtra(WebViewActivity.EXTRA_NETWORK_SSID, network.ssid)
                        putExtra(WebViewActivity.EXTRA_NETWORK_PASSWORD, network.password)
                        putExtra(WebViewActivity.EXTRA_NETWORK_URL, url)
                    }
                    startActivity(intent)
                    
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Failed to detect router IP: ${e.message}", Toast.LENGTH_LONG).show()
                }
                
            } else {
                Toast.makeText(this@MainActivity, "Failed to connect to ${network.ssid}", Toast.LENGTH_SHORT).show()
            }
        }
    }



    private fun getGatewayIP(): String {
        val dhcpInfo = wifiManager.dhcpInfo
        val gatewayIP = dhcpInfo.gateway
        
        if (gatewayIP == 0) {
            throw RuntimeException("Gateway IP is 0 - DHCP not available")
        }
        
        return String.format(
            "%d.%d.%d.%d",
            (gatewayIP and 0xff),
            (gatewayIP shr 8 and 0xff),
            (gatewayIP shr 16 and 0xff),
            (gatewayIP shr 24 and 0xff)
        )
    }
    
    private suspend fun waitForValidDHCP(): String {
        repeat(10) { attempt ->
            try {
                return getGatewayIP()
            } catch (e: Exception) {
                if (attempt < 9) {
                    delay(1000)
                }
            }
        }
        throw RuntimeException("Could not get gateway IP after 10 attempts")
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
