package com.lumicontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.lumicontrol.app.server.HttpServer
import com.lumicontrol.app.server.WebSocketHandler
import com.lumicontrol.app.discovery.UdpBroadcaster

class ProjectorService : Service() {

    private var httpServer: HttpServer? = null
    private var webSocketHandler: WebSocketHandler? = null
    private var udpBroadcaster: UdpBroadcaster? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        acquireWakeLock()
        startServers()
    }

    private fun startForeground() {
        val channelId = "projector_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "projector:wakelock"
        )
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun startServers() {
        httpServer = HttpServer(this, MainActivity.HTTP_PORT).apply { start() }
        webSocketHandler = WebSocketHandler(MainActivity.WS_PORT).apply { start() }
        udpBroadcaster = UdpBroadcaster(this, MainActivity.UDP_PORT).apply { start() }
    }

    override fun onDestroy() {
        httpServer?.stop()
        webSocketHandler?.stop()
        udpBroadcaster?.stop()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
