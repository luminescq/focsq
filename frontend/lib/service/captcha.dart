// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'package:desktop_webview_window/desktop_webview_window.dart';
import '../src/rust/api/simple.dart';
import '../l10n/app_locale.dart';
import 'webview_data_dir.dart';

class CaptchaService {
  CaptchaService._();

  static bool _busy = false;

  static void Function(String result, String reason)? _activeFinish;

  static Duration _timeoutFor(String mode) {
    switch (mode) {
      case 'selected':
        return const Duration(seconds: 120);
      case 'manual':
        return const Duration(seconds: 60);
      default:
        return const Duration(seconds: 40);
    }
  }

  static void handleSolveRequest(
    String line,
    void Function(String message) log, {
    required bool clientRunning,
  }) {
    final parts = line.split('|');
    if (parts.length < 4) {
      log(S.captchaBadRequest(line));
      return;
    }
    if (!clientRunning) {
      log(S.captchaClientStoppedLog);
      return;
    }
    if (_busy) {
      log(S.captchaAlreadyOpenLog);
      submitCaptchaResult(result: 'error:busy');
      return;
    }
    final mode = parts[1];
    final redirectUri = parts[2];
    _busy = true;
    _solve(mode, redirectUri, log).whenComplete(() {
      _busy = false;
      _activeFinish = null;
    });
  }

  static void abort(String reason) {
    _activeFinish?.call('error:stopped', reason);
  }

  static Future<void> _solve(
    String mode,
    String redirectUri,
    void Function(String message) log,
  ) async {
    final timeout = _timeoutFor(mode);
    log(S.captchaOpeningLog(mode, timeout.inSeconds));

    Webview? webview;
    var finished = false;
    Timer? pollTimer;
    final done = Completer<void>();

    Future<void> finish(String result, String reason) async {
      if (finished) return;
      finished = true;
      pollTimer?.cancel();
      final delivered = await submitCaptchaResult(result: result);
      log(
        delivered ? S.captchaAcceptedLog(reason) : S.captchaRejectedLog(reason),
      );
      try {
        webview?.close();
      } catch (_) {}
      done.complete();
    }

    try {
      webview = await WebviewWindow.create(
        configuration: CreateConfiguration(
          title: S.captchaWindowTitle,
          titleBarHeight: 0,
          windowWidth: 420,
          windowHeight: 620,
          userDataFolderWindows: webviewDataDir('vk_captcha_data'),
        ),
      );

      webview.onClose.then((_) {
        finish('error:closed', S.captchaReasonClosed);
      });

      webview.launch(redirectUri);

      pollTimer = Timer.periodic(const Duration(milliseconds: 400), (
        timer,
      ) async {
        try {
          await webview?.evaluateJavaScript(_hookJs);
          final capture = await webview?.evaluateJavaScript(
            'window.__csqttCapture || ""',
          );
          if (capture == null || capture.isEmpty) return;
          final match = _successTokenRegex.firstMatch(capture);
          if (match != null) {
            await finish(match.group(1)!, S.captchaReasonCaptured);
          }
        } catch (_) {}
      });

      Timer(timeout, () {
        finish('error:timeout', S.captchaReasonTimeout(timeout.inSeconds));
      });
      return done.future;
    } catch (error) {
      log(S.captchaOpenFailed(error));
      await finish('error:webview', S.captchaReasonWebview);
    }
  }
}

const String _hookJs = '''
(function(){
  if (window.__csqttHooked) { return "hooked"; }
  window.__csqttHooked = "1";
  window.__csqttCapture = "";
  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function() {
      var args = arguments;
      return originalFetch.apply(this, args).then(function(response) {
        try { response.clone().text().then(function(t){ window.__csqttCapture += t; }); } catch(e) {}
        return response;
      });
    };
  }
  var originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.addEventListener('load', function() {
      try { window.__csqttCapture += this.responseText; } catch(e) {}
    });
    return originalOpen.apply(this, arguments);
  };
  return "hooked";
})()
''';

final RegExp _successTokenRegex = RegExp(
  'success_token\\\\?"\\s*:\\s*\\\\?"([0-9a-zA-Z_\\-]+)',
);
