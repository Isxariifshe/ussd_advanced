/// Run ussd code directly in your application
import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

class UssdAdvanced {
  static const MethodChannel _channel = MethodChannel(
    'method.com.phan_tech/ussd_advanced',
  );
  //Initialize BasicMessageChannel
  static const BasicMessageChannel<String> _basicMessageChannel =
      BasicMessageChannel("message.com.phan_tech/ussd_advanced", StringCodec());

  static Future<void> sendUssd({
    required String code,
    int subscriptionId = 1,
  }) async {
    await _channel.invokeMethod('sendUssd', {
      "subscriptionId": subscriptionId,
      "code": code,
    });
  }

  static Future<String?> sendAdvancedUssd({
    required String code,
    int subscriptionId = 1,
  }) async {
    final String? response = await _channel
        .invokeMethod('sendAdvancedUssd', {
          "subscriptionId": subscriptionId,
          "code": code,
        })
        .timeout(const Duration(seconds: 30))
        .catchError((e) {
          throw e;
        });
    return response;
  }

  static Future<String?> multisessionUssd({
    required String code,
    int subscriptionId = 1,
  }) async {
    String response = await _channel.invokeMethod('multisessionUssd', {
          "subscriptionId": subscriptionId,
          "code": code,
        }).catchError((e) {
          throw e;
        }) ??
        '';
    return response;
  }

  static Future<void> cancelSession() async {
    await _channel.invokeMethod('multisessionUssdCancel').catchError((e) {
      throw e;
    });
  }

  /// Dismisses a final prompt only after the host has validated a matching
  /// provider receipt. Native code prefers a visible Cancel control and uses
  /// Send/OK only when Cancel is unavailable.
  static Future<String?> dismissConfirmedPayoutPrompt() async =>
      _channel.invokeMethod<String>('multisessionUssdDismissFinal');

  /// Releases automation ownership without interacting with the visible USSD
  /// dialog. Use this when the payout outcome is uncertain or needs review.
  static Future<void> releaseSession() async {
    await _channel.invokeMethod('multisessionUssdRelease').catchError((e) {
      throw e;
    });
  }

  /// Runs a fake multi-step prompt on a debuggable Android build only.
  /// The native side rejects this call in every non-debuggable build.
  static Future<String?> debugMultisessionUssd() async =>
      _channel.invokeMethod<String>('debugMultisessionUssd');

  /// Attempts a guarded dismissal of the currently visible ZAAD class-0 OTP
  /// dialog. Native Android returns an explicit result; it does not dismiss
  /// unrelated telephony dialogs or USSD input screens.
  static Future<String> dismissMatchedZaadFlashSms({
    required String otp,
  }) async {
    if (!RegExp(r'^[0-9]{6}$').hasMatch(otp)) return 'INVALID_OTP';
    final result = await _channel.invokeMethod<String>(
      'dismissMatchedZaadFlashSms',
      {'otp': otp},
    );
    return result ?? 'DISMISS_RESULT_UNAVAILABLE';
  }

  static Future<String?> sendMessage(String message) async {
    var _response = await _basicMessageChannel.send(message).catchError((e) {
      throw e;
    });
    return _response;
  }

  static Future<String?> sendMultipleMessages(List<String> messages) async {
    var _response = "";
    for (var m in messages) {
      var _res = await sendMessage(m);
      _response += "\n$_res";
    }

    return _response;
  }

  static StreamController<String?> onEnd() {
    StreamController<String?> _streamController = StreamController<String?>();
    _basicMessageChannel.setMessageHandler((message) async {
      _streamController.add(message);
      return message ?? '';
    });

    return _streamController;
  }

  static Future<bool> hasPermissions() async {
    try {
      return (await _channel.invokeMethod('hasPermissions')) == true;
    } catch (_) {
      return false;
    }
  }

  /// Returns the bounded, locally persisted Android USSD diagnostic ring buffer.
  /// It stores event metadata and response lengths, not USSD text or PIN values.
  static Future<Map<String, dynamic>> getDiagnostics() async {
    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        'getDiagnostics',
      );
      return result ?? <String, dynamic>{};
    } catch (_) {
      return <String, dynamic>{};
    }
  }

  /// Adds one redacted application event to the native, persistent diagnostics ring.
  static Future<void> recordDiagnostic(
    String phase, {
    Map<String, Object?> details = const <String, Object?>{},
    bool flush = false,
  }) async {
    try {
      await _channel.invokeMethod<void>('recordAppDiagnostic', {
        'phase': phase,
        'details': details,
        'flush': flush,
      });
    } catch (_) {
      // Diagnostics must never interrupt an operational flow.
    }
  }

  /// Opens Android's system share sheet with the current redacted JSON report.
  static Future<void> shareDiagnostics() async {
    await _channel.invokeMethod<void>('shareDiagnostics');
  }

  /// Copies the redacted local diagnostics report when Android cannot launch
  /// a share target. The caller can then paste it into a support message.
  static Future<bool> copyDiagnosticsToClipboard() async {
    final report = await getDiagnostics();
    if (report.isEmpty) return false;
    await Clipboard.setData(
      ClipboardData(text: const JsonEncoder.withIndent('  ').convert(report)),
    );
    return true;
  }

  static void requestPermissions() {
    try {
      _channel.invokeMethod('requestPermissions');
    } catch (_) {}
  }
}
