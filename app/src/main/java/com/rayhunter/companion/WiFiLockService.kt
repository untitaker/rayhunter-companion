package com.rayhunter.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.rayhunter.companion.data.ManifestResponse
import com.rayhunter.companion.data.AnalysisResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class WiFiLockService : Service() {
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivityManager: ConnectivityManager
    
    // Polling related
    private var pollingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var httpClient: OkHttpClient
    private lateinit var gson: Gson
    private var baseUrl: String = ""
    private var hasWarnings = false
    private var currentStatus = RecordingStatus.UNKNOWN
    private var currentSsid = ""
    
    enum class RecordingStatus {
        UNKNOWN,        // Haven't checked yet
        NOT_RECORDING,  // No active recording
        RECORDING,      // Recording but no warnings
        WARNING         // Recording with warnings
    }
    
    companion object {
        const val CHANNEL_ID = "wifi_lock_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rayhunter.companion.START_WIFI_LOCK"
        const val ACTION_STOP = "com.rayhunter.companion.STOP_WIFI_LOCK"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_URL = "url"
        
        // Notification IDs
        const val WARNING_NOTIFICATION_ID = 1002
        
        private const val TAG = "WiFiLockService"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WiFiLockService onCreate() called")
        
        try {
            // Initialize WiFi lock
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock("RayhunterWiFiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "RayhunterWiFiLock")
            }
            Log.d(TAG, "WiFi lock initialized")
            
            // Initialize wake lock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rayhunter::WiFiWakeLock")
            Log.d(TAG, "Wake lock initialized")
            
            // Initialize connectivity manager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            Log.d(TAG, "Connectivity manager initialized")
            
            // Initialize HTTP client and JSON parser
            httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            gson = Gson()
            Log.d(TAG, "HTTP client initialized")
            
            createNotificationChannel()
            Log.d(TAG, "WiFiLockService onCreate() completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate(): ${e.message}", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ssid = intent.getStringExtra(EXTRA_SSID) ?: "Unknown Network"
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                baseUrl = url // Store the base URL for API calls
                currentSsid = ssid // Store SSID for notifications
                
                Log.d(TAG, "Starting WiFi lock for SSID: $ssid, URL: $url")
                
                // Ensure notification channel is created before starting foreground
                createNotificationChannel()
                
                // Start polling for Rayhunter status before showing notification
                startPolling()
                
                // Start foreground service with notification
                try {
                    val notification = createNotification(ssid, url)
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Foreground service started with notification")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to start foreground service - notification permission missing: ${e.message}")
                    // Show regular notification as fallback
                    try {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val notification = createNotification(ssid, url)
                        notificationManager.notify(NOTIFICATION_ID, notification)
                        Log.d(TAG, "Showing regular notification as fallback")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to show fallback notification: ${e2.message}")
                    }
                    Log.w(TAG, "Service will continue without foreground status - may be killed by system")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service: ${e.message}")
                    // Try to stop the service if we can't go foreground
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                // Acquire locks
                try {
                    if (!wifiLock.isHeld) {
                        wifiLock.acquire()
                        Log.d(TAG, "WiFi lock acquired")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acquire WiFi lock: ${e.message}")
                }
                
                try {
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(10*60*1000L) // 10 minutes timeout, will be renewed
                        Log.d(TAG, "Wake lock acquired")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
                }
                
                // Service is now running with locks active
                Log.d(TAG, "WiFi lock service active for $ssid")
                
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping WiFi lock service")
                cleanupResources()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Connection Lock",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Maintains WiFi connection for Rayhunter device"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        } else {
            Log.d(TAG, "Pre-O device, no notification channel needed")
        }
    }
    
    private fun createNotification(ssid: String, url: String): Notification {
        // Intent to open WebView when notification is clicked
        val notificationIntent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_NETWORK_SSID, ssid)
            putExtra(WebViewActivity.EXTRA_NETWORK_URL, url)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val (statusText, statusColor) = when (currentStatus) {
            RecordingStatus.UNKNOWN -> Pair("Checking status... (updates every 30s)", R.color.dark_gray)
            RecordingStatus.NOT_RECORDING -> Pair("Not recording (updates every 30s)", R.color.warning_orange)
            RecordingStatus.RECORDING -> Pair("Recording - No warnings (updates every 30s)", R.color.rayhunter_green)
            RecordingStatus.WARNING -> Pair("Recording - Warnings detected (updates every 30s)", R.color.error_red)
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to $ssid")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(getColor(statusColor))
            .build()
        return notification
    }
    
    
    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed, releasing resources")
        
        cleanupResources()
        
        super.onDestroy()
    }
    
    private fun startPolling() {
        if (baseUrl.isBlank()) {
            Log.w(TAG, "No base URL provided, skipping polling")
            return
        }
        
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            // Poll immediately, then continue polling every 30 seconds
            while (isActive) {
                try {
                    checkRayhunterStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling: ${e.message}")
                }
                
                delay(30000L) // Wait 30 seconds before next check
            }
        }
        Log.d(TAG, "Started polling Rayhunter status")
    }
    
    private suspend fun checkRayhunterStatus() {
        try {
            // Check if recording is active
            val manifestRequest = Request.Builder()
                .url("$baseUrl/api/qmdl-manifest")
                .build()
            
            val manifestResponse = httpClient.newCall(manifestRequest).execute()
            if (!manifestResponse.isSuccessful) {
                Log.w(TAG, "Failed to get manifest: ${manifestResponse.code}")
                return
            }
            
            val manifestJson = manifestResponse.body?.string() ?: return
            val manifest = gson.fromJson(manifestJson, ManifestResponse::class.java)
            
            // Update status based on recording state
            if (manifest.currentEntry != null) {
                // Recording is active, check for warnings
                checkForWarnings()
            } else {
                // No active recording
                updateStatus(RecordingStatus.NOT_RECORDING)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Rayhunter status: ${e.message}")
        }
    }
    
    private suspend fun checkForWarnings() {
        try {
            val analysisRequest = Request.Builder()
                .url("$baseUrl/api/analysis-report/live")
                .build()
            
            val analysisResponse = httpClient.newCall(analysisRequest).execute()
            if (!analysisResponse.isSuccessful) {
                // It's normal for analysis to not be available immediately
                return
            }
            
            val analysisData = analysisResponse.body?.string() ?: return
            val lines = analysisData.trim().split('\n')
            
            var foundWarnings = false
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val result = gson.fromJson(line, AnalysisResult::class.java)
                    if (!result.warnings.isNullOrEmpty()) {
                        foundWarnings = true
                        break
                    }
                } catch (e: Exception) {
                    // Skip malformed JSON lines
                    continue
                }
            }
            
            // Update status based on warning state
            if (foundWarnings) {
                updateStatus(RecordingStatus.WARNING)
                if (!hasWarnings) {
                    showWarningNotification()
                    hasWarnings = true
                }
            } else {
                updateStatus(RecordingStatus.RECORDING)
                if (hasWarnings) {
                    hasWarnings = false
                    cancelWarningNotification()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for warnings: ${e.message}")
        }
    }
    
    private fun showWarningNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rayhunter Warning")
            .setContentText("Suspicious activity detected")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(getColor(R.color.error_red))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
        
        notificationManager.notify(WARNING_NOTIFICATION_ID, notification)
        Log.d(TAG, "Warning notification shown")
    }
    
    private fun cancelWarningNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(WARNING_NOTIFICATION_ID)
        Log.d(TAG, "Warning notification cancelled")
    }
    
    private fun updateStatus(newStatus: RecordingStatus) {
        if (currentStatus != newStatus) {
            currentStatus = newStatus
            updateForegroundNotification()
            Log.d(TAG, "Status updated to: $newStatus")
        }
    }
    
    private fun updateForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(currentSsid, baseUrl)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun cleanupResources() {
        // Cancel all coroutines (including polling)
        serviceScope.cancel()
        
        // Cancel warning notification
        cancelWarningNotification()
        
        // Release WiFi lock with extra safety checks
        try {
            if (::wifiLock.isInitialized) {
                if (wifiLock.isHeld) {
                    wifiLock.release()
                    Log.d(TAG, "WiFi lock released")
                } else {
                    Log.d(TAG, "WiFi lock was not held")
                }
            } else {
                Log.d(TAG, "WiFi lock was not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WiFi lock: ${e.message}")
        }
        
        // Release wake lock with extra safety checks
        try {
            if (::wakeLock.isInitialized) {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.d(TAG, "Wake lock released")
                } else {
                    Log.d(TAG, "Wake lock was not held")
                }
            } else {
                Log.d(TAG, "Wake lock was not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }
        
        // Reset network binding with safety check
        try {
            if (::connectivityManager.isInitialized) {
                connectivityManager.bindProcessToNetwork(null)
                Log.d(TAG, "Network binding reset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting network binding: ${e.message}")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}