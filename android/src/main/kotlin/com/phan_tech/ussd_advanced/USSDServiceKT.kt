package com.phan_tech.ussd_advanced

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class USSDServiceKT : AccessibilityService() {
    private var debugPromptWindowManager: WindowManager? = null
    private var debugPromptRoot: LinearLayout? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleAccessibilityEvent(event)
        } catch (failure: Throwable) {
            recordDiagnosticAndFlush(this, "accessibility_event_exception", mapOf(
                "exceptionType" to failure.javaClass.name,
                "messageLength" to (failure.message?.length ?: 0).toString(),
                "eventType" to event.eventType.toString(),
                "package" to event.packageName?.toString().orEmpty(),
                "class" to event.className?.toString().orEmpty(),
                "windowId" to event.windowId.toString(),
                "ussdRunning" to (USSDController.isRunning == true).toString(),
            ))
            throw failure
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val ussd = USSDController
        val response = responseText(event)
        val eventPackage = event.packageName?.toString().orEmpty()
        val likelyPhoneWindow = isUssdWidget(event) || hasCarrierTitle(response) ||
            eventPackage == "com.android.phone"
        if (ussd.isRunning != true) {
            if (likelyPhoneWindow) {
                recordDiagnostic(this, "phone_window_event_while_controller_idle", mapOf(
                    "eventType" to event.eventType.toString(),
                    "package" to eventPackage,
                    "class" to event.className?.toString().orEmpty(),
                    "windowId" to event.windowId.toString(),
                    "textLength" to response.length.toString(),
                ))
            }
            return
        }
        recordDiagnostic(this, "accessibility_callback_received", mapOf(
            "eventType" to event.eventType.toString(),
            "package" to eventPackage,
            "class" to event.className?.toString().orEmpty(),
            "windowId" to event.windowId.toString(),
            "textLength" to response.length.toString(),
            "responseKind" to responseKind(response),
        ))
        if (!isUssdWidget(event) && !hasCarrierTitle(response)) {
            recordDiagnostic(this, "accessibility_callback_filtered", mapOf(
                "reason" to "not_ussd_window_or_carrier_title",
                "eventType" to event.eventType.toString(),
                "package" to eventPackage,
                "class" to event.className?.toString().orEmpty(),
                "windowId" to event.windowId.toString(),
                "textLength" to response.length.toString(),
            ))
            return
        }
        // Android may temporarily expose only System UI (for example while the
        // notification shade is expanded). A missing root is not evidence that
        // the carrier session ended. Keep the session alive and wait for the
        // same telephony window to become accessible again.
        val root = activeUssdRoot(event)
        if (root == null) {
            recordDiagnostic(this, "ussd_prompt_temporarily_unavailable", mapOf(
                "windowId" to event.windowId.toString(),
                "package" to event.packageName?.toString().orEmpty(),
            ))
            return
        }
        // The emulator harness renders the fake carrier prompt as a native
        // LinearLayout accessibility overlay. Ignore its Flutter host window,
        // whose diagnostic text also contains "Telesom Message".
        if (event.packageName?.toString() == DEBUG_SIMULATOR_PACKAGE &&
            root.className?.toString() != "android.widget.LinearLayout") return
        val promptHasInput = nodes(root).any(::isTextInput)
        val expectedWindowId = sessionWindowId
        if (expectedWindowId != null && event.windowId != expectedWindowId) {
            val expectedPackage = sessionPackageName
            val eventPackage = event.packageName?.toString().orEmpty()
            // Android/carrier builds may replace the USSD dialog window between
            // prompts. Follow a replacement only when it is still the same
            // telephony package and exposes actual USSD content. Overlay events
            // (notification shade, another app, etc.) never rebind the session.
            val sameTelephonySession = expectedPackage != null &&
                eventPackage == expectedPackage &&
                (promptHasInput || response.isNotBlank())
            if (!sameTelephonySession) {
                recordDiagnostic(this, "unrelated_window_ignored", mapOf(
                    "eventWindowId" to event.windowId.toString(),
                    "sessionWindowId" to expectedWindowId.toString(),
                    "package" to eventPackage,
                ))
                return
            }
            sessionWindowId = event.windowId
            recordDiagnostic(this, "ussd_window_rebound", mapOf(
                "previousWindowId" to expectedWindowId.toString(),
                "windowId" to event.windowId.toString(),
                "package" to eventPackage,
                "hasInput" to promptHasInput.toString(),
            ))
        }
        // Some Android/carrier accessibility snapshots temporarily lose the
        // EditText immediately after Send while still exposing the exact old
        // prompt text. Treating that snapshot as terminal ends the session
        // before the provider's next prompt/receipt arrives (seen repeatedly
        // after PIN replies in production review traces). Keep the prior live
        // prompt bound and wait for a changed response or a real terminal result.
        if (ussd.sendType == true && !promptHasInput && response.isNotBlank() &&
            response.normalizedPrompt() == lastSubmittedPromptText.normalizedPrompt()) {
            recordDiagnostic(this, "stale_post_reply_prompt_ignored", mapOf(
                "windowId" to event.windowId.toString(),
                "responseLength" to response.length.toString(),
                "responseKind" to responseKind(response),
            ))
            return
        }
        currentEvent?.recycle()
        currentEvent = AccessibilityEvent.obtain(event)
        recordDiagnostic(this, "accessibility_event", mapOf(
            "eventType" to event.eventType.toString(),
            "package" to event.packageName?.toString().orEmpty(),
            "class" to event.className?.toString().orEmpty(),
            "windowId" to event.windowId.toString(),
            "textLength" to response.length.toString(),
            "hasInput" to promptHasInput.toString(),
            "responseKind" to responseKind(response),
        ))
        if (tryDeferredReply(event)) return
        if (isTransientMessage(response)) return
        when {
            isConfiguredMessage(event, USSDController.KEY_LOGIN) && !promptHasInput -> finish(event, response)
            isConfiguredMessage(event, USSDController.KEY_ERROR) -> finish(event, response)
            !promptHasInput -> finish(event, response)
            ussd.sendType == true -> {
                val sameTextNewPromptWindow =
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        promptHasInput && response.isNotBlank()
                if (isNewPromptResponse(response) || sameTextNewPromptWindow) {
                    if (sameTextNewPromptWindow && !isNewPromptResponse(response)) {
                        recordDiagnostic(this, "same_text_new_prompt_window", mapOf(
                            "windowId" to event.windowId.toString(),
                        ))
                    }
                    ussd.callbackMessage?.invoke(event)
                } else {
                    recordDiagnostic(this, "same_prompt_after_reply_ignored", mapOf(
                        "windowId" to event.windowId.toString(),
                    ))
                }
            }
            else -> USSDController.callbackInvoke.responseInvoke(event)
        }
    }

    private fun finish(event: AccessibilityEvent, response: String) {
        recordDiagnostic(this, "session_finished", mapOf(
            "textLength" to response.length.toString(),
            "responseKind" to responseKind(response),
            "hasInput" to hasInput(event).toString(),
            "dialogDismissed" to "false",
        ))
        USSDController.stopRunning()
        USSDController.callbackInvoke.over(response)
    }

    private fun isUssdWidget(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase().orEmpty()
        val packageName = event.packageName?.toString()?.lowercase().orEmpty()
        return className.contains("alertdialog") || className.contains("ussd") ||
            packageName.contains("com.android.phone") || packageName.contains("telephony") ||
            packageName.contains("telecom") || packageName.contains("dialer") ||
            (packageName == DEBUG_SIMULATOR_PACKAGE &&
                className.contains("linearlayout"))
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

    override fun onInterrupt() {
        recordDiagnosticAndFlush(this, "accessibility_service_interrupted", mapOf(
            "ussdRunning" to (USSDController.isRunning == true).toString(),
            "sessionWindowId" to (sessionWindowId?.toString() ?: "none"),
            "sessionPackage" to (sessionPackageName ?: "none"),
        ))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        recordDiagnostic(this, "accessibility_service_connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        recordDiagnosticAndFlush(this, "accessibility_service_unbound", mapOf(
            "ussdRunning" to (USSDController.isRunning == true).toString(),
            "sessionWindowId" to (sessionWindowId?.toString() ?: "none"),
            "sessionPackage" to (sessionPackageName ?: "none"),
        ))
        if (serviceInstance === this) serviceInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recordDiagnosticAndFlush(this, "accessibility_service_destroyed", mapOf(
            "ussdRunning" to (USSDController.isRunning == true).toString(),
            "sessionWindowId" to (sessionWindowId?.toString() ?: "none"),
            "sessionPackage" to (sessionPackageName ?: "none"),
        ))
        removeDebugPrompt(stopSimulator = false)
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }

    companion object {
        private const val DEBUG_SIMULATOR_PACKAGE = "com.phan_tech.ussd_advanced_example"
        private const val DIAGNOSTIC_PREFS = "ussd_advanced_diagnostics"
        private const val DIAGNOSTIC_EVENTS_KEY = "events"
        // Keep enough history for background SMS matching, queue gating, and
        // payout callbacks to be reviewed together after a long production run.
        private const val MAX_DIAGNOSTIC_EVENTS = 2000
        private val diagnosticLock = Any()
        private val deferredReplyLock = Any()
        private val deferredReplyHandler = Handler(Looper.getMainLooper())
        private data class DeferredReply(
            val attempt: (AccessibilityEvent) -> Boolean?,
            val onFailure: (String) -> Unit,
            val timeout: Runnable,
            val retry: Runnable,
        )
        @Volatile private var deferredReply: DeferredReply? = null
        private var currentEvent: AccessibilityEvent? = null
        @Volatile private var sessionWindowId: Int? = null
        @Volatile private var sessionPackageName: String? = null
        @Volatile private var serviceInstance: USSDServiceKT? = null
        @Volatile private var activeDiagnosticAttemptId: String = ""
        @Volatile private var lastSubmittedPromptText: String = ""
        @Volatile private var lastInputFailureReason: String = ""
        @Volatile private var lastRootUnavailableDiagnosticAt: Long = 0L

        @JvmStatic fun recordDiagnostic(
            context: Context,
            phase: String,
            details: Map<String, String> = emptyMap(),
            flushImmediately: Boolean = false,
        ) {
            writeDiagnostic(context, phase, details, flushImmediately = flushImmediately)
        }

        @JvmStatic fun isServiceBound(): Boolean = serviceInstance != null

        private fun recordDiagnosticAndFlush(
            context: Context,
            phase: String,
            details: Map<String, String> = emptyMap(),
        ) {
            writeDiagnostic(context, phase, details, flushImmediately = true)
        }

        private fun writeDiagnostic(
            context: Context,
            phase: String,
            details: Map<String, String>,
            flushImmediately: Boolean,
        ) {
            try {
                synchronized(diagnosticLock) {
                    details["attemptId"]?.takeIf { phase == "payout_attempt_started" }
                        ?.let { activeDiagnosticAttemptId = it.take(80) }
                    val prefs = context.applicationContext.getSharedPreferences(
                        DIAGNOSTIC_PREFS, Context.MODE_PRIVATE,
                    )
                    val events = JSONArray(prefs.getString(DIAGNOSTIC_EVENTS_KEY, "[]"))
                    val item = JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("phase", phase.take(80))
                    if (activeDiagnosticAttemptId.isNotBlank()) {
                        item.put("attemptId", activeDiagnosticAttemptId)
                    }
                    details.forEach { (key, value) ->
                        if (!isSensitiveDiagnosticKey(key)) {
                            item.put(key.take(60), value.take(240))
                        }
                    }
                    events.put(item)
                    while (events.length() > MAX_DIAGNOSTIC_EVENTS) events.remove(0)
                    val editor = prefs.edit().putString(DIAGNOSTIC_EVENTS_KEY, events.toString())
                    if (flushImmediately) editor.commit() else editor.apply()
                    if (phase == "payout_attempt_finished") activeDiagnosticAttemptId = ""
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
                        if (!isSensitiveDiagnosticKey(key)) {
                            event[key] = item.opt(key) ?: ""
                        }
                    }
                    event
                }
            }.toMutableList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                        as? ActivityManager
                    val lastExitTime = eventList
                        .filter { it["phase"] == "app_process_exit_history" }
                        .mapNotNull { (it["time"] as? Number)?.toLong() }
                        .maxOrNull() ?: 0L
                    val exits = activityManager?.getHistoricalProcessExitReasons(
                        context.packageName, 0, 12,
                    ).orEmpty()
                    exits.asSequence()
                        .filter { it.timestamp > lastExitTime }
                        .take(8)
                        .forEach { exit ->
                            eventList.add(linkedMapOf(
                                "time" to exit.timestamp,
                                "phase" to "app_process_exit_history",
                                "reason" to exitReasonName(exit.reason),
                                "reasonCode" to exit.reason.toString(),
                                "status" to exit.status.toString(),
                                "processName" to exit.processName.orEmpty(),
                                "description" to (exit.description ?: "").take(240),
                                "importance" to exit.importance.toString(),
                                "pssKb" to exit.pss.toString(),
                                "rssKb" to exit.rss.toString(),
                            ))
                        }
                } catch (_: Exception) {
                    // Process-exit history is best effort and must not block report export.
                }
            }
            val orderedEvents = eventList.sortedBy {
                (it["time"] as? Number)?.toLong() ?: 0L
            }.takeLast(MAX_DIAGNOSTIC_EVENTS)
            return mapOf(
                "generatedAt" to System.currentTimeMillis(),
                "device" to mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "androidRelease" to Build.VERSION.RELEASE,
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "package" to context.packageName,
                    "appVersion" to runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty(),
                    "appBuild" to runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                        }
                    }.getOrDefault(0L),
                    "accessibilityEnabledSetting" to USSDController.verifyAccessibilityAccess(context),
                    "accessibilityServiceBound" to isServiceBound(),
                    "ussdControllerRunning" to (USSDController.isRunning == true),
                    "activeSessionPackage" to (sessionPackageName ?: "none"),
                    "activeSessionWindowId" to (sessionWindowId?.toString() ?: "none"),
                ),
                "events" to orderedEvents,
            )
        }

        private fun isSensitiveDiagnosticKey(key: String): Boolean {
            val normalized = key.lowercase()
            if (normalized in setOf("responsekind", "responselength", "textlength", "codelength")) {
                return false
            }
            return normalized.contains("pin") || normalized.contains("secret") ||
                normalized.contains("recipient") || normalized.contains("phone") ||
                normalized.endsWith("number") || normalized.endsWith("name") ||
                normalized == "text" || normalized == "response" ||
                normalized.contains("body") || normalized.contains("dialstring") ||
                normalized == "ussdcode" || normalized == "message" ||
                normalized == "description"
        }

        private fun exitReasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
            ApplicationExitInfo.REASON_EXIT_SELF -> "app_exit_self"
            else -> "other"
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

        /** Test-only carrier dialog hosted by the AccessibilityService itself. */
        @JvmStatic fun showDebugPrompt(stage: Int): Boolean =
            serviceInstance?.showDebugPromptWindow(stage) == true

        @JvmStatic fun dismissDebugPrompt(): Boolean =
            serviceInstance?.removeDebugPrompt(stopSimulator = true) == true

        @JvmStatic fun beginSession(event: AccessibilityEvent) {
            sessionWindowId = event.windowId
            sessionPackageName = event.packageName?.toString()
            currentEvent?.recycle()
            currentEvent = AccessibilityEvent.obtain(event)
            recordDiagnostic(USSDController.context, "session_window_bound", mapOf(
                "windowId" to event.windowId.toString(),
                "package" to sessionPackageName.orEmpty(),
            ))
        }

        @JvmStatic fun terminalEventSnapshot(): AccessibilityEvent? =
            currentEvent?.let { AccessibilityEvent.obtain(it) }

        @JvmStatic fun endSession() {
            USSDController.stopRunning()
            sessionWindowId = null
            sessionPackageName = null
            synchronized(deferredReplyLock) {
                deferredReply?.let {
                    deferredReplyHandler.removeCallbacks(it.timeout)
                    deferredReplyHandler.removeCallbacks(it.retry)
                }
                deferredReply = null
            }
            currentEvent?.recycle()
            currentEvent = null
        }

        /** Retry an input only on a later, accessible event from this USSD session. */
        @JvmStatic fun deferReplyUntilAccessible(
            timeoutMs: Long,
            attempt: (AccessibilityEvent) -> Boolean?,
            onFailure: (String) -> Unit,
        ): Boolean {
            synchronized(deferredReplyLock) {
                if (deferredReply != null) return false
                lateinit var pending: DeferredReply
                val timeout = Runnable {
                    val shouldFail = synchronized(deferredReplyLock) {
                        if (deferredReply === pending) {
                            deferredReply = null
                            true
                        } else false
                    }
                    if (shouldFail) onFailure("The USSD prompt stayed inaccessible for ${timeoutMs / 1000} seconds.")
                }
                val retry = Runnable {
                    val stillPending = synchronized(deferredReplyLock) {
                        deferredReply === pending
                    }
                    if (!stillPending) return@Runnable
                    val freshEvent = currentEvent?.let { AccessibilityEvent.obtain(it) }
                    if (freshEvent != null) {
                        try { tryDeferredReply(freshEvent) } finally { freshEvent.recycle() }
                    }
                    if (synchronized(deferredReplyLock) { deferredReply === pending }) {
                        deferredReplyHandler.postDelayed(pending.retry, 500L)
                    }
                }
                pending = DeferredReply(attempt, onFailure, timeout, retry)
                deferredReply = pending
                deferredReplyHandler.postDelayed(timeout, timeoutMs)
                deferredReplyHandler.postDelayed(retry, 500L)
                recordDiagnostic(USSDController.context, "reply_deferred_until_window_accessible", mapOf(
                    "timeoutMs" to timeoutMs.toString(),
                ))
                return true
            }
        }

        private fun tryDeferredReply(event: AccessibilityEvent): Boolean {
            val pending = synchronized(deferredReplyLock) { deferredReply } ?: return false
            val outcome = try { pending.attempt(event) } catch (_: Exception) { false }
            if (outcome == null) return false
            synchronized(deferredReplyLock) {
                if (deferredReply === pending) {
                    deferredReply = null
                    deferredReplyHandler.removeCallbacks(pending.timeout)
                    deferredReplyHandler.removeCallbacks(pending.retry)
                }
            }
            if (outcome) {
                recordDiagnostic(thisOrContext(), "deferred_reply_input_accepted")
                return true
            }
            pending.onFailure(lastInputFailureReason.ifBlank { "Android could not submit the deferred USSD reply." })
            return true
        }

        private fun thisOrContext(): Context = USSDController.context

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
         * Close a final payout dialog after the app has matched the provider
         * receipt. Prefer an explicit Cancel control. If the carrier exposes
         * only Send/OK, use it only while the input is empty.
         */
        @JvmStatic fun dismissConfirmedPayoutPrompt(event: AccessibilityEvent): String {
            // The emulator's fake prompt is hosted by this accessibility
            // service rather than the carrier process, so it cannot always be
            // reacquired from AccessibilityService.windows after a final
            // callback. Keep the test-only dismissal isolated to that package.
            if (event.packageName?.toString() == DEBUG_SIMULATOR_PACKAGE) {
                val dismissed = dismissDebugPrompt()
                recordDiagnostic(USSDController.context,
                    if (dismissed) "debug_confirmed_prompt_dismissed" else "debug_confirmed_prompt_missing")
                return if (dismissed) "DISMISSED_DEBUG_SIMULATOR" else "DEBUG_PROMPT_UNAVAILABLE"
            }
            val root = activeUssdRoot(event) ?: return "FINAL_PROMPT_WINDOW_UNAVAILABLE"
            val controls = nodes(root).filter { it.isClickable }
            fun label(node: AccessibilityNodeInfo): String = listOf(
                node.text?.toString(), node.contentDescription?.toString(),
            ).filterNotNull().joinToString(" ").trim().lowercase()
            val cancelLabels = setOf("cancel", "bax", "jooji", "close")
            val cancel = controls.firstOrNull { control ->
                label(control).let { value -> cancelLabels.any(value::contains) }
            }
            if (cancel != null && clickNodeOrClickableParent(cancel)) {
                recordDiagnostic(USSDController.context, "confirmed_final_prompt_dismissed", mapOf(
                    "method" to "cancel_control",
                ))
                return "DISMISSED_CANCEL"
            }

            val hasNonEmptyInput = nodes(root).filter(::isTextInput)
                .any { !it.text.isNullOrBlank() }
            if (hasNonEmptyInput) return "FINAL_PROMPT_INPUT_NOT_EMPTY"
            val sendLabels = setOf("send", "ok", "continue", "submit", "dir", "sii wad")
            val send = controls.firstOrNull { control ->
                label(control).let { value -> sendLabels.any(value::contains) }
            }
            if (send != null && clickNodeOrClickableParent(send)) {
                recordDiagnostic(USSDController.context, "confirmed_final_prompt_dismissed", mapOf(
                    "method" to "empty_send_or_ok_control",
                ))
                return "DISMISSED_SEND_OR_OK"
            }
            recordDiagnostic(USSDController.context, "confirmed_final_prompt_dismiss_failed", mapOf(
                "method" to "no_safe_dismiss_control",
            ))
            return "FINAL_PROMPT_DISMISS_CONTROL_UNAVAILABLE"
        }

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
            val expectedWindowId = event.windowId
            val service = serviceInstance
            val roots = mutableListOf<AccessibilityNodeInfo>()
            val availableWindows = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    service?.windows?.forEach { window ->
                        val root = window.root
                        if (root != null) {
                            roots.add(root)
                            availableWindows.add("${root.windowId}:${root.packageName}")
                        }
                    }
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
                root.windowId == expectedWindowId && (
                    packageName.contains("phone") || packageName.contains("telephony") ||
                    packageName.contains("telecom") || packageName.contains("dialer") ||
                    (expectedPackage != null && packageName == expectedPackage.lowercase())
                )
            }
            return telephonyRoots.firstOrNull { root ->
                nodes(root).any(::isTextInput)
            } ?: telephonyRoots.firstOrNull { root ->
                nodes(root).any { node ->
                    node.isClickable || !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank()
                }
            }.also { result ->
                if (result == null) {
                    val now = System.currentTimeMillis()
                    if (now - lastRootUnavailableDiagnosticAt >= 1_000L) {
                        lastRootUnavailableDiagnosticAt = now
                        recordDiagnostic(
                            USSDController.context,
                            "ussd_window_root_unavailable",
                            mapOf(
                                "expectedWindowId" to expectedWindowId.toString(),
                                "expectedPackage" to expectedPackage.orEmpty(),
                                "availableWindows" to availableWindows.joinToString("|").take(240),
                                "serviceConnected" to (service != null).toString(),
                            ),
                        )
                    }
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
            val root = activeUssdRoot(event)
            if (root == null) {
                event.text.forEach { addText(values, it) }
            } else {
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

    private fun showDebugPromptWindow(stage: Int): Boolean {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return false
        }
        val manager = debugPromptWindowManager
            ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        debugPromptWindowManager = manager
        debugPromptRoot?.let { try { manager.removeView(it) } catch (_: Exception) { } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 26)
            setBackgroundColor(Color.rgb(72, 62, 42))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(TextView(this).apply {
            text = "Telesom Message"
            textSize = 19f
            setTextColor(Color.WHITE)
        })
        val body = when (stage) {
            0 -> "Telesom Message\nFadlan Geli PIN-kaaga"
            1 -> "Telesom Message\nHadhaagaaga Cusub waa:\n0 Next"
            3 -> "Telesom Message\nFadlan Geli PIN-kaaga"
            else -> "Telesom Message\n500 Shilling ayaad u dirtay TEST RECIPIENT (659038808). Tix: SIMTEST1020. Hadhaagaaga Cusub waa: 984. 0 Next"
        }
        val promptBody = TextView(this).apply {
            text = body
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 18)
        }
        root.addView(promptBody)

        if (stage == 0) {
            root.addView(EditText(this).apply {
                hint = "PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            })
            root.addView(Button(this).apply {
                text = "Send"
                // Reproduce a carrier/Android accessibility snapshot that
                // briefly loses its input while retaining the submitted PIN
                // prompt text, then the actual next prompt.
                setOnClickListener { showDebugPromptWindow(3) }
            })
        } else if (stage == 1) {
            root.addView(EditText(this).apply {
                hint = "Reply"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            })
            root.addView(Button(this).apply {
                text = "Send"
                setOnClickListener { showDebugPromptWindow(2) }
            })
        } else if (stage == 2) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@USSDServiceKT).apply {
                    text = "Cancel"
                    setOnClickListener { removeDebugPrompt(stopSimulator = true) }
                })
                addView(Button(this@USSDServiceKT).apply {
                    text = "OK"
                    setOnClickListener { removeDebugPrompt(stopSimulator = true) }
                })
            })
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            title = "Telesom Message"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        return try {
            manager.addView(root, params)
            debugPromptRoot = root
            if (stage == 3) {
                recordDiagnostic(this, "debug_stale_prompt_snapshot_shown")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (debugPromptRoot === root) {
                        promptBody.text = "Telesom Message\nHadhaagaaga Cusub waa:\n0 Next"
                        val input = EditText(this).apply {
                            hint = "Reply"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            setTextColor(Color.WHITE)
                            setHintTextColor(Color.LTGRAY)
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        }
                        root.addView(input)
                        root.addView(Button(this).apply {
                            text = "Send"
                            setOnClickListener { showDebugPromptWindow(2) }
                        })
                        recordDiagnostic(this, "debug_next_prompt_shown")
                        promptBody.sendAccessibilityEvent(
                            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                        )
                        root.sendAccessibilityEvent(
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        )
                        root.sendAccessibilityEvent(
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        )
                    }
                }, 700L)
            }
            true
        } catch (error: Exception) {
            recordDiagnostic(this, "debug_prompt_overlay_failed", mapOf(
                "errorType" to error.javaClass.simpleName,
            ))
            false
        }
    }

    private fun removeDebugPrompt(stopSimulator: Boolean): Boolean {
        val root = debugPromptRoot
        debugPromptRoot = null
        if (root != null) {
            try { debugPromptWindowManager?.removeView(root) } catch (_: Exception) { }
        }
        if (stopSimulator) {
            stopService(Intent().setClassName(
                packageName,
                "com.phan_tech.ussd_advanced_example.DebugUssdSimulatorService",
            ))
        }
        return root != null
    }
}
