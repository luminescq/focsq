// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/services.dart' show PlatformException;

import '../../l10n/app_locale.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../logs.dart';
import '../webview_data_dir.dart';
import '../webview_debug_log.dart';
import 'auth_url.dart';
import 'net_error.dart';

class VkToken {
  VkToken._();

  static const FlutterSecureStorage _storage = FlutterSecureStorage();
  static const String _key = 'vk_eternal_token';

  static String? lastNotice;

  static String? fallbackDirForTest;

  static File get _fallbackFile {
    final base =
        fallbackDirForTest ??
        (Platform.isLinux
            ? (Platform.environment['XDG_DATA_HOME'] ??
                  '${Platform.environment['HOME']}/.local/share')
            : (Platform.environment['APPDATA'] ?? Directory.systemTemp.path));
    return File(
      '$base${Platform.pathSeparator}focsq${Platform.pathSeparator}vk_token',
    );
  }

  static const String _authUrl =
      'https://oauth.vk.ru/authorize?'
      'client_id=7793118&'
      'scope=1073737727&'
      'redirect_uri=https://oauth.vk.ru/blank.html&'
      'display=page&'
      'response_type=token&'
      'revoke=1&'
      'v=5.199';

  static const Duration _loginTimeout = Duration(minutes: 5);
  static const Duration _warmupDelay = Duration(seconds: 2);
  static const int _maxSilentRestarts = 1;
  static const String _warmupUrl = 'https://vk.com/';
  static const String _authLabel = 'oauth.vk.ru/authorize';

  static Future<String?> login() async {
    final debugLog = await WebViewDebugLog.start('vk_auth');
    void dbg(String message) {
      final safe = WebViewDebugLog.redact(message);
      LogService().add('[VKDBG] $safe');
      debugLog?.add(safe);
    }

    if (WebViewDebugLog.enabled) {
      dbg(
        debugLog == null
            ? 'debug log: unavailable'
            : 'debug log: ${debugLog.file.path}',
      );
    }

    final available = await WebviewWindow.isWebviewAvailable();
    if (!available) {
      dbg('webview unavailable');
      LogService().add(S.vkWebviewUnavailableLog);
      await debugLog?.stop();
      return S.vkWebviewUnavailableMsg;
    }

    final completer = Completer<String?>();

    final Webview webview = await WebviewWindow.create(
      configuration: CreateConfiguration(
        title: S.vkAuthWindowTitle,
        titleBarHeight: 0,
        windowWidth: 500,
        windowHeight: 700,

        userDataFolderWindows: webviewDataDir('vk_auth_data'),
      ),
    );
    dbg('window created');

    if (WebViewDebugLog.enabled) {
      webview.addScriptToExecuteOnDocumentCreated(
        WebViewDebugLog.injectedScript,
      );
      dbg('js debug hook installed');
    }

    var finished = false;
    var busy = false;
    var silentRestarts = 0;
    var jsErrorLogged = false;
    String? lastHref;
    Timer? poll;
    Timer? deadline;

    void finish(String? error) {
      if (finished) return;
      finished = true;
      dbg('finish: ${error ?? 'ok'}');
      poll?.cancel();
      deadline?.cancel();
      if (!completer.isCompleted) completer.complete(error);
      try {
        webview.removeAllWebMessageReceivedCallback();
      } catch (_) {}
      _closeQuietly(webview);
      unawaited(debugLog?.stop());
    }

    if (WebViewDebugLog.enabled) {
      webview.addOnWebMessageReceivedCallback((message) {
        final line = WebViewDebugLog.takeMessage(message);
        if (line == null) return;
        dbg(line);

        // VK присылает ошибки в JSON-ответах api.vk.*, а не в URL.
        // Раньше это выяснялось только ожиданием 5-минутного тайм-аута.
        final issue = _vkIssueFromDebugLine(line);
        if (issue != null) {
          scheduleMicrotask(() => finish(issue));
        }
      });
    }

    Future<void> retryAfterSilent() async {
      if (silentRestarts >= _maxSilentRestarts) {
        finish(S.vkSilentTokenFailed);
        return;
      }
      silentRestarts++;
      dbg(
        'silent_token pass $silentRestarts/$_maxSilentRestarts: '
        'vk.com warm-up',
      );
      // VK проставит cookie активной сессии, и второй проход по OAuth
      // покажет «Продолжить как <Имя>» — уже с access_token.
      try {
        await webview.launch(_warmupUrl);
      } catch (error) {
        dbg('warm-up launch failed: $error');
      }
      await Future<void>.delayed(_warmupDelay);
      try {
        await webview.launch(_authUrl);
      } catch (error) {
        dbg('auth launch failed: $error');
      }
      await Future<void>.delayed(const Duration(milliseconds: 800));
    }

    webview.onClose.then((_) {
      finish(S.vkWindowClosedEarly);
    });

    poll = Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      if (finished || busy) return;

      String? href;
      try {
        href = await webview.evaluateJavaScript('window.location.href');
      } catch (error) {
        if (!jsErrorLogged) {
          jsErrorLogged = true;
          dbg('evaluateJavaScript failed: $error');
        }
        return;
      }
      if (href == null || href.isEmpty) return;

      final url = unwrapEvaluatedUrl(href);
      if (url != lastHref) {
        lastHref = url;
        dbg('nav $url');
      }

      final capture = parseVkAuthUrl(href);
      if (capture == null) return;
      if (!capture.hasToken &&
          !capture.hasError &&
          !capture.hasCode &&
          !capture.silentToken) {
        return;
      }

      dbg('capture ${capture.debugSummary}');
      busy = true;
      try {
        if (capture.hasToken) {
          finish(await save(capture.accessToken!));
        } else if (capture.hasError) {
          finish(
            S.vkOAuthError(capture.error!, capture.errorDescription ?? ''),
          );
        } else if (capture.hasCode) {
          dbg('VK returned an auth code, not a token');
          finish(S.vkCodeInsteadOfToken);
        } else {
          await retryAfterSilent();
        }
      } finally {
        if (!finished) busy = false;
      }
    });

    deadline = Timer(_loginTimeout, () {
      if (finished) return;
      dbg('login timeout');
      LogService().add(S.vkTokenTimeout);
      finish(S.vkLoginTimeoutMsg(_loginTimeout.inMinutes));
    });

    dbg('launch $_authLabel');
    try {
      await webview.launch(_authUrl);
    } catch (error) {
      dbg('launch failed: $error');
    }
    return completer.future;
  }

  /// Разбирает `[net]`-строку из JS-хука. Возвращает текст для UI, если
  /// VK уже ответил ошибкой, из-за которой ждать токен бессмысленно.
  static String? _vkIssueFromDebugLine(String line) {
    final issue = parseVkNetIssue(line);
    if (issue == null) return null;

    if (issue.isFloodControl) return S.vkFloodControl;
    if (issue.isPasskeyRequired) return S.vkPasskeyUnsupported;
    if (issue.isAccountBlocked) return S.vkAccountBlocked;

    // Ошибки промежуточных шагов (отправка кода, капча, валидация)
    // VK ID показывает сам и даёт повторить — окно не закрываем.
    // Обрываем вход только когда VK отказал в самом токене.
    if (!line.contains('/method/auth.getOauthToken')) return null;
    return S.vkApiError(issue.code, issue.detail);
  }

  static void _closeQuietly(Webview webview) {
    try {
      webview.close();
    } catch (_) {}
  }

  static Future<String?> save(String token) async {
    token = token.trim();

    final secureIssue = await _writeSecure(token);
    if (secureIssue == null) {
      LogService().add(S.vkTokenSaved);
      lastNotice = null;

      await _deleteFallback();
      return null;
    }

    try {
      final file = _fallbackFile;
      await file.parent.create(recursive: true);
      await file.writeAsString(token, flush: true);
      if (Platform.isLinux) {
        await Process.run('chmod', ['600', file.path]);
      }
      LogService().add(S.vkSecretFallback(secureIssue));
      lastNotice = S.vkFallbackNotice;
      return null;
    } catch (error) {
      LogService().add(S.vkTokenSaveFailed(error));
      lastNotice = null;
      return S.vkSaveFailedMsg(error);
    }
  }

  static Future<String?> _writeSecure(String token) async {
    try {
      await _storage.write(key: _key, value: token);
      return null;
    } catch (error) {
      return describeStorageError(error);
    }
  }

  static Future<({String? token, String? issue})> readWithStatus() async {
    String? secureIssue;
    try {
      final token = await _storage.read(key: _key);
      if (token != null && token.trim().isNotEmpty) {
        return (token: token.trim(), issue: null);
      }
    } catch (error) {
      secureIssue = describeStorageError(error);
    }

    try {
      final file = _fallbackFile;
      if (await file.exists()) {
        final token = (await file.readAsString()).trim();
        if (token.isEmpty) return (token: null, issue: secureIssue);

        if (await _writeSecure(token) == null) {
          await file.delete();
          LogService().add(S.vkTokenMoved);
        }
        return (token: token, issue: null);
      }
    } catch (_) {}
    return (token: null, issue: secureIssue);
  }

  static Future<String?> get() async => (await readWithStatus()).token;

  static Future<bool> has() async {
    final read = await readWithStatus();
    if (read.issue != null) {
      LogService().add(S.vkTokenUnavailable(read.issue!));
    }
    return read.token != null && read.token!.isNotEmpty;
  }

  static String describeStorageError(Object error) {
    if (error is PlatformException) {
      switch (error.code) {
        case 'KeyringLocked':
          return S.vkKeyringLocked;
        case 'Libsecret error':
          return S.vkNoSecretService;
        case 'SecretNotFound':
          return S.vkSecretNotFound;
        default:
          return '${error.code}: ${error.message ?? S.vkNoDescription}';
      }
    }
    return error.toString();
  }

  static Future<void> _deleteFallback() async {
    try {
      final file = _fallbackFile;
      if (await file.exists()) await file.delete();
    } catch (_) {}
  }

  static Future<bool> delete() async {
    var ok = true;
    try {
      await _storage.delete(key: _key);
    } catch (error) {
      LogService().add(S.vkTokenDeleteFailed(describeStorageError(error)));
      ok = false;
    }
    try {
      await _deleteFallback();
    } catch (_) {
      ok = false;
    }
    return ok;
  }
}
