import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/webview_debug_log.dart';

void main() {
  group('WebViewDebugLog.redact', () {
    test('маскирует токены во фрагменте OAuth-редиректа', () {
      final text = WebViewDebugLog.redact(
        'https://oauth.vk.ru/blank.html'
        '#access_token=secret&expires_in=0&user_id=42&code=abc',
      );
      expect(text, contains('access_token=***'));
      expect(text, contains('code=***'));
      expect(text, isNot(contains('secret')));
      expect(text, contains('expires_in=0'));
      expect(text, contains('user_id=42'));
    });

    test('маскирует телефон и логин в query', () {
      final text = WebViewDebugLog.redact(
        'https://api.vk.ru/method/auth?login=%2B79990000000&phone=%2B79990000000',
      );
      expect(text, isNot(contains('79990000000')));
      expect(text, contains('login=***'));
      expect(text, contains('phone=***'));
    });

    test('маскирует секреты в JSON-сообщениях', () {
      final text = WebViewDebugLog.redact(
        '{"access_token":"secret","anonymous_token":"anon","error_msg":"Flood control"}',
      );
      expect(text, contains('"access_token":"***"'));
      expect(text, contains('"anonymous_token":"***"'));
      expect(text, contains('"error_msg":"Flood control"'));
      expect(text, isNot(contains('secret')));
    });

    test('не трогает error_code и другие безопасные поля', () {
      final text = WebViewDebugLog.redact(
        'code=9 subcode=1100 msg=Flood error_code=9',
      );
      expect(text, startsWith('code=***'));
      expect(text, contains('error_code=9'));
    });

    test('маскирует VK-хеши из ответов с ошибкой', () {
      final text = WebViewDebugLog.redact(
        '{"hash":"abc123","auth_user_hash":"def456"}',
      );
      expect(text, contains('"hash":"***"'));
      expect(text, contains('"auth_user_hash":"***"'));
      expect(text, isNot(contains('abc123')));
      expect(text, isNot(contains('def456')));
    });
  });

  test('takeMessage принимает только сообщения WebView', () {
    expect(WebViewDebugLog.takeMessage('WVDBG|hello'), 'hello');
    expect(WebViewDebugLog.takeMessage('other|hello'), isNull);
  });
}
