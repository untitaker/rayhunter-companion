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

class WiFiLockService : Service() {
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivityManager: ConnectivityManager
    
    companion object {
        const val CHANNEL_ID = "wifi_lock_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rayhunter.companion.START_WIFI_LOCK"
        const val ACTION_STOP = "com.rayhunter.companion.STOP_WIFI_LOCK"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_URL = "url"
        
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
                wifiManager.createWifiLock("RayHunterWiFiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "RayHunterWiFiLock")
            }
            Log.d(TAG, "WiFi lock initialized")
            
            // Initialize wake lock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RayHunter::WiFiWakeLock")
            Log.d(TAG, "Wake lock initialized")
            
            // Initialize connectivity manager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            Log.d(TAG, "Connectivity manager initialized")
            
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
                
                Log.d(TAG, "Starting WiFi lock for SSID: $ssid")
                
                // Ensure notification channel is created before starting foreground
                createNotificationChannel()
                
                // Start foreground service with notification first
                try {
                    val notification = createNotification(ssid, url)
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Foreground service started with notification")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to start foreground service - notification permission missing: ${e.message}")
                    // Continue running but without foreground status (will be killed sooner by system)
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
                description = "Maintains WiFi connection for RayHunter device"
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
        
        // Intent for stop action
        val stopIntent = Intent(this, WiFiLockService::class.java).apply {
            action = ACTION_STOP
        }
        
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        Log.d(TAG, "Creating notification for SSID: $ssid")
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to $ssid")
            .setContentText("Tap to return")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                stopPendingIntent
            )
            .build()
            
        Log.d(TAG, "Notification created successfully")
        return notification
    }
    
    
    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed, releasing resources")
        
        cleanupResources()
        
        super.onDestroy()
    }
    
    private fun cleanupResources() {
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