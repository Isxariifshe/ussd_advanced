package com.phan_tech.ussd_advanced

import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.UssdResponseCallback
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.StringCodec
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean


/** UssdAdvancedPlugin */
class UssdAdvancedPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, BasicMessageChannel.MessageHandler<String?>  {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private var context: Context? = null
  private var activity: Activity? = null
  private val ussdApi: USSDApi = USSDController
  private var event: AccessibilityEvent? = null
  private val pendingReplyLock = Any()
  private var pendingSendReply: BasicMessageChannel.Reply<String?>? = null


  private lateinit var basicMessageChannel: BasicMessageChannel<String>


  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "method.com.phan_tech/ussd_advanced")
    channel.setMethodCallHandler(this)
    this.context = flutterPluginBinding.applicationContext
    basicMessageChannel  = BasicMessageChannel(
      flutterPluginBinding.binaryMessenger,
      "message.com.phan_tech/ussd_advanced", StringCodec.INSTANCE
    )
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    activity = null
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  private fun setListener(){
    basicMessageChannel.setMessageHandler(this)
  }

  override fun onMessage(message: String?, reply: BasicMessageChannel.Reply<String?>) {
    context?.let { USSDServiceKT.recordDiagnostic(it, "confirmation_reply_received", mapOf(
      "replyLength" to (message?.length ?: 0).toString(),
    )) }
    if(message == null){
      reply.reply("USSD_AUTOMATION_INPUT_FAILED: Confirmation reply was empty.")
      return
    }
    val activeEvent = event
    if(activeEvent == null){
      reply.reply("USSD_AUTOMATION_INPUT_FAILED: No active USSD prompt is available.")
      return
    }
    synchronized(pendingReplyLock) {
      // A previous sendMessage call that never received a carrier response
      // must not steal the reply for a new confirmation.
      pendingSendReply?.reply("USSD_AUTOMATION_INPUT_FAILED: Previous USSD response was not received.")
      pendingSendReply = reply
    }
    val submitted = USSDController.send2(message, activeEvent){
        event = AccessibilityEvent.obtain(it)
        val response = USSDServiceKT.extractResponseText(it)
        if (response.isNotBlank()) {
          completePendingSendReply("USSD_AUTOMATION_PROMPT_PENDING: $response")
        }

    }
    if (!submitted) {
      val reason = USSDServiceKT.getLastInputFailureReason()
      if (reason.startsWith("No active telephony accessibility window")) {
        val deferred = USSDServiceKT.deferReplyUntilAccessible(
          timeoutMs = 20_000L,
          attempt = { freshEvent ->
            val accepted = USSDController.send2(message, freshEvent) {
              event?.recycle()
              event = AccessibilityEvent.obtain(it)
              val response = USSDServiceKT.extractResponseText(it)
              if (response.isNotBlank()) {
                completePendingSendReply("USSD_AUTOMATION_PROMPT_PENDING: $response")
              }
            }
            if (accepted) true
            else if (USSDServiceKT.getLastInputFailureReason()
                .startsWith("No active telephony accessibility window")) null
            else false
          },
          onFailure = { failure ->
            completePendingSendReply("USSD_AUTOMATION_INPUT_FAILED: $failure")
          },
        )
        if (deferred) return
      }
      completePendingSendReply(
        "USSD_AUTOMATION_INPUT_FAILED: ${reason.ifBlank { "Could not enter the reply and activate the USSD Send button." }}"
      )
    }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    var subscriptionId:Int = 1
    var code:String? = ""

    if(call.method == "sendUssd" ||call.method == "sendAdvancedUssd" ||call.method == "multisessionUssd"){
      val subscriptionIdInteger = call.argument<Int>("subscriptionId")
        ?: throw RequestParamsException(
          "Incorrect parameter type: `subscriptionId` must be an int"
        )
      subscriptionId = subscriptionIdInteger
      if (subscriptionId < -1 ) {
        throw RequestParamsException(
          "Incorrect parameter value: `subscriptionId` must be >= -1"
        )
      }
      code = call.argument<String>("code")
      if (code == null) {
        throw RequestParamsException("Incorrect parameter type: `code` must be a String")
      }
      if (code.isEmpty()) {
        throw RequestParamsException(
          "Incorrect parameter value: `code` must not be an empty string"
        )
      }
    }

    when (call.method) {
      "hasPermissions" -> {
        result.success(hasPermissions())

      }
      "requestPermissions" -> {
          requestPermissions()
        result.success(null)

      }
      "getDiagnostics" -> {
        val appContext = context?.applicationContext
        result.success(
          if (appContext == null) emptyMap<String, Any>()
          else USSDServiceKT.diagnosticReport(appContext),
        )
      }
      "recordAppDiagnostic" -> {
        val appContext = context?.applicationContext
        if (appContext != null) {
          val phase = call.argument<String>("phase") ?: "app_diagnostic"
          val details = call.argument<Map<String, Any?>>("details").orEmpty()
            .mapNotNull { (key, value) ->
              if (value == null || value is Map<*, *> || value is List<*>) null
              else key to value.toString().take(240)
            }.toMap()
          val flush = call.argument<Boolean>("flush") == true ||
            phase in setOf("flutter_uncaught_error", "flutter_framework_error",
              "payout_exception", "payout_attempt_finished")
          USSDServiceKT.recordDiagnostic(appContext, phase, details, flushImmediately = flush)
        }
        result.success(null)
      }
      "shareDiagnostics" -> {
        val appContext = context?.applicationContext
        if (appContext == null) {
          result.error("DIAGNOSTICS_UNAVAILABLE", "Application context unavailable", null)
          return
        }
        var stage = "start"
        try {
          USSDServiceKT.recordDiagnostic(appContext, "diagnostics_share_native_started")
          stage = "serialize_report"
          val report = JSONObject(USSDServiceKT.diagnosticReport(appContext)).toString(2)
          stage = "create_export_directory"
          val exportDirectory = java.io.File(appContext.cacheDir, "diagnostics")
          if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IllegalStateException("Cannot create diagnostics export folder")
          }
          exportDirectory.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(2)?.forEach { it.delete() }
          stage = "write_export_file"
          val exportFile = java.io.File(
            exportDirectory,
            "kaashy-diagnostics-${System.currentTimeMillis()}.json",
          )
          exportFile.writeText(report, Charsets.UTF_8)
          stage = "create_share_uri"
          val exportUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.diagnostics",
            exportFile,
          )
          stage = "create_share_chooser"
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Kaashy Admin diagnostics")
            putExtra(Intent.EXTRA_STREAM, exportUri)
            clipData = ClipData.newUri(appContext.contentResolver, "Diagnostics report", exportUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          val chooser = Intent.createChooser(shareIntent, "Share diagnostics")
          val hostActivity = activity
          if (hostActivity == null) {
            throw IllegalStateException("App screen is no longer active; reopen Automation Center and retry")
          }
          stage = "launch_share_chooser"
          hostActivity.startActivity(chooser)
          USSDServiceKT.recordDiagnostic(appContext, "diagnostics_share_native_launched")
          result.success(null)
        } catch (error: Exception) {
          USSDServiceKT.recordDiagnostic(appContext, "diagnostics_share_native_failed", mapOf(
            "stage" to stage,
            "errorType" to error.javaClass.simpleName,
            "error" to (error.message ?: "no message").take(240),
          ), flushImmediately = true)
          result.error(
            "DIAGNOSTICS_SHARE_FAILED",
            "$stage: ${error.javaClass.simpleName}: ${error.message ?: "no message"}",
            null,
          )
        }
      }
      "sendUssd" -> {
        result.success(defaultUssdService(code!!, subscriptionId))

      }
      "sendAdvancedUssd" -> {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
          val res = singleSessionUssd(code!!, subscriptionId)
          if(res != null){

            res.exceptionally { e: Throwable? ->
              if (e is RequestExecutionException) {
                result.error(
                  RequestExecutionException.type, e.message, null
                )
              } else {
                result.error(RequestExecutionException.type, e?.message, null)
              }
              null
            }.thenAccept(result::success);

          }else{
            result.success(res);
          }
        }else{
          result.success(defaultUssdService(code!!, subscriptionId))
        }
      }
      "multisessionUssd" -> {
        // This method also runs from the SMS background engine. Do not launch
        // Settings or a permission dialog from this path.
        val appContext = context?.applicationContext
        if (appContext == null) {
          result.success("USSD_AUTOMATION_UNAVAILABLE: Android application context is unavailable.")
        } else if (ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.CALL_PHONE,
          ) != PackageManager.PERMISSION_GRANTED) {
          USSDServiceKT.recordDiagnostic(appContext, "preflight_failed", mapOf("reason" to "call_phone_permission"))
          result.success("USSD_PERMISSION_REQUIRED: Grant Phone permission before enabling recharge automation.")
        } else if (ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.READ_PHONE_STATE,
          ) != PackageManager.PERMISSION_GRANTED) {
          USSDServiceKT.recordDiagnostic(appContext, "preflight_failed", mapOf("reason" to "read_phone_state_permission"))
          result.success("USSD_PERMISSION_REQUIRED: Grant Phone State permission before enabling recharge automation.")
        } else if (!isAccessibilityServiceEnabled(appContext)) {
          USSDServiceKT.recordDiagnostic(appContext, "preflight_failed", mapOf("reason" to "accessibility_service_disabled"))
          result.success("USSD_ACCESSIBILITY_REQUIRED: Enable Kaashy Admin's USSD accessibility service in Android Settings. No USSD was dialed.")
        } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
          multisessionUssd(code!!, subscriptionId, result)

        }else{
          result.success(defaultUssdService(code!!, subscriptionId))
        }

      }
      "debugMultisessionUssd" -> debugMultisessionUssd(result)
      "multisessionUssdCancel" ->{
        multisessionUssdCancel()
        result.success(null)
      }
      "multisessionUssdDismissFinal" -> {
        result.success(multisessionUssdDismissFinal())
      }
      "multisessionUssdRelease" -> {
        multisessionUssdRelease()
        result.success(null)
      }
      "dismissMatchedZaadFlashSms" -> {
        val otp = call.argument<String>("otp").orEmpty()
        USSDServiceKT.dismissMatchedZaadFlashSms(otp) { status ->
          result.success(status)
        }
      }
      else -> {
        result.notImplemented()
      }
    }
  }

    private fun hasPermissions() : Boolean{
        val appContext = context?.applicationContext ?: return false
        return ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            isAccessibilityServiceEnabled(appContext)
    }

    private fun requestPermissions(){
        val appContext = context?.applicationContext ?: return
        if(!isAccessibilityServiceEnabled(appContext)) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            appContext.startActivity(intent)

        }

        val attachedActivity = activity ?: return
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(attachedActivity, android.Manifest.permission.CALL_PHONE)) {
                ActivityCompat.requestPermissions(attachedActivity, arrayOf(android.Manifest.permission.CALL_PHONE), 2)
            }
        }

        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(attachedActivity, android.Manifest.permission.READ_PHONE_STATE)) {
                ActivityCompat.requestPermissions(attachedActivity, arrayOf(android.Manifest.permission.READ_PHONE_STATE), 2)
            }
        }

    }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private class RequestExecutionException internal constructor(override var message: String) :
    Exception() {
    companion object {
      var type = "ussd_plugin_ussd_execution_failure"
    }
  }

  private class RequestParamsException internal constructor(override var message: String) :
    Exception() {
    companion object {
      var type = "ussd_plugin_incorrect__parameters"
    }
  }




  // for android 8+
  private fun singleSessionUssd(ussdCode:String, subscriptionId:Int) : CompletableFuture<String>?{
    // use defaulft sim
    val _useDefault: Boolean = subscriptionId == -1

    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
      var res:CompletableFuture<String> = CompletableFuture<String>()
      // check permissions
      if (ContextCompat.checkSelfPermission(this.context!!, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!, android.Manifest.permission.CALL_PHONE)) {
        } else {
          ActivityCompat.requestPermissions(activity!!, arrayOf(android.Manifest.permission.CALL_PHONE), 2)
        }
      }

      // get TelephonyManager
      val tm = this.context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

      val simManager: TelephonyManager = tm.createForSubscriptionId(subscriptionId)

      // callback
      val callback =
        object : UssdResponseCallback() {
          override fun onReceiveUssdResponse(
            telephonyManager: TelephonyManager, request: String, response: CharSequence
          ) {
            res.complete(response.toString())
          }

          override fun onReceiveUssdResponseFailed(
            telephonyManager: TelephonyManager, request: String, failureCode: Int
          ) {
            when (failureCode) {
              TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> {
                res.completeExceptionally(RequestExecutionException("USSD_ERROR_SERVICE_UNAVAIL"))
              }
              TelephonyManager.USSD_RETURN_FAILURE -> {
                res.completeExceptionally(RequestExecutionException("USSD_RETURN_FAILURE"))
              }
              else -> {
                res.completeExceptionally(RequestExecutionException("unknown error"))
              }
            }
          }
        }

      if(_useDefault){
        tm.sendUssdRequest(
          ussdCode,
          callback,
          Handler(Looper.getMainLooper())
        )

      }else{
        simManager.sendUssdRequest(
          ussdCode,
          callback,
          Handler(Looper.getMainLooper())
        )
      }


      return res
    }else{
      // if sdk is less than 26
      defaultUssdService(ussdCode, subscriptionId)
      return  null
    }

  }

  private fun multisessionUssd(ussdCode:String, subscriptionId:Int, @NonNull result: Result){
    USSDServiceKT.endSession()
    event?.recycle()
    event = null
    var slot = subscriptionId
    if(subscriptionId == -1){
      slot = 0
    }

    val appContext = context?.applicationContext
    if (appContext == null) {
      result.success("USSD_AUTOMATION_UNAVAILABLE: Android application context is unavailable.")
      return
    }
    USSDServiceKT.recordDiagnostic(appContext, "ussd_call_requested", mapOf(
      "simSlot" to slot.toString(),
      "codePrefix" to Regex("^\\*\\d{3}\\*").find(ussdCode)?.value.orEmpty(),
      "codeLength" to ussdCode.length.toString(),
    ))
    val initialResultDelivered = AtomicBoolean(false)
    fun deliverInitialResult(response: String?) {
      if (initialResultDelivered.compareAndSet(false, true)) result.success(response)
    }

    try {
      ussdApi.callUSSDInvoke(appContext, ussdCode, slot, object : USSDController.CallbackInvoke {

      override fun responseInvoke(ev: AccessibilityEvent) {
        USSDServiceKT.beginSession(ev)
        event?.recycle()
        event = AccessibilityEvent.obtain(ev)
        setListener()

        try {
          val response = USSDServiceKT.extractResponseText(ev)
          USSDServiceKT.recordDiagnostic(appContext, "initial_prompt_callback", mapOf(
            "textLength" to response.length.toString(),
            "responseEmpty" to response.isBlank().toString(),
          ))
          deliverInitialResult(response.ifBlank { null })
        }catch (e: Exception){
          USSDServiceKT.recordDiagnostic(appContext, "initial_prompt_callback_failed", mapOf(
            "errorType" to e.javaClass.simpleName,
          ))
          deliverInitialResult(null)
        }
      }

      override fun over(message: String) {
        try {
          USSDServiceKT.recordDiagnostic(appContext, "ussd_session_ended", mapOf(
            "textLength" to message.length.toString(),
          ))
          completePendingSendReply(message)
          basicMessageChannel.send(message)
          deliverInitialResult(message)
        }catch (e: Exception){}
        finally {
          event?.recycle()
          // Keep the exact terminal dialog snapshot so the host app can
          // explicitly cancel it after inspecting a receipt. Never dismiss
          // a USSD dialog as a side effect of receiving its final text.
          event = USSDServiceKT.terminalEventSnapshot()
          basicMessageChannel.setMessageHandler(null)
        }

      }
      })
    } catch (error: Exception) {
      USSDServiceKT.recordDiagnostic(appContext, "ussd_call_exception", mapOf(
        "errorType" to error.javaClass.simpleName,
        "hasMessage" to (!error.message.isNullOrBlank()).toString(),
      ))
      deliverInitialResult(
        "USSD_AUTOMATION_DIAL_FAILED: ${error.message ?: error.javaClass.simpleName}"
      )
      completePendingSendReply("USSD_AUTOMATION_DIAL_FAILED: ${error.message ?: error.javaClass.simpleName}")
      USSDServiceKT.endSession()
      event?.recycle()
      event = null
      basicMessageChannel.setMessageHandler(null)
    }
  }

  /**
   * Starts an emulator-only accessibility dialog flow. This is explicitly
   * blocked in non-debuggable builds and never calls TelephonyManager.
   */
  private fun debugMultisessionUssd(@NonNull result: Result) {
    val appContext = context?.applicationContext
    if (appContext == null) {
      result.error("DEBUG_USSD_UNAVAILABLE", "Application context unavailable.", null)
      return
    }
    if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      result.error("DEBUG_USSD_DISABLED", "The USSD simulator is debug-build only.", null)
      return
    }
    if (!isAccessibilityServiceEnabled(appContext)) {
      result.error("DEBUG_USSD_ACCESSIBILITY", "Enable the example app's USSD accessibility service first.", null)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
      result.error("DEBUG_USSD_OVERLAY_PERMISSION", "Grant overlay permission to the debug test app.", null)
      return
    }
    USSDServiceKT.endSession()
    event?.recycle()
    event = null
    val delivered = AtomicBoolean(false)
    fun deliver(response: String?) {
      if (delivered.compareAndSet(false, true)) result.success(response)
    }
    USSDController.beginDebugSession(appContext, object : USSDController.CallbackInvoke {
      override fun responseInvoke(ev: AccessibilityEvent) {
        USSDServiceKT.beginSession(ev)
        event?.recycle()
        event = AccessibilityEvent.obtain(ev)
        setListener()
        deliver(USSDServiceKT.extractResponseText(ev))
      }

      override fun over(message: String) {
        try {
          completePendingSendReply(message)
          basicMessageChannel.send(message)
          deliver(message)
        } finally {
          event?.recycle()
          event = USSDServiceKT.terminalEventSnapshot()
          basicMessageChannel.setMessageHandler(null)
        }
      }
    })
    try {
      val simulator = Intent("com.phan_tech.ussd_advanced.DEBUG_USSD_PROMPT")
        .setClassName(appContext.packageName,
          "com.phan_tech.ussd_advanced_example.DebugUssdSimulatorService")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(simulator)
      else appContext.startService(simulator)
      USSDServiceKT.recordDiagnostic(appContext, "debug_simulator_started")
    } catch (error: Exception) {
      USSDServiceKT.endSession()
      deliver("DEBUG_USSD_START_FAILED: ${error.javaClass.simpleName}")
    }
  }

  private fun completePendingSendReply(message: String?) {
    val reply = synchronized(pendingReplyLock) {
      val current = pendingSendReply
      pendingSendReply = null
      current
    }
    try {
      reply?.reply(message)
    } catch (_: Exception) {
      // A reply may already have been delivered by an accessibility event.
    }
  }

  private fun multisessionUssdCancel(){
    val activeEvent = event
    try {
      if (activeEvent != null) ussdApi.cancel2(activeEvent)
    } finally {
      USSDServiceKT.endSession()
      activeEvent?.recycle()
      event = null
      basicMessageChannel.setMessageHandler(null)
      completePendingSendReply("USSD_AUTOMATION_CANCELLED: Session was cancelled.")
    }
  }

  private fun multisessionUssdDismissFinal(): String {
    val activeEvent = event ?: return "FINAL_PROMPT_EVENT_UNAVAILABLE"
    val status = try {
      USSDServiceKT.dismissConfirmedPayoutPrompt(activeEvent)
    } catch (error: Exception) {
      "FINAL_PROMPT_DISMISS_EXCEPTION:${error.javaClass.simpleName}"
    }
    if (status.startsWith("DISMISSED_")) {
      try {
        USSDServiceKT.endSession()
        activeEvent.recycle()
        event = null
        basicMessageChannel.setMessageHandler(null)
        completePendingSendReply("USSD_AUTOMATION_FINAL_PROMPT_DISMISSED: $status")
      } catch (_: Exception) {
        // Return the actual button result even if listener cleanup fails.
      }
    }
    return status
  }

  private fun multisessionUssdRelease(){
    val activeEvent = event
    try {
      USSDServiceKT.recordDiagnostic(context ?: return, "session_released_without_dismissal", mapOf(
        "hadDialogSnapshot" to (activeEvent != null).toString(),
      ))
    } finally {
      USSDServiceKT.endSession()
      activeEvent?.recycle()
      event = null
      basicMessageChannel.setMessageHandler(null)
      completePendingSendReply("USSD_AUTOMATION_RELEASED: Session ownership was released without dismissing the dialog.")
    }
  }

  private val simSlotName = arrayOf(
    "extra_asus_dial_use_dualsim",
    "com.android.phone.extra.slot",
    "slot",
    "simslot",
    "sim_slot",
    "subscription",
    "Subscription",
    "phone",
    "com.android.phone.DialingMode",
    "simSlot",
    "slot_id",
    "simId",
    "simnum",
    "phone_type",
    "slotId",
    "slotIdx"
  )

  // multiple for all
  private fun defaultUssdService(ussdCode:String, subscriptionId:Int){
    if (ContextCompat.checkSelfPermission(this.context!!, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
      if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!, android.Manifest.permission.CALL_PHONE)) {
      } else {
        ActivityCompat.requestPermissions(activity!!, arrayOf(android.Manifest.permission.CALL_PHONE), 2)
      }
    }
    try {
      // use defaulft sim
      val _useDefault: Boolean = subscriptionId == -1

      val sim:Int = subscriptionId -1
      var number:String = ussdCode;
      number = number.replace("#", "%23");
      if (!number.startsWith("tel:")) {
        number = String.format("tel:%s", number);
      }
      val intent =
        Intent(if (isTelephonyEnabled()) Intent.ACTION_CALL else Intent.ACTION_VIEW)
      intent.data = Uri.parse(number)

      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);


      if(!_useDefault){
        intent.putExtra("com.android.phone.force.slot", true);
        intent.putExtra("Cdma_Supp", true);

        for (s in simSlotName) intent.putExtra(s, sim)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
          if (ContextCompat.checkSelfPermission(this.context!!, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity!!, android.Manifest.permission.READ_PHONE_STATE)) {
            } else {
              ActivityCompat.requestPermissions(activity!!, arrayOf(android.Manifest.permission.READ_PHONE_STATE), 2)
            }
          }
          val telecomManager = this.context!!.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

          val phoneAccountHandleList = telecomManager.callCapablePhoneAccounts
          if (phoneAccountHandleList != null && phoneAccountHandleList.isNotEmpty())
            intent.putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE",
              phoneAccountHandleList[sim]
            );
        }
      }


      this.context!!.startActivity(intent)

    } catch (e: Exception) {
      throw e
    }
  }

  private fun isAccessibilityServiceEnabled(context: Context): Boolean{
    return USSDController.verifyAccessibilityAccess(context.applicationContext)

  }

  private fun isTelephonyEnabled(): Boolean {
    val tm = this.context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    return tm.phoneType != TelephonyManager.PHONE_TYPE_NONE

  }




}
