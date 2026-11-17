import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/vk/auth_url.dart';

/// Разбор URL возврата VK OAuth: раньше перехват требовал ровно
/// oauth.vk[.com|.ru]/blank.html и читал только фрагмент, поэтому VK ID
/// (id.vk.ru) и query-параметры проходили мимо.
void main() {
  group('parseVkAuthUrl', () {
    test('implicit token во фрагменте oauth.vk.ru', () {
      final capture = parseVkAuthUrl(
        'https://oauth.vk.ru/blank.html'
        '#access_token=vk1.a.SECRET&expires_in=86400&user_id=42',
      );

      expect(capture, isNotNull);
      expect(capture!.hasToken, isTrue);
      expect(capture.accessToken, 'vk1.a.SECRET');
      expect(capture.expiresIn, '86400');
      expect(capture.userId, '42');
      expect(capture.host, 'oauth.vk.ru');
      expect(capture.path, '/blank.html');
    });

    test('токен в query на id.vk.ru', () {
      final capture = parseVkAuthUrl(
        'https://id.vk.ru/blank.html?access_token=vk1.a.SECRET&user_id=7',
      );

      expect(capture, isNotNull);
      expect(capture!.hasToken, isTrue);
      expect(capture.accessToken, 'vk1.a.SECRET');
      expect(capture.host, 'id.vk.ru');
      expect(capture.userId, '7');
    });

    test('фрагмент приоритетнее query', () {
      final capture = parseVkAuthUrl(
        'https://id.vk.ru/blank.html?access_token=from_query'
        '#access_token=from_fragment',
      );

      expect(capture!.accessToken, 'from_fragment');
    });

    test('error и error_description', () {
      final capture = parseVkAuthUrl(
        'https://oauth.vk.ru/blank.html'
        '#error=access_denied&error_description=User+denied+access',
      );

      expect(capture, isNotNull);
      expect(capture!.hasToken, isFalse);
      expect(capture.hasError, isTrue);
      expect(capture.error, 'access_denied');
      expect(capture.errorDescription, 'User denied access');
    });

    test('silent_token в payload (URL-encoded JSON)', () {
      final capture = parseVkAuthUrl(
        'https://id.vk.ru/blank.html'
        '#payload=%7B%22type%22%3A%22silent_token%22%7D',
      );

      expect(capture, isNotNull);
      expect(capture!.hasToken, isFalse);
      expect(capture.silentToken, isTrue);
    });

    test('silent_token в escaped JSON', () {
      final capture = parseVkAuthUrl(
        r'https://id.vk.ru/blank.html#payload={\"type\":\"silent_token\"}',
      );

      expect(capture!.silentToken, isTrue);
    });

    test('обычные параметры — не silent_token', () {
      final capture = parseVkAuthUrl(
        'https://id.vk.ru/blank.html#type=access_token',
      );

      expect(capture!.silentToken, isFalse);
    });

    test('чужой хост игнорируется', () {
      expect(
        parseVkAuthUrl('https://evil.example/blank.html#access_token=abc'),
        isNull,
      );
      expect(
        parseVkAuthUrl('https://notvk.com/blank.html#access_token=abc'),
        isNull,
      );
      expect(parseVkAuthUrl('not a url'), isNull);
    });

    test('VK без параметров — null (промежуточные страницы)', () {
      expect(parseVkAuthUrl('https://id.vk.ru/'), isNull);
      expect(parseVkAuthUrl('https://id.vk.ru/blank.html'), isNull);
    });

    test('снимает кавычки и экранирование evaluateJavaScript', () {
      final capture = parseVkAuthUrl(
        r'"https:\/\/oauth.vk.ru\/blank.html#access_token=vk1.a.SECRET"',
      );

      expect(capture, isNotNull);
      expect(capture!.accessToken, 'vk1.a.SECRET');
    });

    test('пустой access_token не считается токеном', () {
      final capture = parseVkAuthUrl(
        'https://oauth.vk.ru/blank.html#access_token=&user_id=1',
      );

      expect(capture!.hasToken, isFalse);
    });

    test('debugSummary содержит имена ключей, но не значения', () {
      final capture = parseVkAuthUrl(
        'https://oauth.vk.ru/blank.html'
        '#access_token=vk1.a.SECRET&expires_in=86400&user_id=42',
      )!;

      expect(capture.debugSummary, contains('oauth.vk.ru/blank.html'));
      expect(capture.debugSummary, contains('access_token'));
      expect(capture.debugSummary, contains('expires_in=86400'));
      expect(capture.debugSummary, isNot(contains('SECRET')));
    });
  });
}
