package com.rayhunter.companion

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rayhunter.companion.data.SavedNetwork
import com.rayhunter.companion.databinding.ActivityWebviewBinding
import com.rayhunter.companion.storage.NetworkStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewBinding
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var networkStorage: NetworkStorage
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var targetNetwork: Network? = null
    private var originalNetwork: Network? = null
    private var allNetworks: List<SavedNetwork> = emptyList()
    private var currentNetworkIndex: Int = 0
    private var currentUrl: String = ""
    
    companion object {
        const val EXTRA_NETWORK_SSID = "network_ssid"
        const val EXTRA_NETWORK_PASSWORD = "network_password"
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        networkStorage = NetworkStorage(this)
        
        val networkSSID = intent.getStringExtra(EXTRA_NETWORK_SSID) ?: "Unknown Network"
        val networkPassword = intent.getStringExtra(EXTRA_NETWORK_PASSWORD) ?: ""
        
        val gatewayIP = getGatewayIP()
        if (gatewayIP == null) {
            Toast.makeText(this, "Failed to detect router IP address", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        currentUrl = "http://$gatewayIP:8080"
        
        // Load all networks and find current network index
        lifecycleScope.launch {
            allNetworks = withContext(Dispatchers.IO) {
                networkStorage.getAllNetworks()
            }
            currentNetworkIndex = allNetworks.indexOfFirst { it.ssid == networkSSID }
            if (currentNetworkIndex == -1) currentNetworkIndex = 0
        }
        
        // Set up the toolbar
        supportActionBar?.title = "Browse: $networkSSID"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Store the current network before switching
        originalNetwork = connectivityManager.activeNetwork
        
        // Maintain WiFi connection for this network
        maintainNetworkConnection(networkSSID, networkPassword)
        
        // Configure WebView (but don't load URL yet)
        setupWebView()
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            webViewClient = object : WebViewClient() {
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.progressBar.visibility = android.view.View.VISIBLE
                }
                
                private var retryCount = 0
                private val maxRetries = 10
                
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Log.d("WebViewActivity", "WebView error - ${error?.description}, retry $retryCount/$maxRetries")
                        
                        if (retryCount < maxRetries) {
                            retryCount++
                            lifecycleScope.launch {
                                delay(500) // Short delay between retries
                                runOnUiThread {
                                    view?.reload()
                                }
                            }
                        } else {
                            Toast.makeText(this@WebViewActivity, "Failed to load after $maxRetries attempts", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    retryCount = 0 // Reset retry count on successful load
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "RayHunter Companion App"
            }
            
            // url will be loaded later
        }
        
        binding.btnBack.setOnClickListener {
            switchToPreviousNetwork()
        }
        
        binding.btnForward.setOnClickListener {
            switchToNextNetwork()
        }
        
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    
    private fun maintainNetworkConnection(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
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

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d("WebViewActivity", "Network available for $ssid")
                        targetNetwork = network
                        
                        connectivityManager.bindProcessToNetwork(network)
                        
                        runOnUiThread {
                            Toast.makeText(this@WebViewActivity, "Locked to $ssid network", Toast.LENGTH_SHORT).show()
                            
                            // now load the URL since network is connected
                            binding.webView.loadUrl(currentUrl)
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        Log.d("WebViewActivity", "Network lost for $ssid")
                        runOnUiThread {
                            Toast.makeText(this@WebViewActivity, "Connection to $ssid lost - reconnecting", Toast.LENGTH_SHORT).show()
                        }
                        
                        lifecycleScope.launch {
                            delay(1000)
                            maintainNetworkConnection(ssid, password)
                        }
                    }
                    
                    override fun onUnavailable() {
                        Log.d("WebViewActivity", "Network unavailable for $ssid")
                        runOnUiThread {
                            Toast.makeText(this@WebViewActivity, "Cannot connect to $ssid", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            } catch (e: Exception) {
                Log.e("WebViewActivity", "Error maintaining network connection: ${e.message}")
            }
        }
    }
    
    private fun switchToPreviousNetwork() {
        if (allNetworks.isEmpty()) return
        
        currentNetworkIndex = if (currentNetworkIndex > 0) {
            currentNetworkIndex - 1
        } else {
            allNetworks.size - 1
        }
        
        switchToNetwork(allNetworks[currentNetworkIndex])
    }
    
    private fun switchToNextNetwork() {
        if (allNetworks.isEmpty()) return
        
        currentNetworkIndex = if (currentNetworkIndex < allNetworks.size - 1) {
            currentNetworkIndex + 1
        } else {
            0
        }
        
        switchToNetwork(allNetworks[currentNetworkIndex])
    }
    
    private fun switchToNetwork(network: SavedNetwork) {
        connectivityManager.bindProcessToNetwork(null)
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        
        supportActionBar?.title = "Browse: ${network.ssid}"
        
        binding.progressBar.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Switching to ${network.ssid}...", Toast.LENGTH_SHORT).show()
        
        maintainNetworkConnection(network.ssid, network.password)
        
        lifecycleScope.launch {
            delay(3000) // Wait for network connection
            binding.webView.loadUrl(currentUrl)
        }
    }
    
    private fun getGatewayIP(): String? {
        return try {
            val dhcpInfo = wifiManager.dhcpInfo
            val gatewayIP = dhcpInfo.gateway
            if (gatewayIP == 0) {
                null
            } else {
                String.format(
                    "%d.%d.%d.%d",
                    (gatewayIP and 0xff),
                    (gatewayIP shr 8 and 0xff),
                    (gatewayIP shr 16 and 0xff),
                    (gatewayIP shr 24 and 0xff)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        
        if (originalNetwork != null) {
            try {
                connectivityManager.bindProcessToNetwork(originalNetwork)
                Log.d("WebViewActivity", "Restored original network")
                runOnUiThread {
                    Toast.makeText(this, "Restored original network connection", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WebViewActivity", "Failed to restore original network: ${e.message}")
                // Fall back to clearing the binding
                connectivityManager.bindProcessToNetwork(null)
                runOnUiThread {
                    Toast.makeText(this, "Network connection reset", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            connectivityManager.bindProcessToNetwork(null)
            runOnUiThread {
                Toast.makeText(this, "Network connection reset", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
