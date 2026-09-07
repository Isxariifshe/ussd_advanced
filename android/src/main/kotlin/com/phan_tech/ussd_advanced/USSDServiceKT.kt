package com.phan_tech.ussd_advanced

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class USSDServiceKT : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        currentEvent = AccessibilityEvent.obtain(event)
        val ussd = USSDController
        val response = responseText(event)
        if (ussd.isRunning != true || (!isUssdWidget(event) && !hasCarrierTitle(response))) return
        if (isTransientMessage(response)) return
        when {
            isConfiguredMessage(event, USSDController.KEY_LOGIN) && !hasInput(event) -> finish(event, response, 0)
            isConfiguredMessage(event, USSDController.KEY_ERROR) -> finish(event, response, 0)
            !hasInput(event) -> finish(event, response, 0)
            ussd.sendType == true -> ussd.callbackMessage?.invoke(event)
            else -> USSDController.callbackInvoke.responseInvoke(event)
        }
    }

    private fun finish(event: AccessibilityEvent, response: String, buttonIndex: Int) {
        USSDController.stopRunning()
        USSDController.callbackInvoke.over(response)
        clickButton(event, buttonIndex)
    }

    private fun isUssdWidget(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase().orEmpty()
        val packageName = event.packageName?.toString()?.lowercase().orEmpty()
        return className.contains("alertdialog") || className.contains("ussd") ||
            packageName.contains("com.android.phone") || packageName.contains("telephony") ||
            packageName.contains("telecom") || packageName.contains("dialer")
    }

    private fun isConfiguredMessage(event: AccessibilityEvent, key: String): Boolean {
        val text = responseText(event).lowercase()
        return text.isNotEmpty() && USSDController.map[key].orEmpty()
            .any { text.contains(it.lowercase()) }
    }

    private fun isTransientMessage(response: String): Boolean {
        val normalized = response.lowercase().replace('…', '.').trim()
        return normalized.isEmpty() || normalized.contains("ussd code running") ||
            normalized.contains("ussd running") || normalized.contains("please wait") ||
            normalized.contains("processing request") || isCarrierTitleOnly(response)
    }

    private fun hasCarrierTitle(response: String): Boolean {
        val text = response.lowercase()
        return text.contains("telesom message") || text.contains("somtel message") ||
            text.contains("phone services")
    }

    private fun isCarrierTitleOnly(response: String): Boolean {
        val lines = response.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return lines.isNotEmpty() && lines.all {
            it == "telesom message" || it == "somtel message" || it == "phone services"
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private var currentEvent: AccessibilityEvent? = null

        @JvmStatic fun send(text: String) {
            currentEvent?.let { setText(it, text); clickButton(it, 1) }
        }

        @JvmStatic fun send2(text: String, event: AccessibilityEvent) {
            setText(event, text)
            clickButton(event, 1)
        }

        @JvmStatic fun cancel() { currentEvent?.let { clickButton(it, 0) } }
        @JvmStatic fun cancel2(event: AccessibilityEvent) { clickButton(event, 0) }

        private fun setText(event: AccessibilityEvent, data: String) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, data)
            }
            leaves(event).filter { it.className?.toString() == "android.widget.EditText" }.forEach { node ->
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    val clipboard = USSDController.context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("text", data))
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
        }

        private fun hasInput(event: AccessibilityEvent) = leaves(event)
            .any { it.className?.toString() == "android.widget.EditText" }

        private fun clickButton(event: AccessibilityEvent, index: Int) {
            leaves(event).filter { it.className?.toString()?.lowercase()?.contains("button") == true }
                .getOrNull(index)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun responseText(event: AccessibilityEvent): String {
            val values = linkedSetOf<String>()
            event.text.forEach { addText(values, it) }
            leaves(event).forEach { node ->
                addText(values, node.text)
                addText(values, node.contentDescription)
            }
            return values.joinToString("\n")
        }

        private fun addText(values: MutableSet<String>, value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.equals("null", ignoreCase = true) ||
                text.uppercase() in setOf("SEND", "CANCEL", "OK")) return
            values.add(text)
        }

        private fun leaves(event: AccessibilityEvent): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            collectLeaves(event.source, result)
            return result
        }

        private fun collectLeaves(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
            if (node == null) return
            if (node.childCount == 0) {
                result.add(node)
                return
            }
            repeat(node.childCount) { collectLeaves(node.getChild(it), result) }
        }
    }
}
