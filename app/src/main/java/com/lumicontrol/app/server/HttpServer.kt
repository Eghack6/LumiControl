package com.lumicontrol.app.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lumicontrol.app.CrashLogger
import com.lumicontrol.app.CursorOverlayService
import com.lumicontrol.app.MainActivity
import com.lumicontrol.app.ProjectorAccessibilityService
import com.lumicontrol.app.SettingsManager
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream

class HttpServer(private val ctx: Context, private val port: Int) : NanoHTTPD(port) {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRequestTime = 0L
    private var wasConnected = false
    private val disconnectRunnable = Runnable { checkDisconnect() }

    init {
        mainHandler.postDelayed(disconnectRunnable, 5000)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val now = System.currentTimeMillis()
            if (lastRequestTime != 0L && now - lastRequestTime > 12000) {
                // Reconnected after disconnect
                showToast("连接成功！")
            }
            lastRequestTime = now
            val prev = MainActivity.clientRequestCount.getAndIncrement()
            if (prev == 0) MainActivity.notifyClientConnected()
            when (session.uri) {
                "/api/status" -> jsonResponse(Response.Status.OK, statusJson())
                "/api/discover" -> jsonResponse(Response.Status.OK, discoverJson())
                "/api/touch" -> handleTouch(session)
                "/api/key" -> if (session.method == Method.POST) handleKey(session) else null
                "/api/text" -> handleText(session)
                "/api/reset" -> handleReset(session)
                "/api/install" -> if (session.method == Method.POST) handleInstall(session) else null
                "/api/settings" -> handleSettings(session)
                "/api/cursor-image" -> {
                    if (session.method == Method.POST) handleCursorImageUpload(session)
                    else serveCursorImage(session)
                }
                "/api/log" -> {
                    val log = CrashLogger.getLog()
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", log)
                }
                "/", "/index.html" -> serveAsset("index.html")
                "/style.css" -> serveAsset("style.css")
                "/app.js" -> serveAsset("app.js")
                else -> serveAsset(session.uri.removePrefix("/"))
            } ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        } catch (e: Exception) {
            android.util.Log.e("LumiControl", "serve error: ${session.uri}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500")
        }
    }

    private fun statusJson(): String {
        val accessibilityOn = ProjectorAccessibilityService.instance != null
        return """{"ip":"${MainActivity.serverIp}","port":$port,"accessibility":$accessibilityOn,"screenW":${CursorOverlayService.lastScreenW},"screenH":${CursorOverlayService.lastScreenH}}"""
    }

    private fun discoverJson(): String {
        return """{"name":"LumiControl","ip":"${MainActivity.serverIp}","http":${MainActivity.HTTP_PORT}}"""
    }

    /** Run task on main thread, block until done */
    private fun onMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try { task() } finally { latch.countDown() }
            }
            try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
    }

    private fun handleKey(session: IHTTPSession): Response {
        val body = parseBody(session)
        val action = body["action"] as? String ?: return errorResponse("missing action")
        val keyCode = getKeyCode(action) ?: return errorResponse("unknown action: $action")
        onMain {
            val svc = ProjectorAccessibilityService.instance
            if (svc != null) svc.injectKey(keyCode)
        }
        showToast("按键: $action")
        return if (ProjectorAccessibilityService.instance != null) okResponse()
            else errorResponse("无障碍服务未开启")
    }

    private fun handleText(session: IHTTPSession): Response {
        // Support both GET (query param) and POST (JSON body)
        val text = session.parameters["text"]?.firstOrNull()
            ?: (parseBody(session)["text"] as? String)
            ?: return errorResponse("missing text")
        var ok = false
        onMain {
            val svc = ProjectorAccessibilityService.instance
            if (svc != null) ok = svc.injectText(text)
        }
        if (ok) {
            showToast("文字已发送: $text")
            return okResponse()
        }
        // injectText always copies to clipboard — even if window injection failed
        showToast("文字已复制到剪贴板，请长按输入框粘贴")
        return okResponse()
    }

    private fun handleReset(session: IHTTPSession): Response {
        onMain { com.lumicontrol.app.CursorOverlayService.instance?.resetCursor() }
        showToast("光标已复位")
        return okResponse()
    }

    private fun handleTouch(session: IHTTPSession): Response {
        val params = session.parameters
        val type = params["type"]?.firstOrNull() ?: return errorResponse("missing type")
        val x = params["x"]?.firstOrNull()?.toFloatOrNull() ?: return errorResponse("missing x")
        val y = params["y"]?.firstOrNull()?.toFloatOrNull() ?: return errorResponse("missing y")

        onMain {
            com.lumicontrol.app.CursorOverlayService.instance?.moveCursor(x, y)
            if (type == "click") {
                ProjectorAccessibilityService.instance?.injectClick(x, y)
            } else if (type == "scroll") {
                val dx = params["dx"]?.firstOrNull()?.toFloatOrNull() ?: 0f
                val dy = params["dy"]?.firstOrNull()?.toFloatOrNull() ?: 0f
                ProjectorAccessibilityService.instance?.injectScroll(x, y, dx, dy)
            }
        }
        return okResponse()
    }

    private fun handleInstall(session: IHTTPSession): Response {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            showToast("请在设置中允许安装未知应用，然后重新上传")
            return errorResponse("需要安装权限，已跳转设置")
        }
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tmpPath = files["apk"] ?: return errorResponse("no apk file")
            val tmpFile = File(tmpPath)
            if (!tmpFile.exists()) return errorResponse("tmp file not found")
            val dest = File(ctx.cacheDir, "update.apk")
            tmpFile.copyTo(dest, overwrite = true)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            showToast("正在安装 APK...")
            okResponse()
        } catch (e: Exception) {
            errorResponse("install failed: ${e.message}")
        }
    }

    private fun handleSettings(session: IHTTPSession): Response {
        return if (session.method == Method.POST) {
            val body = parseBody(session)
            try {
                val json = gson.toJson(body)
                val newSettings = gson.fromJson(json, com.lumicontrol.app.AppSettings::class.java)
                onMain { SettingsManager.update(newSettings) }
                okResponse()
            } catch (_: Exception) {
                errorResponse("invalid settings")
            }
        } else {
            jsonResponse(Response.Status.OK, gson.toJson(SettingsManager.get()))
        }
    }

    private fun handleCursorImageUpload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tmpPath = files["image"] ?: return errorResponse("no image file")
            val tmpFile = File(tmpPath)
            if (!tmpFile.exists()) return errorResponse("file not found")
            val cursorDir = File(ctx.filesDir, "cursors")
            cursorDir.mkdirs()
            val dest = File(cursorDir, "custom_cursor.png")
            tmpFile.copyTo(dest, overwrite = true)
            val s = SettingsManager.get().copy(cursorShape = "custom")
            onMain { SettingsManager.update(s) }
            okResponse()
        } catch (e: Exception) {
            errorResponse("upload failed: ${e.message}")
        }
    }

    private fun serveCursorImage(session: IHTTPSession): Response {
        val file = File(File(ctx.filesDir, "cursors"), "custom_cursor.png")
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        return newChunkedResponse(Response.Status.OK, "image/png", file.inputStream())
    }

    private fun getKeyCode(action: String): Int? = when (action) {
        "home" -> android.view.KeyEvent.KEYCODE_HOME
        "back" -> android.view.KeyEvent.KEYCODE_BACK
        "menu" -> android.view.KeyEvent.KEYCODE_MENU
        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        "center" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
        "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
        "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        "power" -> android.view.KeyEvent.KEYCODE_POWER
        "settings" -> android.view.KeyEvent.KEYCODE_SETTINGS
        else -> null
    }

    private fun parseBody(session: IHTTPSession): Map<String, Any?> {
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) { return emptyMap() }
        val raw = files["postData"] ?: return emptyMap()
        return try {
            gson.fromJson(raw, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun checkDisconnect() {
        if (lastRequestTime == 0L) {
            mainHandler.postDelayed(disconnectRunnable, 5000)
            return
        }
        val elapsed = System.currentTimeMillis() - lastRequestTime
        if (elapsed > 12000 && wasConnected) {
            wasConnected = false
            showToast("连接已断开")
        } else if (elapsed <= 12000 && !wasConnected) {
            wasConnected = true
        }
        mainHandler.postDelayed(disconnectRunnable, 5000)
    }

    private fun serveAsset(path: String): Response {
        return try {
            val input: InputStream = ctx.assets.open("web/$path")
            val mime = when {
                path.endsWith(".html") -> "text/html; charset=utf-8"
                path.endsWith(".css") -> "text/css; charset=utf-8"
                path.endsWith(".js") -> "application/javascript; charset=utf-8"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".ico") -> "image/x-icon"
                else -> "text/plain"
            }
            newChunkedResponse(Response.Status.OK, mime, input)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun okResponse() = jsonResponse(Response.Status.OK, """{"ok":true}""")
    private fun errorResponse(msg: String) = jsonResponse(Response.Status.BAD_REQUEST, """{"ok":false,"error":"${msg.replace("\"", "'")}"}""")
    private fun jsonResponse(status: Response.Status, json: String) =
        newFixedLengthResponse(status, "application/json", json)
}
