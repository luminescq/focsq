// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/browser.dart';

void main() {
  group('UrlService.validated: схема и хост', () {
    test('публичные http/https проходят', () {
      expect(
        UrlService.validated('https://github.com/luminescq/focsq'),
        'https://github.com/luminescq/focsq',
      );
      expect(
        UrlService.validated('http://example.com/path?q=1'),
        'http://example.com/path?q=1',
      );
      expect(
        UrlService.validated('https://api.github.com:443/repos'),
        'https://api.github.com:443/repos',
      );
    });

    test('не-http(s) схемы отклоняются', () {
      expect(
        () => UrlService.validated('file:///C:/Windows/system32'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('ftp://example.com'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('javascript:alert(1)'),
        throwsFormatException,
      );
    });

    test('отсутствие хоста отклоняется', () {
      expect(() => UrlService.validated('https://'), throwsFormatException);
      expect(
        () => UrlService.validated('not-a-url'),
        throwsFormatException,
      );
    });
  });

  group('UrlService.validated: локальные и приватные хосты', () {
    test('localhost и локальные домены отклоняются', () {
      expect(
        () => UrlService.validated('http://localhost:8080'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('https://app.localhost'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('https://printer.local'),
        throwsFormatException,
      );
    });

    test('приватные IPv4 отклоняются', () {
      expect(
        () => UrlService.validated('http://127.0.0.1/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://10.0.0.1/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://172.16.0.1/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://172.31.255.254/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://192.168.8.105/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://169.254.1.1/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://100.64.0.1/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://224.0.0.1/'),
        throwsFormatException,
      );
    });

    test('приватные IPv6 отклоняются', () {
      expect(
        () => UrlService.validated('http://[::1]/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://[fe80::1]/'),
        throwsFormatException,
      );
      expect(
        () => UrlService.validated('http://[fc00::1]/'),
        throwsFormatException,
      );
    });

    test('публичные IPv4 проходят', () {
      expect(UrlService.validated('http://1.1.1.1/'), 'http://1.1.1.1/');
      expect(
        UrlService.validated('https://13.143.132.214/'),
        'https://13.143.132.214/',
      );
    });
  });
}
