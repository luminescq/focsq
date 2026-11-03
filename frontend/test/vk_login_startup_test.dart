import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
// ignore: depend_on_referenced_packages
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

import 'package:frontend/service/logs.dart';
import 'package:frontend/service/vk/token.dart';

class _TemporarySupportDirectory extends PathProviderPlatform {
  _TemporarySupportDirectory(this.path);

  final String path;

  @override
  Future<String?> getApplicationSupportPath() async => path;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const webviewChannel = MethodChannel('webview_window');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const shortTimeout = Duration(milliseconds: 20);

  group('Windows VK login startup', () {
    late Directory temporary;
    late PathProviderPlatform originalPaths;
    late String? originalFallbackDirectory;
    late List<String> calls;

    setUp(() async {
      temporary = await Directory.systemTemp.createTemp('focsq_vk_startup_');
      originalPaths = PathProviderPlatform.instance;
      originalFallbackDirectory = VkToken.fallbackDirForTest;
      VkToken.fallbackDirForTest = temporary.path;
      PathProviderPlatform.instance = _TemporarySupportDirectory(
        temporary.path,
      );
      // All tests use a disposable profile. No real VK data or credentials.
      await File(
        '${temporary.path}/vk_auth_directory.txt',
      ).writeAsString('${temporary.path}/profile');
      calls = [];
      LogService().clear();
    });

    tearDown(() async {
      messenger.setMockMethodCallHandler(webviewChannel, null);
      PathProviderPlatform.instance = originalPaths;
      VkToken.fallbackDirForTest = originalFallbackDirectory;
      LogService().clear();
      await temporary.delete(recursive: true);
    });

    test(
      'unavailable runtime returns an actionable error without creating a window',
      () async {
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          return false;
        });

        final result = await VkToken.login();

        expect(result, contains('WebView2 Runtime'));
        expect(calls, ['isWebviewAvailable']);
      },
    );

    test(
      'availability exception is reported and does not escape to the UI',
      () async {
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          throw PlatformException(code: 'runtime_lookup_failed');
        });

        final result = await VkToken.login();

        expect(result, contains('runtime_lookup_failed'));
        expect(LogService().lines.join('\n'), contains('Ошибка входа'));
      },
    );

    test('stalled availability check times out and permits retry', () async {
      messenger.setMockMethodCallHandler(
        webviewChannel,
        (call) => Completer<Object?>().future,
      );

      final result = await VkToken.login(availabilityTimeout: shortTimeout);

      expect(result, contains('Проверка WebView2 не отвечает'));
      messenger.setMockMethodCallHandler(webviewChannel, (call) async => false);
      expect(await VkToken.login(), contains('WebView недоступен'));
    });

    test(
      'native create error is returned after passing the configured directory',
      () async {
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          if (call.method == 'isWebviewAvailable') return true;
          if (call.method == 'create') {
            final arguments = call.arguments as Map;
            expect(
              arguments['userDataFolderWindows'],
              '${temporary.path}/profile',
            );
            throw PlatformException(code: 'create_failed');
          }
          return null;
        });

        final result = await VkToken.login();

        expect(result, contains('create_failed'));
        expect(calls, ['isWebviewAvailable', 'create']);
        expect(
          LogService().lines.join('\n'),
          contains('Папка профиля WebView2'),
        );
      },
    );

    test(
      'stalled native create times out instead of silently waiting forever',
      () async {
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          if (call.method == 'isWebviewAvailable') return true;
          return Completer<Object?>().future;
        });

        final result = await VkToken.login(createTimeout: shortTimeout);

        expect(result, contains('WebView2 не ответил'));
        expect(result, contains('доступ к папке'));
      },
    );

    test('parallel attempts do not create multiple native windows', () async {
      final availability = Completer<Object?>();
      messenger.setMockMethodCallHandler(webviewChannel, (call) {
        calls.add(call.method);
        return availability.future;
      });
      final first = VkToken.login();
      final second = await VkToken.login();
      expect(second, contains('уже'));
      availability.complete(false);
      expect(await first, contains('WebView недоступен'));
      expect(calls, ['isWebviewAvailable']);
    });

    test('launch error is awaited and the failed window is closed', () async {
      messenger.setMockMethodCallHandler(webviewChannel, (call) async {
        calls.add(call.method);
        if (call.method == 'isWebviewAvailable') return true;
        if (call.method == 'create') return 901;
        if (call.method == 'launch') {
          expect((call.arguments as Map)['triggerOnUrlRequestEvent'], isFalse);
          throw PlatformException(
            code: 'webview_not_ready',
            message: 'https://oauth.vk.ru/blank.html#access_token=not-a-token',
          );
        }
        return null;
      });

      final result = await VkToken.login();

      expect(result, contains('webview_not_ready'));
      expect(calls, contains('close'));
      expect(LogService().lines.join('\n'), isNot(contains('access_token')));
      expect(result, isNot(contains('https://')));
    });

    test(
      'an initially blank page is closed with a useful error and permits retry',
      () async {
        var activeScripts = 0;
        var maximumActiveScripts = 0;
        var scriptCalls = 0;
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          if (call.method == 'isWebviewAvailable') return true;
          if (call.method == 'create') return 902;
          if (call.method == 'evaluateJavaScript') {
            scriptCalls++;
            activeScripts++;
            if (activeScripts > maximumActiveScripts) {
              maximumActiveScripts = activeScripts;
            }
            await Future<void>.delayed(const Duration(milliseconds: 5));
            activeScripts--;
            return '"about:blank"';
          }
          return null;
        });

        final result = await VkToken.login(
          pageLoadTimeout: const Duration(milliseconds: 40),
          pollInterval: const Duration(milliseconds: 1),
        );

        expect(result, contains('осталось пустым'));
        expect(result, contains('Проверь соединение'));
        expect(scriptCalls, greaterThan(0));
        expect(maximumActiveScripts, 1);
        expect(calls, contains('close'));
        expect(LogService().lines.join('\n'), isNot(contains('about:blank')));
        // Let an already-running script finish before resetting the channel.
        await Future<void>.delayed(const Duration(milliseconds: 10));
        messenger.setMockMethodCallHandler(
          webviewChannel,
          (call) async => false,
        );
        expect(await VkToken.login(), contains('WebView недоступен'));
      },
    );

    test(
      'HTTP navigation with an empty DOM still times out as blank',
      () async {
        var contentChecks = 0;
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          if (call.method == 'isWebviewAvailable') return true;
          if (call.method == 'create') return 904;
          if (call.method == 'evaluateJavaScript') {
            final arguments = call.arguments as Map;
            if (arguments['javaScriptString'] == 'window.location.href') {
              return '"https://oauth.vk.ru/authorize?synthetic=1"';
            }
            contentChecks++;
            return 'false';
          }
          return null;
        });

        final result = await VkToken.login(
          pageLoadTimeout: const Duration(milliseconds: 40),
          pollInterval: const Duration(milliseconds: 1),
        );

        expect(result, contains('осталось пустым'));
        expect(contentChecks, greaterThan(0));
        expect(calls, contains('close'));
        final logs = LogService().lines.join('\n');
        expect(logs, contains('oauth.vk.ru'));
        expect(logs, isNot(contains('synthetic=1')));
        expect(logs, isNot(contains('https://')));
      },
    );

    test(
      'a script response slower than three seconds can complete login normally',
      () async {
        FlutterSecureStorage.setMockInitialValues({});
        addTearDown(() => FlutterSecureStorage.setMockInitialValues({}));
        var scriptCompleted = false;
        var scriptCalls = 0;
        var closedBeforeScriptCompleted = false;
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          if (call.method == 'isWebviewAvailable') return true;
          if (call.method == 'create') return 905;
          if (call.method == 'evaluateJavaScript') {
            scriptCalls++;
            await Future<void>.delayed(const Duration(milliseconds: 3200));
            scriptCompleted = true;
            return '"https://oauth.vk.ru/blank.html#access_token=fixture-slow-token"';
          }
          if (call.method == 'close' && !scriptCompleted) {
            closedBeforeScriptCompleted = true;
          }
          return null;
        });

        final result = await VkToken.login(
          pollInterval: const Duration(milliseconds: 1),
        );

        expect(result, isNull);
        expect(await VkToken.get(), 'fixture-slow-token');
        expect(scriptCalls, 1);
        expect(closedBeforeScriptCompleted, isFalse);
        expect(calls, contains('close'));
        final logs = LogService().lines.join('\n');
        expect(logs, isNot(contains('fixture-slow-token')));
        expect(logs, isNot(contains('https://')));
      },
    );

    test(
      'successful synthetic redirect saves its token and logs no credentials',
      () async {
        FlutterSecureStorage.setMockInitialValues({});
        messenger.setMockMethodCallHandler(webviewChannel, (call) async {
          calls.add(call.method);
          if (call.method == 'isWebviewAvailable') return true;
          if (call.method == 'create') return 903;
          if (call.method == 'evaluateJavaScript') {
            return '"https://oauth.vk.ru/blank.html#access_token=fixture-token"';
          }
          return null;
        });

        final result = await VkToken.login(
          pollInterval: const Duration(milliseconds: 1),
        );

        expect(result, isNull);
        expect(await VkToken.get(), 'fixture-token');
        expect(calls, containsAllInOrder(['create', 'launch', 'close']));
        final logs = LogService().lines.join('\n');
        expect(logs, contains('oauth.vk.ru'));
        expect(logs, isNot(contains('fixture-token')));
        expect(logs, isNot(contains('#access_token')));
        expect(logs, isNot(contains('https://')));
        FlutterSecureStorage.setMockInitialValues({});
      },
    );
  }, skip: !Platform.isWindows);
}
