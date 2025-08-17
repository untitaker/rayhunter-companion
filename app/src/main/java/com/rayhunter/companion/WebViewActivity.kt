package com.rayhunter.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.res.Configuration
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var isDestroyed = false
    
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
        
        // Start the foreground service immediately to show notification
        val serviceIntent = Intent(this, WiFiLockService::class.java).apply {
            action = WiFiLockService.ACTION_START
            putExtra(WiFiLockService.EXTRA_SSID, networkSSID)
            putExtra(WiFiLockService.EXTRA_URL, networkURL)
        }
        
        try {
            // Check if notifications are allowed before starting foreground service
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Pre-Android 13 doesn't need notification permission
            }
            
            if (!hasNotificationPermission) {
                Log.w("WebViewActivity", "Notification permission not granted - foreground service may not work properly")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
                Log.d("WebViewActivity", "Started foreground WiFi lock service")
            } else {
                startService(serviceIntent)
                Log.d("WebViewActivity", "Started WiFi lock service")
            }
            
            // Show a toast to confirm the service started
            Toast.makeText(this, "WiFi lock active", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Failed to start WiFi lock service: ${e.message}")
            Toast.makeText(this, "WiFi lock failed", Toast.LENGTH_SHORT).show()
        }
        
        // Establish the WiFi connection and load the WebView
        connectAndLoadWebView(networkSSID, networkPassword, networkURL)
        
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
                
                // Enable dark mode support for websites
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    isAlgorithmicDarkeningAllowed = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                        forceDark = WebSettings.FORCE_DARK_ON
                    } else {
                        forceDark = WebSettings.FORCE_DARK_OFF
                    }
                }
            }
        }
    }
    
    private fun connectAndLoadWebView(ssid: String, password: String, url: String) {
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
                        if (isDestroyed) return
                        Log.d("WebViewActivity", "Connected to $ssid")
                        
                        connectivityManager.bindProcessToNetwork(network)
                        
                        // Load the WebView
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                binding.webView.loadUrl(url)
                            }
                        }
                    }
                }

                connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            } catch (e: Exception) {
                Log.e("WebViewActivity", "Error connecting to network: ${e.message}")
            }
        } else {
            // For older Android versions, just load the URL directly
            binding.webView.loadUrl(url)
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
    
    override fun onPause() {
        super.onPause()
        // Pause WebView to stop JavaScript execution and network requests
        binding.webView.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        if (!isDestroyed) {
            binding.webView.onResume()
        }
    }
    
    override fun onDestroy() {
        isDestroyed = true
        
        // Clean up resources in proper order:
        // 1. Stop network operations first
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
            } catch (e: Exception) {
                Log.e("WebViewActivity", "Error unregistering network callback: ${e.message}")
            }
        }
        
        // 2. Stop WebView operations
        try {
            binding.webView.stopLoading()
            binding.webView.onPause()
            binding.webView.removeAllViews()
            binding.webView.destroy()
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error destroying WebView: ${e.message}")
        }
        
        // 3. Clean up service
        try {
            val serviceIntent = Intent(this, WiFiLockService::class.java).apply {
                action = WiFiLockService.ACTION_STOP
            }
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error stopping service: ${e.message}")
        }
        
        // 4. Reset network binding
        try {
            if (originalNetwork != null) {
                connectivityManager.bindProcessToNetwork(originalNetwork)
                Log.d("WebViewActivity", "Restored original network")
            } else {
                connectivityManager.bindProcessToNetwork(null)
                Log.d("WebViewActivity", "Reset network binding")
            }
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Failed to restore network: ${e.message}")
            try {
                connectivityManager.bindProcessToNetwork(null)
            } catch (e2: Exception) {
                Log.e("WebViewActivity", "Failed to reset network binding: ${e2.message}")
            }
        }
        
        // 5. Call super.onDestroy() last
        super.onDestroy()
    }
}