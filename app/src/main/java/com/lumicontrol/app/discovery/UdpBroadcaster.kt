package com.lumicontrol.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.lumicontrol.app.MainActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpBroadcaster(private val ctx: Context, private val port: Int) : Thread() {

    private var running = false
    private var socket: DatagramSocket? = null

    override fun run() {
        running = true
        socket = try { DatagramSocket(null) } catch (e: Exception) { null }
        socket?.reuseAddress = true

        while (running) {
            try {
                val ip = MainActivity.serverIp
                val json = """{"name":"LumiControl","ip":"$ip","http":${MainActivity.HTTP_PORT},"ws":${MainActivity.WS_PORT}}"""
                val data = json.toByteArray()
                val addr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, addr, port)
                socket?.send(packet)
                sleep(3000)
            } catch (e: Exception) {
                if (running) {
                    sleep(3000)
                }
            }
        }
    }

    fun stopBroadcast() {
        running = false
        socket?.close()
        interrupt()
    }
}
