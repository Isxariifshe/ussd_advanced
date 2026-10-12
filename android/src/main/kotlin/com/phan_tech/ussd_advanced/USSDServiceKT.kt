package com.phan_tech.ussd_advanced

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class USSDServiceKT : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        currentEvent = AccessibilityEvent.obtain(event)
        val ussd = USSDController
        val response = responseText(event)
        if (ussd.isRunning != true || (!isUssdWidget(event) && !hasCarrierTitle(response))) return
        recordDiagnostic(this, "accessibility_event", mapOf(
            "eventType" to event.eventType.toString(),
            "package" to event.packageName?.toString().orEmpty(),
            "class" to event.className?.toString().orEmpty(),
            "windowId" to event.windowId.toString(),
            "textLength" to response.length.toString(),
            "hasInput" to hasInput(event).toString(),
            "responseKind" to responseKind(response),
        ))
        if (isTransientMessage(response)) return
        when {
            isConfiguredMessage(event, USSDController.KEY_LOGIN) && !hasInput(event) -> finish(event, response, 0)
            isConfiguredMessage(event, USSDController.KEY_ERROR) -> finish(event, response, 0)
            !hasInput(event) -> finish(event, response, 0)
            ussd.sendType == true -> {
                if (isNewPromptResponse(response)) ussd.callbackMessage?.invoke(event)
            }
            else -> USSDController.callbackInvoke.responseInvoke(event)
        }
    }

    private fun finish(event: AccessibilityEvent, response: String, buttonIndex: Int) {
        recordDiagnostic(this, "session_finished", mapOf(
            "textLength" to response.length.toString(),
            "responseKind" to responseKind(response),
            "hasInput" to hasInput(event).toString(),
        ))
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        recordDiagnostic(this, "accessibility_service_connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (serviceInstance === this) serviceInstance = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val DIAGNOSTIC_PREFS = "ussd_advanced_diagnostics"
        private const val DIAGNOSTIC_EVENTS_KEY = "events"
        private const val MAX_DIAGNOSTIC_EVENTS = 120
        private val diagnosticLock = Any()
        private var currentEvent: AccessibilityEvent? = null
        @Volatile private var serviceInstance: USSDServiceKT? = null
        @Volatile private var lastSubmittedPromptText: String = ""
        @Volatile private var lastInputFailureReason: String = ""

        @JvmStatic fun recordDiagnostic(
            context: Context,
            phase: String,
            details: Map<String, String> = emptyMap(),
        ) {
            try {
                synchronized(diagnosticLock) {
                    val prefs = context.applicationContext.getSharedPreferences(
                        DIAGNOSTIC_PREFS, Context.MODE_PRIVATE,
                    )
                    val events = JSONArray(prefs.getString(DIAGNOSTIC_EVENTS_KEY, "[]"))
                    val item = JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("phase", phase.take(80))
                    details.forEach { (key, value) -> item.put(key.take(60), value.take(240)) }
                    events.put(item)
                    while (events.length() > MAX_DIAGNOSTIC_EVENTS) events.remove(0)
                    prefs.edit().putString(DIAGNOSTIC_EVENTS_KEY, events.toString()).apply()
                }
            } catch (_: Exception) {
                // Diagnostics must never interrupt an active USSD session.
            }
        }

        @JvmStatic fun diagnosticReport(context: Context): Map<String, Any> {
            val prefs = context.applicationContext.getSharedPreferences(
                DIAGNOSTIC_PREFS, Context.MODE_PRIVATE,
            )
            val events = JSONArray(prefs.getString(DIAGNOSTIC_EVENTS_KEY, "[]"))
            val eventList = (0 until events.length()).mapNotNull { index ->
                events.optJSONObject(index)?.let { item ->
                    val event = linkedMapOf<String, Any>()
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        event[key] = item.opt(key) ?: ""
                    }
                    event
                }
            }
            return mapOf(
                "device" to mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "androidRelease" to Build.VERSION.RELEASE,
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "package" to context.packageName,
                ),
                "events" to eventList,
            )
        }

        private fun responseKind(response: String): String {
            val normalized = response.lowercase()
            return when {
                response.isBlank() -> "empty"
                normalized.contains("connection problem") || normalized.contains("invalid mmi") -> "network_or_mmi_error"
                normalized.contains("waxaad codsatay") || normalized.contains("riix 1") -> "confirmation_prompt"
                normalized.contains("successful") || normalized.contains("completed") || normalized.contains("guuleysatay") -> "success_like"
                else -> "text"
            }
        }

        @JvmStatic fun extractResponseText(event: AccessibilityEvent): String = responseText(event)

        @JvmStatic fun send(text: String) {
            currentEvent?.let { setText(it, text); clickButton(it, 1) }
        }

        @JvmStatic fun send2(text: String, event: AccessibilityEvent): Boolean {
            val root = activeUssdRoot(event)
            if (root == null) {
                lastInputFailureReason = "No active telephony accessibility window was available."
                recordDiagnostic(USSDController.context, "reply_failed_no_window")
                return false
            }
            lastSubmittedPromptText = screenText(root).normalizedPrompt()
            val inputNodes = nodes(root).filter { isTextInput(it) }
            if (inputNodes.isEmpty()) {
                lastInputFailureReason = "The active USSD window has no accessible text input. ${describeControls(root)}"
                recordDiagnostic(USSDController.context, "reply_failed_no_input", mapOf(
                    "rootPackage" to root.packageName?.toString().orEmpty(),
                    "rootClass" to root.className?.toString().orEmpty(),
                    "controls" to controlsSummary(root),
                ))
                return false
            }
            if (!setText(root, text)) {
                lastInputFailureReason = "Android did not accept text in the USSD input. ${describeControls(root)}"
                recordDiagnostic(USSDController.context, "reply_failed_set_text", mapOf(
                    "rootPackage" to root.packageName?.toString().orEmpty(),
                    "inputCount" to inputNodes.size.toString(),
                    "controls" to controlsSummary(root),
                ))
                return false
            }
            val buttonActivated = clickSendButton(root)
            val imeActionActivated = !buttonActivated && Build.VERSION.SDK_INT >= 30 &&
                inputNodes.any { input ->
                    input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            if (!buttonActivated && !imeActionActivated) {
                lastInputFailureReason = "The USSD input accepted the reply, but no Send/OK control could be activated. ${describeControls(root)}"
                recordDiagnostic(USSDController.context, "reply_failed_send_control", mapOf(
                    "rootPackage" to root.packageName?.toString().orEmpty(),
                    "inputCount" to inputNodes.size.toString(),
                    "buttonCount" to nodes(root).count { it.isClickable }.toString(),
                    "controls" to controlsSummary(root),
                ))
                return false
            }
            lastInputFailureReason = ""
            recordDiagnostic(USSDController.context, "reply_input_accepted", mapOf(
                "rootPackage" to root.packageName?.toString().orEmpty(),
                "inputCount" to inputNodes.size.toString(),
                "buttonActivated" to buttonActivated.toString(),
                "imeActionActivated" to imeActionActivated.toString(),
            ))
            return true
        }

        @JvmStatic fun getLastInputFailureReason(): String = lastInputFailureReason

        private fun isNewPromptResponse(response: String): Boolean {
            val current = response.normalizedPrompt()
            return current.isNotEmpty() && current != lastSubmittedPromptText
        }

        @JvmStatic fun cancel() { currentEvent?.let { clickButton(it, 0) } }
        @JvmStatic fun cancel2(event: AccessibilityEvent) { clickButton(event, 0) }

        /**
         * Dismiss only the currently-visible ZAAD class-0 message that contains
         * the exact OTP already captured and submitted by the host app.
         * Never send BACK to an arbitrary telephony dialog or active USSD form.
         */
        @JvmStatic fun dismissMatchedZaadFlashSms(otp: String, callback: (String) -> Unit) {
            if (!otp.matches(Regex("^[0-9]{6}$"))) {
                callback("INVALID_OTP")
                return
            }
            val service = serviceInstance
            if (service == null) {
                callback("ACCESSIBILITY_SERVICE_UNAVAILABLE")
                return
            }
            val root = service.rootInActiveWindow
            if (root == null) {
                callback("ACTIVE_WINDOW_UNAVAILABLE")
                return
            }
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName != "com.android.phone") {
                callback("ACTIVE_WINDOW_NOT_TELEPHONY")
                return
            }
            val text = rootText(root).lowercase()
            if (!text.contains("zaad services") ||
                !text.contains("access code") ||
                !text.contains(otp)) {
                callback("MATCHED_ZAAD_FLASH_NOT_VISIBLE")
                return
            }
            if (containsInput(root)) {
                callback("ACTIVE_TELEPHONY_INPUT_LEFT_UNTOUCHED")
                return
            }

            if (!service.performGlobalAction(GLOBAL_ACTION_BACK)) {
                callback("DISMISS_ACTION_REJECTED")
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                val after = service.rootInActiveWindow
                val stillVisible = after != null &&
                    after.packageName?.toString() == "com.android.phone" &&
                    rootText(after).lowercase().let { visible ->
                        visible.contains("zaad services") &&
                            visible.contains("access code") && visible.contains(otp)
                    }
                callback(if (stillVisible) "DISMISS_NOT_CONFIRMED" else "DISMISS_CONFIRMED")
            }, 400L)
        }

        private fun rootText(root: AccessibilityNodeInfo): String {
            val values = linkedSetOf<String>()
            collectText(root, values)
            return values.joinToString(" ")
        }

        private fun collectText(node: AccessibilityNodeInfo, values: MutableSet<String>) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            repeat(node.childCount) { index -> node.getChild(index)?.let { collectText(it, values) } }
        }

        private fun containsInput(root: AccessibilityNodeInfo): Boolean {
            if (root.className?.toString() == "android.widget.EditText") return true
            return (0 until root.childCount).any { index ->
                root.getChild(index)?.let(::containsInput) == true
            }
        }

        private fun setText(event: AccessibilityEvent, data: String): Boolean {
            return setText(event.source, data)
        }

        private fun setText(root: AccessibilityNodeInfo?, data: String): Boolean {
            if (root == null) return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, data)
            }
            val inputs = nodes(root).filter { isTextInput(it) }
            if (inputs.isEmpty()) return false
            return inputs.any { node ->
                val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (set) {
                    node.refresh()
                    if (node.text?.toString() == data) return@any true
                }
                val pasted = run {
                    val clipboard = USSDController.context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("text", data))
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
                node.refresh()
                pasted && node.text?.toString() == data
            }
        }

        private fun hasInput(event: AccessibilityEvent): Boolean {
            val root = activeUssdRoot(event)
            return if (root != null) nodes(root).any(::isTextInput)
            else leaves(event).any(::isTextInput)
        }

        private fun clickButton(event: AccessibilityEvent, index: Int): Boolean {
            val root = activeUssdRoot(event) ?: return false
            val buttons = nodes(root).filter {
                it.isClickable && it.className?.toString()?.lowercase()?.contains("button") == true
            }
            return buttons.getOrNull(index)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
            val allNodes = nodes(root)
            val sendLabels = setOf("send", "ok", "continue", "submit", "sii wad", "dir", "haye")
            val labelMatch = allNodes.firstOrNull { node ->
                val label = listOf(node.text, node.contentDescription)
                    .mapNotNull { it?.toString()?.trim()?.lowercase() }
                label.any { it in sendLabels } && clickNodeOrClickableParent(node)
            }
            if (labelMatch != null) return true

            val buttons = allNodes.filter {
                it.isClickable && it.className?.toString()?.lowercase()?.contains("button") == true
            }
            val positiveButton = buttons.firstOrNull {
                it.viewIdResourceName?.substringAfterLast('/') == "button1"
            }
            if (positiveButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                return true
            }
            if (buttons.size >= 2) {
                return buttons.last().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val onlyButton = buttons.singleOrNull() ?: return false
            val onlyLabel = onlyButton.text?.toString()?.trim()?.lowercase().orEmpty()
            if (onlyLabel in setOf("cancel", "back", "no", "bax")) return false
            return onlyButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
            var candidate: AccessibilityNodeInfo? = node
            repeat(5) {
                val current = candidate ?: return false
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                candidate = current.parent
            }
            return false
        }

        private fun activeUssdRoot(event: AccessibilityEvent): AccessibilityNodeInfo? {
            val expectedPackage = event.packageName?.toString()
            val service = serviceInstance
            val roots = mutableListOf<AccessibilityNodeInfo>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    service?.windows?.forEach { window -> window.root?.let(roots::add) }
                } catch (_: Exception) { }
            }
            try {
                service?.rootInActiveWindow?.let(roots::add)
            } catch (_: Exception) { }
            event.source?.let { source ->
                var root = source
                repeat(20) {
                    val parent = root.parent ?: return@repeat
                    root = parent
                }
                roots.add(root)
            }
            val telephonyRoots = roots.filter { root ->
                val packageName = root.packageName?.toString()?.lowercase().orEmpty()
                packageName.contains("phone") || packageName.contains("telephony") ||
                    packageName.contains("telecom") || packageName.contains("dialer") ||
                    (expectedPackage != null && packageName == expectedPackage.lowercase())
            }
            return telephonyRoots.firstOrNull { root ->
                nodes(root).any(::isTextInput)
            } ?: telephonyRoots.firstOrNull { root ->
                nodes(root).any { node ->
                    node.isClickable || !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank()
                }
            }
        }

        private fun isTextInput(node: AccessibilityNodeInfo): Boolean =
            node.className?.toString() == "android.widget.EditText" ||
                (Build.VERSION.SDK_INT >= 18 && node.isEditable)

        private fun describeControls(root: AccessibilityNodeInfo): String {
            val controls = nodes(root).mapNotNull { node ->
                val label = node.text?.toString()?.trim().orEmpty()
                    .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                if (label.isNotEmpty() && (node.isClickable || isTextInput(node))) {
                    "${node.className?.toString()?.substringAfterLast('.')}=$label"
                } else null
            }.take(8)
            val packageName = root.packageName?.toString().orEmpty()
            return "Window=$packageName/${root.className}; controls=${controls.joinToString(", ").ifEmpty { "none exposed" }}."
        }

        private fun controlsSummary(root: AccessibilityNodeInfo): String = nodes(root)
            .filter { it.isClickable || isTextInput(it) }
            .take(12)
            .joinToString(",") { node ->
                val label = listOf(node.text, node.contentDescription)
                    .mapNotNull { it?.toString()?.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.lowercase()
                    ?.let { value ->
                        when {
                            value.contains("send") || value.contains("dir") -> "send"
                            value.contains("ok") || value.contains("continue") -> "continue"
                            value.contains("cancel") || value.contains("bax") -> "cancel"
                            else -> "labeled"
                        }
                    } ?: "unlabeled"
                "${node.className?.toString()?.substringAfterLast('.')}[$label]"
            }.ifEmpty { "none" }

        private fun responseText(event: AccessibilityEvent): String {
            val values = linkedSetOf<String>()
            event.text.forEach { addText(values, it) }
            activeUssdRoot(event)?.let { root ->
                nodes(root).filterNot(::isTextInput).forEach { node ->
                    addText(values, node.text)
                    addText(values, node.contentDescription)
                }
            }
            return values.joinToString("\n")
        }

        private fun screenText(root: AccessibilityNodeInfo): String {
            val values = linkedSetOf<String>()
            nodes(root).filterNot(::isTextInput).forEach { node ->
                addText(values, node.text)
                addText(values, node.contentDescription)
            }
            return values.joinToString("\n")
        }

        private fun String.normalizedPrompt(): String =
            trim().lowercase().replace(Regex("\\s+"), " ")

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

        private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, result)
            return result
        }

        private fun collectNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
            if (node == null) return
            result.add(node)
            repeat(node.childCount) { collectNodes(node.getChild(it), result) }
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
