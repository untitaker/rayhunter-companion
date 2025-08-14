package com.rayhunter.companion

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rayhunter.companion.R
import com.rayhunter.companion.databinding.ActivityWebviewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewBinding
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var originalNetwork: Network? = null
    private var currentUrl: String = ""
    
    companion object {
        const val EXTRA_NETWORK_SSID = "network_ssid"
        const val EXTRA_NETWORK_URL = "network_url"
        const val EXTRA_NETWORK_PASSWORD = "network_password"
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkSSID = intent.getStringExtra(EXTRA_NETWORK_SSID) ?: "Unknown Network"
        val networkURL = intent.getStringExtra(EXTRA_NETWORK_URL) ?: ""
        val networkPassword = intent.getStringExtra(EXTRA_NETWORK_PASSWORD) ?: ""
        currentUrl = networkURL
        
        Log.d("WebViewActivity", "Starting with SSID: $networkSSID, URL: $networkURL")
        
        // Set up the toolbar
        supportActionBar?.title = "Browse: $networkSSID"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Store the current network before switching
        originalNetwork = connectivityManager.activeNetwork
        
        // Maintain WiFi connection for this network
        maintainNetworkConnection(networkSSID, networkPassword)
        
        // Configure WebView
        binding.webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("WebViewActivity", "Page started loading: $url")
                    binding.progressBar.visibility = android.view.View.VISIBLE
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebViewActivity", "Page finished loading: $url")
                    binding.progressBar.visibility = android.view.View.GONE
                }
                
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    Log.e("WebViewActivity", "WebView error: ${error?.description}")
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@WebViewActivity, "Failed to load page: ${error?.description}", Toast.LENGTH_LONG).show()
                }
            }
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "RayHunter Companion App"
            }
        }
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
                        
                        connectivityManager.bindProcessToNetwork(network)
                        
                        runOnUiThread {
                            Toast.makeText(this@WebViewActivity, "Locked to $ssid network", Toast.LENGTH_SHORT).show()
                            
                            // Load the URL since network is connected
                            if (currentUrl.isNotEmpty()) {
                                binding.webView.loadUrl(currentUrl)
                            }
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
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.webview_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                binding.webView.reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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