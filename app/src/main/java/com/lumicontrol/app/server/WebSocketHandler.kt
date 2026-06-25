package com.lumicontrol.app.server

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.lumicontrol.app.CursorOverlayService
import com.lumicontrol.app.ProjectorAccessibilityService
import com.google.gson.Gson
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import java.io.IOException

class WebSocketHandler(port: Int) : NanoWSD(port) {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return TouchWebSocket(handshake)
    }

    /** Run task on main thread without blocking */
    private fun onMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post(task)
        }
    }

    inner class TouchWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {}
        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {}
        override fun onPong(frame: WebSocketFrame?) {}
        override fun onException(e: IOException?) {}

        override fun onMessage(frame: WebSocketFrame) {
            val text = frame.textPayload ?: return
            handleMessage(text)
        }

        private fun handleMessage(json: String) {
            try {
                @Suppress("UNCHECKED_CAST")
                val msg = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return
                val type = msg["type"] as? String ?: return
                val x = (msg["x"] as? Number)?.toFloat()
                val y = (msg["y"] as? Number)?.toFloat()

                onMain {
                    val svc = ProjectorAccessibilityService.instance
                    val cursor = CursorOverlayService.instance

                    when (type) {
                        "pos" -> {
                            if (x != null && y != null) cursor?.moveCursor(x, y)
                        }
                        "move" -> {
                            val dx = (msg["dx"] as? Number)?.toFloat() ?: 0f
                            val dy = (msg["dy"] as? Number)?.toFloat() ?: 0f
                            cursor?.moveCursorBy(dx, dy)
                        }
                        "click" -> {
                            val cx = x ?: cursor?.getCursorPosition()?.first ?: return@onMain
                            val cy = y ?: cursor?.getCursorPosition()?.second ?: return@onMain
                            cursor?.moveCursor(cx, cy)
                            svc?.injectClick(cx, cy)
                        }
                        "scroll" -> {
                            if (x != null && y != null) {
                                val dx = (msg["dx"] as? Number)?.toFloat() ?: 0f
                                val dy = (msg["dy"] as? Number)?.toFloat() ?: 0f
                                svc?.injectScroll(x, y, dx, dy)
                            }
                        }
                        "key" -> {
                            val action = msg["action"] as? String ?: return@onMain
                            val keyCode = getKeyCode(action) ?: return@onMain
                            svc?.injectKey(keyCode)
                        }
                        "reset" -> {
                            cursor?.resetCursor()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        private fun getKeyCode(action: String): Int? = when (action) {
            "home" -> KeyEvent.KEYCODE_HOME
            "back" -> KeyEvent.KEYCODE_BACK
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "center" -> KeyEvent.KEYCODE_DPAD_CENTER
            "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "menu" -> KeyEvent.KEYCODE_MENU
            "settings" -> KeyEvent.KEYCODE_SETTINGS
            else -> null
        }
    }
}
