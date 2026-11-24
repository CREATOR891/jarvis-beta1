package com.jarvis.beta.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба доступности: открытие приложений, кнопки Домой/Недавние,
 * шторка быстрых настроек, приём/сброс звонков.
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // --- Системные действия ---

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // --- Приложения ---

    fun openApp(pkg: String?, label: String): Boolean {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        // 1) точное совпадение пакета
        pkg?.let { p ->
            candidates.firstOrNull {
                it.activityInfo.packageName.equals(p, ignoreCase = true)
            }?.let { ri ->
                launch(ri.activityInfo.packageName, ri.activityInfo.name)
                return true
            }
        }
        // 2) совпадение по названию
        val needle = label.lowercase()
        candidates.firstOrNull { ri ->
            val l = ri.loadLabel(pm).toString().lowercase()
            l.contains(needle) || (needle.length >= 4 && needle.contains(l))
        }?.let { ri ->
            launch(ri.activityInfo.packageName, ri.activityInfo.name)
            return true
        }
        // 3) системные настройки
        if (label == "настройки") {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return true
        }
        return false
    }

    private fun launch(pkg: String, cls: String) {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // --- Звонки ---

    fun answerCall(): Boolean =
        clickCallButton(listOf("ответить", "ответ", "answer", "принять", "accept"))

    fun rejectCall(): Boolean =
        clickCallButton(
            listOf("сбросить", "отклонить", "decline", "reject", "end call", "отбой")
        ) || performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Ищет кнопку ответа/сброса на экране звонка по тексту,
     * contentDescription или id ресурса, и кликает её (или её кликабельного родителя).
     */
    private fun clickCallButton(keywords: List<String>): Boolean {
        val root = rootInActiveWindow
            ?: windows.firstOrNull()?.root
            ?: return false
        val node = findNode(root, keywords) ?: return false
        val target = if (node.isClickable) node else clickableParent(node) ?: node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase()
        if (text != null && keywords.any { text.contains(it) }) return node
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && keywords.any { desc.contains(it) }) return node
        val rid = node.viewIdResourceName?.lowercase()
        if (rid != null && keywords.any { rid.contains(it) }) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), keywords)?.let { return it }
        }
        return null
    }

    private fun clickableParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = n.parent
        while (p != null) {
            if (p.isClickable) return p
            p = p.parent
        }
        return null
    }
}
