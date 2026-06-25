package com.lumicontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ProjectorAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ProjectorAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun injectMouseMove(x: Float, y: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                path.moveTo(x, y)
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("LumiControl", "injectMouseMove failed", e)
        }
    }

    fun injectClick(x: Float, y: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                path.moveTo(x, y)
                path.lineTo(x, y)  // ≥2 points required by GestureDescription
                val stroke = GestureDescription.StrokeDescription(path, 0, 120)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()
                dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("LumiControl", "injectClick failed", e)
        }
    }

    fun injectScroll(x: Float, y: Float, dx: Float, dy: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                val sx = dx * 8
                val sy = dy * 8
                path.moveTo(x, y)
                path.lineTo(x + sx, y + sy)
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()
                dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("LumiControl", "injectScroll failed", e)
        }
    }

    fun injectKey(keyCode: Int) {
        try {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                KeyEvent.KEYCODE_MENU -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                else -> dispatchKeyEvent(keyCode)
            }
        } catch (e: Exception) {
            android.util.Log.e("LumiControl", "injectKey failed: $keyCode", e)
        }
    }

    private fun dispatchKeyEvent(keyCode: Int) {
        val cmds = arrayOf(
            arrayOf("sh", "-c", "input keyevent $keyCode"),
            arrayOf("/system/bin/sh", "-c", "input keyevent $keyCode"),
            arrayOf("sh", "-c", "cmd input keyevent $keyCode"),
        )
        for (cmd in cmds) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                Thread { try { p.inputStream.bufferedReader().readText() } catch (_: Exception) {} }.start()
                Thread { try { p.errorStream.bufferedReader().readText() } catch (_: Exception) {} }.start()
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                return
            } catch (_: Exception) {}
        }
        dispatchDpadGesture(keyCode)
    }

    @Suppress("DEPRECATION")
    private fun dispatchDpadGesture(keyCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val dist = 200f
        val (tx, ty) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Pair(cx, cy - dist)
            KeyEvent.KEYCODE_DPAD_DOWN -> Pair(cx, cy + dist)
            KeyEvent.KEYCODE_DPAD_LEFT -> Pair(cx - dist, cy)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Pair(cx + dist, cy)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                performNavigationAction(keyCode)
                return
            }
            else -> return
        }
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun performNavigationAction(keyCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val root = rootInActiveWindow ?: return
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        findClickableNode(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                else -> return
            }
        }
    }

    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNode(child)
            if (result != null) return result
        }
        return null
    }

    fun injectText(text: String): Boolean {
        // Always copy to clipboard first (even if other methods fail)
        copyToClipboard(text)
        // Method 1: ACTION_SET_TEXT on focused/editable node
        if (trySetText(text)) return true
        // Method 2: clipboard + ACTION_PASTE
        return tryPasteFromClipboard()
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LumiControl", text))
        } catch (_: Exception) {}
    }

    private fun trySetText(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return true
    }

    private fun tryPasteFromClipboard(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return true
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }
}
