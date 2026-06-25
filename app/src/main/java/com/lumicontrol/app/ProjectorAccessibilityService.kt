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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun injectClick(x: Float, y: Float) {
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
    }

    fun injectScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x + dx, y + dy)
            val stroke = GestureDescription.StrokeDescription(path, 0, 150)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun injectKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_MENU -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            else -> performNavigationAction(keyCode)
        }
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
            val action = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                KeyEvent.KEYCODE_DPAD_LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                KeyEvent.KEYCODE_DPAD_CENTER -> AccessibilityNodeInfo.ACTION_CLICK
                else -> return
            }
            // Try action on focused node; fall back to finding clickable
            if (!focused.performAction(action)) {
                findClickableNode(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        // Method 1: ACTION_SET_TEXT on focused/editable node
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val root = rootInActiveWindow
            if (root != null) {
                val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findEditableNode(root)
                if (target != null) {
                    val args = android.os.Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    return true
                }
            }
        }
        // Method 2: clipboard + ACTION_PASTE
        return injectTextViaClipboard(text)
    }

    private fun injectTextViaClipboard(text: String): Boolean {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LumiControl", text))
        } catch (_: Exception) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val root = rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableNode(root) ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            return true
        }
        return false
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
