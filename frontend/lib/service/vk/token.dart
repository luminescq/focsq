// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../logs.dart';

class VkToken {
  VkToken._();

  static const FlutterSecureStorage _storage = FlutterSecureStorage();
  static const String _key = 'vk_eternal_token';

  static String? lastNotice;

  static String? fallbackDirForTest;

  static File get _fallbackFile {
    final base = fallbackDirForTest ??
        (Platform.isLinux
            ? (Platform.environment['XDG_DATA_HOME'] ??
                '${Platform.environment['HOME']}/.local/share')
            : (Platform.environment['APPDATA'] ??
                Directory.systemTemp.path));
    return File(
        '$base${Platform.pathSeparator}focsq${Platform.pathSeparator}vk_token');
  }

  static const String _authUrl = 'https://oauth.vk.ru/authorize?'
      'client_id=7793118&'
      'scope=1073737727&'
      'redirect_uri=https://oauth.vk.ru/blank.html&'
      'display=page&'
      'response_type=token&'
      'revoke=1&'
      'v=5.199';

  static Future<String?> login() async {
    final available = await WebviewWindow.isWebviewAvailable();
    if (!available) {

      LogService().add('[ВК] WebView недоступен на этой машине');
      return 'WebView недоступен на этой машине '
          '(на Windows: установи WebView2 Runtime)';
    }

    final completer = Completer<String?>();

    final Webview webview = await WebviewWindow.create(
      configuration: CreateConfiguration(
        title: 'Авторизация ВКонтакте',
        titleBarHeight: 0,
        windowWidth: 500,
        windowHeight: 700,

        userDataFolderWindows: 'vk_auth_data',
      ),
    );

    webview.launch(_authUrl);

    final timer = Timer.periodic(const Duration(milliseconds: 500), (t) async {
      if (completer.isCompleted) {
        t.cancel();
        return;
      }
      try {
        final url = await webview.evaluateJavaScript('window.location.href');
        if (url == null) return;
        final unquoted = url.replaceAll('"', '');

        final parsed = Uri.tryParse(unquoted);
        final isBlankRedirect = parsed != null &&
            (parsed.host == 'oauth.vk.ru' || parsed.host == 'oauth.vk.com') &&
            parsed.path == '/blank.html';
        if (!isBlankRedirect) return;

        final tokenUri = Uri.parse(unquoted.replaceFirst('#', '?'));
        final token = tokenUri.queryParameters['access_token'];
        t.cancel();
        if (!completer.isCompleted) {
          completer.complete(
            (token != null && token.isNotEmpty) ? token : null,
          );
        }
        _closeQuietly(webview);
      } catch (_) {
      }
    });

    webview.onClose.then((_) {
      timer.cancel();
      if (!completer.isCompleted) completer.complete(null);
    });

    final token = await completer.future.timeout(
      const Duration(minutes: 5),
      onTimeout: () {
        LogService().add('[ВК] Тайм-аут ожидания токена');
        _closeQuietly(webview);
        return null;
      },
    );

    if (token == null) return 'окно закрыто до завершения входа';

    return save(token);
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
      LogService().add('[ВК] Токен сохранён');
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
      LogService().add('[ВК] Secret Service недоступен ($secureIssue) — '
          'токен сохранён в файл без шифрования');
      lastNotice = 'Токен сохранён в файл без шифрования: на этой системе '
          'нет Secret Service (KDE без gnome-keyring или Live-сессия). '
          'Установи gnome-keyring, войди заново — токен переедет в '
          'защищённое хранилище.';
      return null;
    } catch (error) {
      LogService().add('[ВК] Токен не сохранён: $error');
      lastNotice = null;
      return 'не удалось сохранить токен: $error';
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
          LogService()
              .add('[ВК] Токен перенесён из файла в защищённое хранилище');
        }
        return (token: token, issue: null);
      }
    } catch (_) {
    }
    return (token: null, issue: secureIssue);
  }

  static Future<String?> get() async => (await readWithStatus()).token;

  static Future<bool> has() async {
    final read = await readWithStatus();
    if (read.issue != null) {
      LogService().add('[ВК] Токен недоступен: ${read.issue}');
    }
    return read.token != null && read.token!.isNotEmpty;
  }

  static String describeStorageError(Object error) {
    if (error is PlatformException) {
      switch (error.code) {
        case 'KeyringLocked':
          return 'хранилище заблокировано — разблокируй keyring '
              '(пароль обычно вводится при входе в систему)';
        case 'Libsecret error':
          return 'нет Secret Service — установи gnome-keyring '
              '(работает и в KDE, в отличие от KWallet)';
        case 'SecretNotFound':
          return 'запись не найдена';
        default:
          return '${error.code}: ${error.message ?? "без описания"}';
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
      LogService().add(
          '[ВК] Не удалось удалить токен: ${describeStorageError(error)}');
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