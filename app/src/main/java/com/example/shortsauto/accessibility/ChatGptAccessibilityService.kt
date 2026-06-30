package com.example.shortsauto.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ChatGptAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: ChatGptAccessibilityService? = null
        @Volatile private var latestObservedScreenText: String = ""

        fun isConnected(): Boolean = instance != null

        fun isChatGptWindowActive(): Boolean {
            val service = instance ?: return false
            val packageName = service.rootInActiveWindow?.packageName?.toString().orEmpty()
            return packageName.contains("openai", ignoreCase = true) ||
                packageName.contains("chrome", ignoreCase = true)
        }

        fun setPromptText(prompt: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            if (!isChatGptWindowActive()) return false
            val input = service.findEditableNode(root) ?: return false
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
            }
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun clickSend(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            if (!isChatGptWindowActive()) return false
            val node = service.findSendNode(root)
            if (node != null) return service.clickNodeOrParent(node)
            return service.tapRelative(0.92f, 0.92f)
        }

        fun hasGeneratedImage(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            if (!isChatGptWindowActive()) return false
            return service.findImageBounds(root) != null && !service.hasLoadingText(root)
        }

        fun captureGeneratedImage(): Bitmap? {
            val service = instance ?: return null
            if (!isChatGptWindowActive()) return null
            val screenshot = service.takeWindowScreenshot() ?: return null
            val root = service.rootInActiveWindow
            val bounds = root?.let { service.findImageBounds(it) }
            if (bounds != null && bounds.width() > 80 && bounds.height() > 80) {
                val safe = Rect(
                    bounds.left.coerceIn(0, screenshot.width - 1),
                    bounds.top.coerceIn(0, screenshot.height - 1),
                    bounds.right.coerceIn(1, screenshot.width),
                    bounds.bottom.coerceIn(1, screenshot.height)
                )
                if (safe.width() > 80 && safe.height() > 80) {
                    return Bitmap.createBitmap(screenshot, safe.left, safe.top, safe.width(), safe.height())
                }
            }
            return screenshot
        }

        fun fallbackTapSend(): Boolean = instance?.tapRelative(0.92f, 0.92f) ?: false

        fun readCurrentScreenText(): String {
            val service = instance ?: return ""
            val root = service.rootInActiveWindow
            val activeText = root?.let { service.extractVisibleText(it) }.orEmpty()
            return listOf(latestObservedScreenText, activeText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(separator = "\n")
        }
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (!packageName.contains("openai", ignoreCase = true) &&
            !packageName.contains("chrome", ignoreCase = true)
        ) {
            return
        }
        val root = rootInActiveWindow ?: return
        val text = extractVisibleText(root)
        if (text.length > 80) latestObservedScreenText = text
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        fun visit(current: AccessibilityNodeInfo) {
            val label = listOfNotNull(current.text, current.contentDescription, current.viewIdResourceName)
                .joinToString(" ")
                .lowercase()
            val className = current.className?.toString().orEmpty()
            val editableLike = current.isEditable ||
                className.contains("EditText", ignoreCase = true) ||
                label.contains("message") ||
                label.contains("prompt") ||
                label.contains("메시지")
            val looksLikeBrowserChrome = label.contains("search or type web address") ||
                label.contains("address bar") ||
                label.contains("검색 또는 주소 입력") ||
                label.contains("url")

            if (editableLike && current.isEnabled && !looksLikeBrowserChrome) {
                val rect = Rect()
                current.getBoundsInScreen(rect)
                var score = rect.top
                if (label.contains("message") || label.contains("메시지")) score += 10_000
                if (label.contains("prompt")) score += 5_000
                if (current.isEditable) score += 2_000
                if (rect.width() > rect.height()) score += 500
                if (score > bestScore) {
                    bestScore = score
                    bestNode = current
                }
            }

            for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
        }

        visit(node)
        return bestNode
    }

    private fun findSendNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
            .joinToString(" ")
            .lowercase()
        val className = node.className?.toString().orEmpty()
        val looksLikeSend = label.contains("send") ||
            label.contains("전송") ||
            label.contains("제출") ||
            label.contains("arrow up") ||
            label.contains("submit")
        if (node.isEnabled && (node.isClickable || node.isFocusable) && looksLikeSend) return node
        if (node.isEnabled && node.isClickable && className.contains("Button", ignoreCase = true) && looksLikeSend) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findSendNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = current ?: return@repeat
            if (candidate.isEnabled && candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun findImageBounds(node: AccessibilityNodeInfo): Rect? {
        var best: Rect? = null
        fun visit(current: AccessibilityNodeInfo) {
            val rect = Rect()
            current.getBoundsInScreen(rect)
            val label = listOfNotNull(current.text, current.contentDescription, current.viewIdResourceName)
                .joinToString(" ")
                .lowercase()
            val className = current.className?.toString().orEmpty()
            val isImage = className.contains("Image", ignoreCase = true) ||
                label.contains("image") ||
                label.contains("이미지") ||
                label.contains("generated")
            if (isImage && rect.width() >= 120 && rect.height() >= 120) {
                val previous = best
                if (previous == null || rect.width() * rect.height() > previous.width() * previous.height()) {
                    best = Rect(rect)
                }
            }
            for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
        }
        visit(node)
        return best
    }

    private fun hasLoadingText(node: AccessibilityNodeInfo): Boolean {
        val text = StringBuilder()
        fun visit(current: AccessibilityNodeInfo) {
            current.text?.let { text.append(it).append(' ') }
            current.contentDescription?.let { text.append(it).append(' ') }
            for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
        }
        visit(node)
        val value = text.toString().lowercase()
        return value.contains("generating") ||
            value.contains("creating") ||
            value.contains("생성 중") ||
            value.contains("로딩") ||
            value.contains("stop generating")
    }

    private fun extractVisibleText(node: AccessibilityNodeInfo): String {
        val lines = linkedSetOf<String>()
        fun visit(current: AccessibilityNodeInfo) {
            listOfNotNull(current.text, current.contentDescription)
                .map { it.toString().trim() }
                .filter { it.isNotBlank() }
                .forEach { lines += it }
            for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
        }
        visit(node)
        return lines.joinToString(separator = "\n")
    }

    private fun tapRelative(xFraction: Float, yFraction: Float): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels * xFraction, metrics.heightPixels * yFraction)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun takeWindowScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    result.set(bitmap?.copy(Bitmap.Config.ARGB_8888, false))
                    screenshot.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }
}
