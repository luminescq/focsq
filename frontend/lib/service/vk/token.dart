// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

import '../logs.dart';

class VkToken {
  VkToken._();

  static const FlutterSecureStorage _storage = FlutterSecureStorage();
  static const String _key = 'vk_eternal_token';

  // WebView2 profile directory; independent of the secure VK token storage.
  static Future<File> _authDirectorySetting() async {
    final support = await getApplicationSupportDirectory();
    return File(
      '${support.path}${Platform.pathSeparator}vk_auth_directory.txt',
    );
  }

  static Future<String> getAuthDataDirectory() async {
    final setting = await _authDirectorySetting();
    if (!await setting.exists()) return '';
    return (await setting.readAsString()).trim();
  }

  static Future<void> setAuthDataDirectory(String value) async {
    final path = value.trim();
    if (path.isNotEmpty) {
      final drivePath = RegExp(r'^[a-zA-Z]:[\\/]').hasMatch(path);
      final uncPath = RegExp(r'^\\\\[^\\/]+[\\/][^\\/]+').hasMatch(path);
      if (!drivePath && !uncPath) {
        throw const FormatException(
          'Укажи полный путь к папке, например D:\\FOCSQ\\vk_auth_data',
        );
      }
      await Directory(path).create(recursive: true);
    }
    final setting = await _authDirectorySetting();
    await setting.parent.create(recursive: true);
    await setting.writeAsString(path, flush: true);
  }

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

  static bool _loginInProgress = false;

  /// Optional durations let platform-channel tests exercise stalled startup.
  static Future<String?> login({
    Duration availabilityTimeout = const Duration(seconds: 10),
    Duration createTimeout = const Duration(seconds: 30),
    Duration pageLoadTimeout = const Duration(seconds: 30),
    Duration scriptTimeout = const Duration(seconds: 30),
    Duration pollInterval = const Duration(milliseconds: 500),
  }) async {
    if (_loginInProgress) return 'Окно входа ВК уже открывается или открыто';
    _loginInProgress = true;
    try {
      return await _login(
        availabilityTimeout: availabilityTimeout,
        createTimeout: createTimeout,
        pageLoadTimeout: pageLoadTimeout,
        scriptTimeout: scriptTimeout,
        pollInterval: pollInterval,
      );
    } finally {
      _loginInProgress = false;
    }
  }

  // Native failures may include URLs in their messages. Only report the
  // exception type/code here, never an OAuth URL or access token.
  static String _webviewError(Object error) {
    if (error is PlatformException) return 'код ${error.code}';
    return error.runtimeType.toString();
  }

  static String _loginFailure(String message) {
    LogService().add('[ВК] Ошибка входа: $message');
    return message;
  }

  static Future<String?> _login({
    required Duration availabilityTimeout,
    required Duration createTimeout,
    required Duration pageLoadTimeout,
    required Duration scriptTimeout,
    required Duration pollInterval,
  }) async {
    LogService().add('[ВК] Проверка WebView2...');
    try {
      final available = await WebviewWindow.isWebviewAvailable().timeout(
        availabilityTimeout,
      );
      if (!available) {
        return _loginFailure(
          'WebView недоступен на этой машине '
          '(на Windows: установи WebView2 Runtime)',
        );
      }
    } on TimeoutException {
      return _loginFailure(
        'Проверка WebView2 не отвечает. '
        'Перезапусти FOCSQ и проверь установку WebView2 Runtime.',
      );
    } catch (error) {
      return _loginFailure(
        'Не удалось проверить WebView2 '
        '(${_webviewError(error)}). Проверь установку WebView2 Runtime.',
      );
    }

    String authDataDirectory = 'vk_auth_data';
    if (Platform.isWindows) {
      try {
        final saved = await getAuthDataDirectory();
        // WebView2 resolves a relative user-data folder beside the executable.
        // Send the absolute equivalent so the log names the actual directory.
        authDataDirectory = saved.isEmpty
            ? '${File(Platform.resolvedExecutable).parent.path}'
                  '${Platform.pathSeparator}vk_auth_data'
            : saved;
        await Directory(authDataDirectory).create(recursive: true);
        LogService().add('[ВК] Папка профиля WebView2: $authDataDirectory');
      } catch (error) {
        return _loginFailure(
          'Не удалось подготовить папку данных входа ВК '
          '(${_webviewError(error)}). Выбери доступную для записи папку '
          'в настройках.',
        );
      }
    }

    LogService().add('[ВК] Создание окна входа...');
    final Webview webview;
    var creationTimedOut = false;
    try {
      final creation = WebviewWindow.create(
        configuration: CreateConfiguration(
          title: 'Авторизация ВКонтакте',
          titleBarHeight: 0,
          windowWidth: 500,
          windowHeight: 700,
          userDataFolderWindows: authDataDirectory,
        ),
      );
      // Future.timeout cannot cancel native initialization. Close a window
      // that completes later instead of leaving an orphaned login window.
      unawaited(
        creation.then<void>((lateView) {
          if (creationTimedOut) _closeQuietly(lateView);
        }, onError: (Object error, StackTrace stack) {}),
      );
      webview = await creation.timeout(
        createTimeout,
        onTimeout: () {
          creationTimedOut = true;
          throw TimeoutException('WebView2 initialization');
        },
      );
    } on TimeoutException {
      return _loginFailure(
        'Окно входа ВК не открылось: WebView2 не ответил. '
        'Перезапусти FOCSQ; проверь WebView2 Runtime и доступ к папке '
        'данных входа ВК в настройках.',
      );
    } catch (error) {
      return _loginFailure(
        'Не удалось открыть окно входа ВК '
        '(${_webviewError(error)}). Проверь WebView2 Runtime и папку '
        'данных входа ВК в настройках.',
      );
    }

    LogService().add('[ВК] Окно создано, открытие страницы входа...');
    try {
      await webview
          .launch(_authUrl, triggerOnUrlRequestEvent: false)
          .timeout(createTimeout);
    } on TimeoutException {
      _closeQuietly(webview);
      return _loginFailure(
        'Страница входа ВК не открылась: WebView2 '
        'не ответил. Закрой окно входа и попробуй снова.',
      );
    } catch (error) {
      _closeQuietly(webview);
      return _loginFailure(
        'Не удалось открыть страницу входа ВК '
        '(${_webviewError(error)}). Проверь соединение и попробуй снова.',
      );
    }
    LogService().add('[ВК] Ожидание завершения входа в открытом окне');

    final completer = Completer<String?>();
    var polling = false;
    var navigated = false;
    var pageHasContent = false;
    var blankPageTimedOut = false;
    var scriptTimedOut = false;
    Timer? initialNavigationTimer;
    const visibleContentScript = r'''(() => {
      const body = document.body;
      if (!body) return false;
      if (body.innerText.trim().length > 0) return true;
      return Array.from(body.querySelectorAll(
        'input:not([type="hidden"]),button,select,textarea,a[href],[role="button"]'
      )).some(element => {
        const style = getComputedStyle(element);
        return style.display !== 'none' && style.visibility !== 'hidden' &&
          style.opacity !== '0' && element.getClientRects().length > 0;
      });
    })()''';
    final timer = Timer.periodic(pollInterval, (t) async {
      if (completer.isCompleted) {
        t.cancel();
        return;
      }
      if (polling) return;
      polling = true;
      try {
        final url = await webview
            .evaluateJavaScript('window.location.href')
            .timeout(scriptTimeout);
        if (completer.isCompleted) return;
        if (url == null) return;
        final unquoted = url.replaceAll('"', '');
        final parsed = Uri.tryParse(unquoted);
        if (!navigated &&
            parsed != null &&
            (parsed.scheme == 'http' || parsed.scheme == 'https') &&
            parsed.host.isNotEmpty) {
          navigated = true;
          // A host is enough to diagnose navigation; never log the OAuth URL.
          LogService().add('[ВК] Страница входа открывается: ${parsed.host}');
        }
        final isBlankRedirect =
            parsed != null &&
            (parsed.host == 'oauth.vk.ru' || parsed.host == 'oauth.vk.com') &&
            parsed.path == '/blank.html';
        if (isBlankRedirect) {
          final tokenUri = Uri.parse(unquoted.replaceFirst('#', '?'));
          final token = tokenUri.queryParameters['access_token'];
          t.cancel();
          if (!completer.isCompleted) {
            completer.complete(
              (token != null && token.isNotEmpty) ? token : null,
            );
          }
          _closeQuietly(webview);
          return;
        }
        if (!pageHasContent &&
            parsed != null &&
            (parsed.scheme == 'http' || parsed.scheme == 'https') &&
            parsed.host.isNotEmpty) {
          final content = await webview
              .evaluateJavaScript(visibleContentScript)
              .timeout(scriptTimeout);
          if (completer.isCompleted) return;
          if (content?.trim() == 'true') {
            pageHasContent = true;
            initialNavigationTimer?.cancel();
            LogService().add('[ВК] Содержимое страницы входа отображается');
          }
        }
      } on TimeoutException {
        // A timed-out platform call may still be running natively. Stop the
        // session instead of scheduling a second concurrent script request.
        if (!completer.isCompleted) {
          scriptTimedOut = true;
          completer.complete(null);
          t.cancel();
          _closeQuietly(webview);
        }
      } catch (_) {
        // The initial-navigation timer also bounds a non-responsive script API.
      } finally {
        polling = false;
      }
    });
    initialNavigationTimer = Timer(pageLoadTimeout, () {
      if (pageHasContent || completer.isCompleted) return;
      blankPageTimedOut = true;
      completer.complete(null);
      _closeQuietly(webview);
    });

    webview.onClose.then((_) {
      timer.cancel();
      if (!completer.isCompleted) completer.complete(null);
    });

    var authTimedOut = false;
    try {
      final token = await completer.future.timeout(
        const Duration(minutes: 5),
        onTimeout: () {
          authTimedOut = true;
          _closeQuietly(webview);
          return null;
        },
      );
      if (scriptTimedOut) {
        return _loginFailure(
          'Окно входа ВК не отвечает на проверку страницы. '
          'Перезапусти FOCSQ и проверь WebView2 Runtime.',
        );
      }
      if (blankPageTimedOut) {
        return _loginFailure(
          'Окно входа ВК осталось пустым: страница не '
          'загрузилась. Проверь соединение с VK, прокси и WebView2 Runtime; '
          'затем открой вход заново.',
        );
      }
      if (authTimedOut) {
        return _loginFailure(
          'Время ожидания входа ВК истекло. '
          'Открой окно входа заново и заверши авторизацию.',
        );
      }
      if (token == null) return 'окно закрыто до завершения входа';
      return await save(token);
    } finally {
      timer.cancel();
      initialNavigationTimer.cancel();
    }
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
      LogService().add(
        '[ВК] Secret Service недоступен ($secureIssue) — '
        'токен сохранён в файл без шифрования',
      );
      lastNotice =
          'Токен сохранён в файл без шифрования: на этой системе '
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
          LogService().add(
            '[ВК] Токен перенесён из файла в защищённое хранилище',
          );
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
        '[ВК] Не удалось удалить токен: ${describeStorageError(error)}',
      );
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
