import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/service/import.dart';

void main() {
  late LinkParser parser;

  setUp(() {
    parser = LinkParser(random: Random(1));
  });

  group('LinkParser / v2 (csqtt://connect?...)', () {
    test('полная корректная ссылка', () {
      final profile = parser.parse(
        'csqtt://connect?v=2&host=203.0.113.88&peer=46000&password=dummy_secret_pass',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
      expect(profile.password, 'dummy_secret_pass');
      expect(profile.name, startsWith('Сервер #'));
    });

    test('склеенная ссылка без единого &', () {
      final profile = parser.parse(
        'csqtt://connect?v=2host=203.0.113.88peer=46000password=dummy_secret_pass',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
      expect(profile.password, 'dummy_secret_pass');
    });

    test('частично склеенная ссылка (один & уже есть) — был баг', () {
      final profile = parser.parse(
        'csqtt://connect?v=2host=1.2.3.4&peer=46000password=pw',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '1.2.3.4');
      expect(profile.port, 46000);
      expect(profile.password, 'pw');
    });

    test('ссылка с hashes содержит корректный профиль', () {
      final profile = parser.parse(
        'csqtt://connect?v=2&host=10.0.0.1&peer=443&password=p&hashes=abc123,def456',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '10.0.0.1');
      expect(profile.port, 443);
      expect(profile.password, 'p');
    });

    test('percent-encoding в password декодируется', () {
      final profile = parser.parse(
        'csqtt://connect?v=2&host=h.io&peer=1000&password=my%40pass%20word',
      );

      expect(profile!.password, 'my@pass word');
    });

    test('лишний слэш после connect распознаётся', () {
      final profile = parser.parse(
        'csqtt://connect/?v=2&host=203.0.113.88&peer=46000&password=dummy_secret_pass',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
      expect(profile.password, 'dummy_secret_pass');
    });

    test('HTML-экранированная форма (&amp;) распознаётся', () {
      final profile = parser.parse(
        'csqtt://connect?v=2&amp;host=203.0.113.88&amp;peer=46000'
        '&amp;password=dummy_secret_pass',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
      expect(profile.password, 'dummy_secret_pass');
    });

    test('разделители, закодированные как %26, распознаются', () {
      final profile = parser.parse(
        'csqtt://connect?v=2%26host=203.0.113.88%26peer=46000'
        '%26password=dummy_secret_pass',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
    });

    test('точка с запятой как разделитель и фрагмент поддерживаются', () {
      final profile = parser.parse(
        'csqtt://connect?v=2;host=203.0.113.88;peer=46000;password=pw#frag',
      );

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.88');
      expect(profile.port, 46000);
      expect(profile.password, 'pw');
    });

    test('password со встроенным key= не режется', () {
      final profile = parser.parse(
        'csqtt://connect?v=2&host=h&peer=443&password=abcpower=123',
      );

      expect(profile, isNotNull);
      expect(profile!.password, 'abcpower=123');
      expect(profile.power, isNull);
    });

    test('password со встроенным hashes= не мешает хешам', () {
      const link =
          'csqtt://connect?v=2&host=h&peer=443'
          '&password=abcpower=123&hashes=abc';

      expect(parser.parse(link)!.password, 'abcpower=123');
      expect(parser.parseHashes(link), 'abc');
    });

    test('неверсионированная или v!=2 ссылка отвергается', () {
      expect(
        parser.parse('csqtt://connect?v=1&host=h&peer=80&password=p'),
        isNull,
      );
      expect(parser.parse('csqtt://connect?host=h&peer=80&password=p'), isNull);
    });

    test('некорректный порт отвергается', () {
      expect(
        parser.parse('csqtt://connect?v=2&host=h&peer=0&password=p'),
        isNull,
      );
      expect(
        parser.parse('csqtt://connect?v=2&host=h&peer=70000&password=p'),
        isNull,
      );
      expect(
        parser.parse('csqtt://connect?v=2&host=h&peer=abc&password=p'),
        isNull,
      );
      expect(parser.parse('csqtt://connect?v=2&host=h&password=p'), isNull);
    });

    test('отсутствие host/password отвергается', () {
      expect(parser.parse('csqtt://connect?v=2&peer=443&password=p'), isNull);
      expect(parser.parse('csqtt://connect?v=2&host=h&peer=443'), isNull);
    });

    test('power извлекается и нормализуется', () {
      expect(
        parser
            .parse('csqtt://connect?v=2&host=h&peer=443&password=p&power=27')!
            .power,
        27,
      );
      expect(
        parser
            .parse('csqtt://connect?v=2&host=h&peer=443&password=p&power=0')!
            .power,
        isNull,
      );
      expect(
        parser
            .parse('csqtt://connect?v=2&host=h&peer=443&password=p&power=-3')!
            .power,
        isNull,
      );
      expect(
        parser
            .parse(
              'csqtt://connect?v=2&host=h&peer=443&password=p&power=100000',
            )!
            .power,
        ImportService.maxPower,
      );
    });

    test(
      'workers используется, если power отсутствует, и power приоритетнее',
      () {
        expect(
          parser
              .parse(
                'csqtt://connect?v=2&host=h&peer=443&password=p&workers=18',
              )!
              .power,
          18,
        );
        expect(
          parser
              .parse(
                'csqtt://connect?v=2&host=h&peer=443&password=p&workers=3&power=45',
              )!
              .power,
          45,
        );
      },
    );
  });

  group('LinkParser / hashes', () {
    test('декодирует и очищает список', () {
      expect(
        parser.parseHashes(
          'csqtt://connect?v=2&host=h&peer=443&password=p'
          '&hashes=%20abc%2C%20%2Cdef%20',
        ),
        'abc,def',
      );
    });

    test('поддерживает склеенные параметры', () {
      expect(
        parser.parseHashes(
          'csqtt://connect?v=2host=hpeer=443password=phashes=abc,def',
        ),
        'abc,def',
      );
    });

    test('браузерные формы ссылки с hashes поддерживаются', () {
      expect(
        parser.parseHashes(
          'csqtt://connect/?v=2&amp;host=h&amp;peer=443'
          '&amp;password=p&amp;hashes=abc,def',
        ),
        'abc,def',
      );
      expect(
        parser.parseHashes(
          'csqtt://connect/?v=2&host=h&peer=443&password=p&hashes=abc',
        ),
        'abc',
      );
    });

    test('пустые, отсутствующие и невалидные хеши не применяются', () {
      for (final link in [
        'csqtt://connect?v=2&host=h&peer=443&password=p',
        'csqtt://connect?v=2&host=h&peer=443&password=p&hashes=%20,,',
        'csqtt://connect?v=2&host=h&peer=0&password=p&hashes=abc',
        'csqtt://connect?v=2&host=h&peer=443&password=p&hashes=%zz',
        'csqtt://p@h:443',
      ]) {
        expect(parser.parseHashes(link), isNull);
      }
    });
  });

  group('LinkParser / legacy (csqtt://pass@host:port)', () {
    test('базовый формат', () {
      final profile = parser.parse('csqtt://secret@203.0.113.7:46000');

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.7');
      expect(profile.port, 46000);
      expect(profile.password, 'secret');
    });

    test('url-encoded пароль декодируется', () {
      final profile = parser.parse('csqtt://my%40pass@example.com:1000');
      expect(profile!.password, 'my@pass');
    });

    test('лишний слэш в конце legacy-ссылки не мешает', () {
      final profile = parser.parse('csqtt://secret@203.0.113.7:46000/');

      expect(profile, isNotNull);
      expect(profile!.ip, '203.0.113.7');
      expect(profile.port, 46000);
    });

    test('неотэкраненный @ в пароле — деление по последнему @ (был баг)', () {
      final profile = parser.parse('csqtt://p@ss@host:1234');

      expect(profile, isNotNull);
      expect(profile!.password, 'p@ss');
      expect(profile.ip, 'host');
    });

    test('IPv6 с портом', () {
      final profile = parser.parse('csqtt://s@[2001:db8::1]:46000');

      expect(profile, isNotNull);
      expect(profile!.ip, '2001:db8::1');
      expect(profile.port, 46000);
    });

    test('IPv6 без порта отвергается', () {
      expect(parser.parse('csqtt://s@[2001:db8::1]'), isNull);
    });

    test('битые %-последовательности не роняют парсер', () {
      expect(parser.parse('csqtt://%zz@host:1234'), isNull);
    });

    test('адрес без порта / лишние двоеточия отвергаются', () {
      expect(parser.parse('csqtt://s@hostonly'), isNull);
      expect(parser.parse('csqtt://s@a:b:c'), isNull);
    });
  });

  group('LinkParser / отбраковка', () {
    test('чужая схема и мусор', () {
      expect(
        parser.parse('http://connect?v=2&host=h&peer=80&password=p'),
        isNull,
      );
      expect(parser.parse('csqttp://x'), isNull);
      expect(parser.parse(''), isNull);
      expect(parser.parse('   '), isNull);
    });

    test('пробелы вокруг ссылки обрезаются', () {
      final profile = parser.parse('  csqtt://secret@host:443  ');
      expect(profile, isNotNull);
      expect(profile!.port, 443);
    });
  });

  group('ImportService.extractProfiles / parseLink', () {
    test('извлекает несколько ссылок из текста вперемешку с мусором', () {
      const text = '''
Серверы на сегодня:
csqtt://secret@10.0.0.1:443
какой-то мусор http://example.com
и ещё v2: csqtt://connect?v=2&host=10.0.0.2&peer=5000&password=q
''';

      final profiles = ImportService.extractProfiles(text);

      expect(profiles, hasLength(2));
      expect(profiles[0].ip, '10.0.0.1');
      expect(profiles[1].ip, '10.0.0.2');
    });

    test('текст без ссылок даёт пустой список', () {
      expect(ImportService.extractProfiles('ничего полезного'), isEmpty);
    });

    test('parseLink — разбор одиночной ссылки', () {
      final profile = ImportService.parseLink('csqtt://s@host:443');
      expect(profile, isNotNull);
      expect(ImportService.parseLink('мусор'), isNull);
    });
  });
}
