import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/vk/net_error.dart';

void main() {
  group('parseVkNetIssue', () {
    test('блокировка страницы: error_code 5 + user is blocked', () {
      final issue = parseVkNetIssue(
        '[net] https://api.vk.ru/method/auth.getOauthToken http=200 '
        'error_code=5 error_subcode= '
        'msg=User authorization failed: user is blocked. text=',
      )!;
      expect(issue.code, 5);
      expect(issue.subcode, isNull);
      expect(issue.message, 'User authorization failed: user is blocked.');
      expect(issue.text, isEmpty);
      expect(issue.isAccountBlocked, isTrue);
      expect(issue.detail, 'User authorization failed: user is blocked.');
    });

    test('detail берёт text, если он есть', () {
      final issue = parseVkNetIssue(
        '[net] https://api.vk.ru/method/x http=400 error_code=6 '
        'error_subcode= msg=Too many requests per second text=Слишком часто',
      )!;
      expect(issue.detail, 'Слишком часто');
    });

    test('flood control — код 9', () {
      final issue = parseVkNetIssue(
        '[net] https://api.vk.ru/method/x http=200 error_code=9 '
        'error_subcode= msg=Flood control text=',
      )!;
      expect(issue.isFloodControl, isTrue);
      expect(issue.isAccountBlocked, isFalse);
    });

    test('passkey — код 5, subcode 1108', () {
      final issue = parseVkNetIssue(
        '[net] https://api.vk.ru/method/x http=200 error_code=5 '
        'error_subcode=1108 msg= text=',
      )!;
      expect(issue.isPasskeyRequired, isTrue);
      expect(issue.isAccountBlocked, isFalse);
    });

    test('старый формат code=/subcode= тоже читается', () {
      final issue = parseVkNetIssue(
        '[net] https://api.vk.ru/method/x http=200 code=5 subcode=1108 '
        'msg= text=',
      )!;
      expect(issue.code, 5);
      expect(issue.subcode, 1108);
    });

    test('строка без ошибки VK — null', () {
      expect(
        parseVkNetIssue('[net] https://api.vk.ru/method/x http=200'),
        isNull,
      );
      expect(parseVkNetIssue('[page] load https://id.vk.ru/auth'), isNull);
    });
  });
}
