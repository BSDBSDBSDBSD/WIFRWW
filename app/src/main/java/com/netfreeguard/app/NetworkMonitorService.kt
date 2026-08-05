package com.netfreeguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class NetworkMonitorService : Service() {

    private lateinit var connectivityManager: ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = recheck()
        override fun onLost(network: Network) = recheck()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        startForeground(1, buildNotification("בודק את מצב הרשת..."))
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        recheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recheck() {
        Thread {
            val filtered = FilterCheck.isNetworkFiltered(applicationContext)
            mainHandler.post {
                if (filtered) {
                    stopBlockVpn()
                    updateNotification("הרשת מסוננת ✔ - גישה מותרת")
                } else {
                    startBlockVpn()
                    updateNotification("הרשת לא מסוננת ✖ - אינטרנט חסום")
                }
            }
        }.start()
    }

    private fun startBlockVpn() {
        if (VpnService.prepare(applicationContext) != null) {
            // אין הרשאת VPN - לא ניתן לחסום. מוצג בהתראה כדי שהמשתמש ידע.
            updateNotification("חסר אישור VPN - יש לפתוח את האפליקציה ולאשר")
            return
        }
        val intent = Intent(this, LocalBlockVpnService::class.java).apply {
            action = LocalBlockVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopBlockVpn() {
        val intent = Intent(this, LocalBlockVpnService::class.java).apply {
            action = LocalBlockVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "netfree_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Netfree Guard", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Netfree Guard פעיל")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }
}
