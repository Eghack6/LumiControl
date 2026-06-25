package com.lumicontrol.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Process
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.lang.Thread.UncaughtExceptionHandler
import androidx.appcompat.app.AppCompatActivity
import com.lumicontrol.app.widget.QrCodeView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        const val HTTP_PORT = 4560
        const val WS_PORT = 4561
        const val UDP_PORT = 4562
        var serverIp: String = "0.0.0.0"
            private set

        /** Incremented by HttpServer when any request arrives */
        val clientRequestCount = AtomicInteger(0)
        var activity: MainActivity? = null

        /** Called by HttpServer on the very first HTTP request */
        fun notifyClientConnected() {
            activity?.runOnUiThread {
                Toast.makeText(activity, "连接成功！", Toast.LENGTH_SHORT).show()
                activity?.moveTaskToBack(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashLogger.init(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                CrashLogger.log("CRASH", "应用崩溃", throwable)
                val msg = "LumiControl崩溃退出\n${throwable.message ?: "未知错误"}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                Thread.sleep(2500)
            } catch (_: Exception) {}
            Process.killProcess(Process.myPid())
        }

        activity = this
        setContentView(R.layout.activity_main)
        startProjectorService()
        startCursorService()
        checkOverlayPermission()
        updateStatus()
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        CursorOverlayService.instance?.retry()
    }

    override fun onDestroy() {
        super.onDestroy()
        activity = null
    }

    private fun startProjectorService() {
        try {
            val intent = Intent(this, ProjectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "服务启动失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCursorService() {
        try {
            startService(Intent(this, CursorOverlayService::class.java))
        } catch (_: Exception) {}
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("LumiControl 需要悬浮窗权限来显示鼠标指针。是否前往设置开启？")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    fun updateStatus() {
        val ip = getLocalIpAddress()
        serverIp = ip
        val url = "http://$ip:$HTTP_PORT"
        findViewById<TextView>(R.id.tvIp)?.text = url
        findViewById<TextView>(R.id.tvQrHint)?.text = "请在同一 WiFi 下使用手机扫码"
        val qrView = findViewById<QrCodeView>(R.id.qrCode)
        qrView?.setData(url)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
