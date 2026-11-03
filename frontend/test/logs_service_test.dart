import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/logs.dart';

/// Тесты LogService: группировка повторов (xN), обновление статусных строк
/// на месте, фильтрация пользовательского режима, ошибки, кольцевой буфер.
void main() {
  test('CAPTCHA results, challenge SID, anonymous token and VK join links are masked', () {
    final source =
        '[УПР] CAPTCHA_RESULT|captcha-success-value\n'
        'request anonymToken=anonymous-value&joinLink=join-value&sid=sid-value\n'
        'url https://vk.com/call/join/private-call-hash?x=1\n'
        'encoded https%3A%2F%2Fvk.com%2Fcall%2Fjoin%2Fencoded-call-hash\n'
        'form anonymToken%3Dencoded-anonymous%26joinLink%3Dencoded-join';
    final safe = LogService.redactSecrets(source);
    for (final secret in [
      'captcha-success-value',
      'anonymous-value',
      'join-value',
      'sid-value',
      'private-call-hash',
      'encoded-call-hash',
      'encoded-anonymous',
      'encoded-join',
    ]) {
      expect(safe, isNot(contains(secret)));
    }
    expect(safe, contains('CAPTCHA_RESULT|[REDACTED]'));
    expect(safe, contains('vk.com/call/join/[REDACTED]'));
  });
  setUp(() {
    LogService.developerMode = false;
    LogService().configureDiagnosticOutput(filePath: null, writer: null);
    LogService().clear();
  });

  tearDownAll(() {
    LogService.developerMode = false;
    LogService().configureDiagnosticOutput(filePath: null, writer: null);
    LogService().clear();
  });

  group('закрытие диагностического вывода', () {
    test('ожидает запись и отсоединяет writer перед закрытием', () async {
      final logs = LogService();
      final disk = <String>[];
      final ready = Completer<void>();
      var closeCalls = 0;
      var closed = false;
      logs.configureDiagnosticOutput(
        filePath: 'diagnostic.log',
        writer: disk.add,
        close: () async {
          closeCalls++;
          await ready.future;
          closed = true;
        },
      );
      logs.add('[КОННЕКТ] Последняя запись');
      final closing = logs.closeDiagnosticOutput();
      var completed = false;
      final observed = closing.then((_) => completed = true);
      await Future<void>.value();
      expect(closeCalls, 1);
      expect(completed, isFalse);
      expect(closed, isFalse);
      expect(identical(logs.closeDiagnosticOutput(), closing), isTrue);
      logs.add('[КОННЕКТ] После отсоединения');
      expect(disk, hasLength(1));
      ready.complete();
      await observed;
      expect(closed, isTrue);
      await logs.closeDiagnosticOutput();
      expect(closeCalls, 1);
      expect(logs.diagnosticFilePath, 'diagnostic.log');
    });

    test('ошибка закрытия не мешает выходу и не раскрывает детали', () async {
      final logs = LogService();
      logs.configureDiagnosticOutput(
        filePath: 'diagnostic.log',
        writer: (_) {},
        close: () => throw StateError('fixture-close-secret'),
      );
      await expectLater(logs.closeDiagnosticOutput(), completes);
      expect(logs.lines.single, contains('Ошибка завершения'));
      expect(logs.lines.single, contains('StateError'));
      expect(logs.lines.single, isNot(contains('fixture-close-secret')));
    });

    test('сброс конфигурации очищает и callback закрытия', () async {
      final logs = LogService();
      var closeCalls = 0;
      logs.configureDiagnosticOutput(
        filePath: 'diagnostic.log',
        writer: (_) {},
        close: () async {
          closeCalls++;
        },
      );
      logs.configureDiagnosticOutput(filePath: null, writer: null);
      await logs.closeDiagnosticOutput();
      expect(closeCalls, 0);
      expect(logs.diagnosticFilePath, isNull);
    });
  });

  group('isErrorLine', () {
    test('нулевые счётчики и настройки тайм-аута не означают ошибку', () {
      for (final line in [
        '[TURN][worker=1] stage=allocate_ok decrypt_errors=0 auth_failed=0',
        '[HANDSHAKE][worker=1] stage=getconf_send timeout_ms=750 failed=false',
        '[HANDSHAKE][worker=1] stage=config_reply decrypted=1 accepted=1 decrypt_errors=0 auth_failed=0 malformed_rtp=0 replay_drops=0',
        '[HANDSHAKE][worker=1] stage=session_end ready=true failed=false',
      ]) {
        expect(LogService.isErrorLine(line), isFalse, reason: line);
      }
    });

    test('ошибочные стадии и ненулевые счётчики остаются ошибками', () {
      for (final line in [
        '[TURN][worker=1] stage=allocate_error timeout_ms=750',
        '[HANDSHAKE][worker=1] stage=getconf_timeout auth_failed=0',
        '[HANDSHAKE][worker=1] stage=timeout_ingress decrypt_errors=0',
        '[HANDSHAKE][worker=1] stage=server_denied reason=invalid_worker_count',
        '[HANDSHAKE][worker=1] stage=failed peer_rx=0',
        '[HANDSHAKE][worker=1] stage=rtp_reject reject_kind=hmac hmac_status=failed',
        '[HANDSHAKE][worker=1] stage=reply_rejected peer_rx=1',
        '[HANDSHAKE][worker=1] stage=config_reply decrypt_errors=1',
        '[HANDSHAKE][worker=1] stage=config_reply auth_failed=2',
        '[HANDSHAKE][worker=1] stage=session_end ready=false failed=true',
      ]) {
        expect(LogService.isErrorLine(line), isTrue, reason: line);
      }
    });
    test('распознаёт ошибки в обоих языках', () {
      expect(LogService.isErrorLine('[ОШИБКА] что-то'), isTrue);
      expect(LogService.isErrorLine('connection refused'), isTrue);
      expect(LogService.isErrorLine('request timeout'), isTrue);
      expect(LogService.isErrorLine('[ЯДРО] не удалась авторизация'), isTrue);
      expect(LogService.isErrorLine('[ВОРКЕР] Поток готов ✓'), isFalse);
      // Русские тайм-ауты также должны быть видимы как ошибки.
      expect(LogService.isErrorLine('Таймаут ожидания'), isTrue);
    });
  });

  group('add', () {
    test('пустые строки игнорируются', () {
      LogService().add('   ');
      LogService().add('');
      expect(LogService().lines, isEmpty);
    });

    test('строка получает метку времени', () {
      LogService().add('[КОННЕКТ] Привет');
      expect(LogService().lines, hasLength(1));
      expect(
        LogService().lines.single,
        matches(r'^\[\d{2}:\d{2}:\d{2}\] \[КОННЕКТ\] Привет$'),
      );
    });

    test('подряд идущие дубли схлопываются в xN', () {
      final logs = LogService();
      logs.add('[КОННЕКТ] Шаг');
      logs.add('[КОННЕКТ] Шаг');
      logs.add('[КОННЕКТ] Шаг');
      expect(logs.lines, hasLength(1));
      expect(logs.lines.single, contains('(x3)'));
    });

    test('ошибки НИКОГДА не схлопываются', () {
      final logs = LogService();
      logs.add('[ЯДРО] Ошибка соединения');
      logs.add('[ЯДРО] Ошибка соединения');
      expect(logs.lines, hasLength(2));
    });

    test('разные строки — разные записи', () {
      final logs = LogService();
      logs.add('[КОННЕКТ] Раз');
      logs.add('[КОННЕКТ] Два');
      expect(logs.lines, hasLength(2));
    });
  });

  group('группировка по стабильному ключу', () {
    test('READY-события наращивают счётчик одной строки', () {
      final logs = LogService();
      for (var i = 1; i <= 3; i++) {
        logs.add('__CSQTT_EVENT__|READY|{"worker":$i}');
      }
      expect(logs.lines, hasLength(1));
      expect(logs.lines.single, contains('[ВОРКЕР] Поток готов'));
      expect(logs.lines.single, contains('(x3)'));
    });

    test('STATS обновляет строку [СЕТЬ] на месте, без счётчика', () {
      final logs = LogService();
      logs.add(
        '__CSQTT_EVENT__|STATS|{"active":9,"bytes_up":524288,"bytes_down":524288}',
      );
      // Между ними — посторонняя строка: ключ должен найтись и так
      logs.add('[КОННЕКТ] Постороннее');
      logs.add(
        '__CSQTT_EVENT__|STATS|{"active":10,"bytes_up":1048576,"bytes_down":1048576}',
      );

      final networkLines = logs.lines
          .where((l) => l.contains('[СЕТЬ]'))
          .toList();
      expect(networkLines, hasLength(1));
      expect(networkLines.single, contains('Активных потоков: 10'));
      expect(networkLines.single, contains('2.00 МБ'));
      expect(networkLines.single, isNot(contains('(x')));
    });

    test('TUNCONF-событие становится понятной строкой', () {
      LogService().add(
        '__CSQTT_EVENT__|CONFIG|{"config":"TUNCONF:10.66.67.4:1.1.1.1,1.0.0.1:0"}',
      );
      expect(LogService().lines.single, contains('[TUN] Конфиг сети получен'));
      expect(LogService().lines.single, contains('10.66.67.4'));
    });

    test('битый JSON события молча пропускается', () {
      LogService().add('__CSQTT_EVENT__|STATS|{не json');
      expect(LogService().lines, isEmpty);
    });
  });

  group('фильтрация пользовательского режима', () {
    test('технический шум скрыт, критичное — видно', () {
      final logs = LogService();
      logs.add('[WRAP] промежуточный шаг');
      logs.add('STREAM 100 step1 OK');
      logs.add('[КЛИЕНТ] Слушаю: 127.0.0.1:9000');
      logs.add('[ФАТАЛ] авторизация не удалась');

      final text = logs.lines.join('\n');
      expect(text, isNot(contains('[WRAP]')));
      expect(text, isNot(contains('STREAM')));
      expect(text, contains('[КЛИЕНТ] Слушаю'));
      expect(text, contains('[ФАТАЛ]'));
    });

    test('переключение режима сохраняет безопасную историю', () {
      final logs = LogService();
      logs.add('[WRAP] промежуточный шаг');
      logs.add('[КОННЕКТ] Начало');
      expect(logs.lines, hasLength(1));
      LogService.setDeveloperMode(true);
      expect(logs.lines, hasLength(2));
      expect(logs.lines.first, contains('[WRAP]'));
      LogService.setDeveloperMode(false);
      expect(logs.lines, hasLength(1));
      expect(logs.lines.single, contains('[КОННЕКТ] Начало'));
    });

    test('Exclude-маршруты скрыты от пользователя', () {
      LogService().add('[TUN] Exclude-маршрут TURN 87.240.137.1 добавлен');
      expect(LogService().lines, isEmpty);
    });
  });

  group('диагностика подключения', () {
    test('TURN и GETCONF этапы видимы в обычном режиме', () {
      final logs = LogService();
      logs.add('[TURN] ChannelBind активен: peer=198.51.100.7:46000');
      logs.add(
        '[HANDSHAKE] attempt=1 peer=198.51.100.7:46000 peer_rx=0 auth_failed=0',
      );
      logs.add('[GETCONF][RETRY] Нет подтверждения');
      expect(logs.lines, hasLength(3));
      expect(logs.lines.join('\n'), contains('peer_rx=0'));
      expect(logs.lines.join('\n'), isNot(contains('Поток готов')));
    });

    test(
      'одинаковый тайм-аут воркеров даёт один счётчик с первым примером',
      () {
        final logs = LogService();
        for (final id in [5, 1, 8]) {
          logs.add(
            '[ВОРКЕР #$id] FATAL_HANDSHAKE: нет ответа '
            'peer=198.51.100.7:46000 path=turn attempt=3; '
            'проверьте пароль подключения и совместимость клиента с сервером',
          );
        }
        expect(logs.lines, hasLength(1));
        expect(logs.lines.single, contains('[ВОРКЕР #5]'));
        expect(logs.lines.single, contains('peer=198.51.100.7:46000'));
        expect(logs.lines.single, contains('path=turn'));
        expect(logs.lines.single, contains('(x3)'));
        expect(logs.lines.single, isNot(contains('проверьте пароль')));
        logs.add(
          '[ВОРКЕР #2] FATAL_HANDSHAKE: нет ответа peer=198.51.100.8:46000',
        );
        expect(logs.lines, hasLength(2));
      },
    );

    test('разные причины тайм-аута одного пира не объединяются', () {
      final logs = LogService();
      logs.add(
        '[ВОРКЕР #1] FATAL_HANDSHAKE: reason=no_turn_payload peer=198.51.100.7:46000 worker=1',
      );
      logs.add(
        '[ВОРКЕР #2] FATAL_HANDSHAKE: reason=no_turn_payload peer=198.51.100.7:46000 worker=2',
      );
      logs.add(
        '[ВОРКЕР #3] FATAL_HANDSHAKE: reason=authentication_failed peer=198.51.100.7:46000 worker=3',
      );
      expect(logs.lines, hasLength(2));
      expect(logs.lines.first, contains('(x2)'));
      expect(logs.lines.first, contains('worker=1'));
      expect(logs.lines.last, contains('authentication_failed'));
    });
    test('подробный файл получает все повторы и скрытые строки после редактирования секретов', () {
      final disk = <String>[];
      final logs = LogService();
      logs.configureDiagnosticOutput(
        filePath: 'diagnostic.log',
        writer: disk.add,
      );
      logs.add('[WRAP] password="fixture password"');
      logs.add('[ВОРКЕР #1] FATAL_HANDSHAKE peer=198.51.100.7:46000');
      logs.add('[ВОРКЕР #2] FATAL_HANDSHAKE peer=198.51.100.7:46000');
      expect(disk, hasLength(3));
      expect(disk.first, matches(r'^\[\d{4}-\d{2}-\d{2}T[^\]]+Z\]'));
      expect(disk.join('\n'), isNot(contains('fixture password')));
      expect(disk.first, contains('[REDACTED]'));
      expect(logs.lines, hasLength(1));
      LogService.setDeveloperMode(true);
      expect(logs.lines, hasLength(3));
      expect(disk, hasLength(3));
    });

    test('секреты скрываются также в режиме разработчика', () {
      LogService.developerMode = true;
      final logs = LogService();
      logs.add('[HANDSHAKE] GETCONF:9000|fixture-secret|7|salt|1|9');
      logs.add(
        '[ВК] https://oauth.vk.ru/blank.html#access_token=fixture-token&expires=0',
      );
      logs.add(
        '[TURN] username="fixture-user" password="fixture-pass" peer=198.51.100.7:46000',
      );
      logs.add('[ЯДРО] Authorization: Bearer fixture-bearer');
      final text = logs.lines.join('\n');
      for (final secret in [
        'fixture-secret',
        'fixture-token',
        'fixture-user',
        'fixture-pass',
        'fixture-bearer',
      ]) {
        expect(text, isNot(contains(secret)));
      }
      expect(text, contains('peer=198.51.100.7:46000'));
    });
  });

  group('безопасный диагностический transcript', () {
    test(
      'HASH_CHECK, CAPTCHA, credentials и URL не раскрывают секреты на диске',
      () {
        final disk = <String>[];
        final logs = LogService();
        logs.configureDiagnosticOutput(
          filePath: 'diagnostic.log',
          writer: disk.add,
        );
        LogService.developerMode = true;
        for (final line in [
          '__CSQTT_EVENT__|HASH_CHECK|{"hash":"fixture-hash","available":true}',
          'CAPTCHA_SOLVE|task|https://captcha.example/?token=fixture-captcha',
          '[TURN] Credentials { username: "fixture-user", password: "fixture-pass" } peer=198.51.100.7:46000',
          '[ВК] https://oauth.vk.ru/blank.html#access_token=fixture-token&key=fixture-key',
          '[TURN] https://fixture-urluser:fixture-urlpass@turn.example/path',
          '[ВК] url?token%3Dfixture-encoded%26next%3Dvalue',
        ]) {
          logs.add(line);
        }
        final all = disk.join('\n') + logs.lines.join('\n');
        for (final secret in [
          'fixture-hash',
          'fixture-captcha',
          'fixture-user',
          'fixture-pass',
          'fixture-token',
          'fixture-key',
          'fixture-urluser',
          'fixture-urlpass',
          'fixture-encoded',
        ]) {
          expect(all, isNot(contains(secret)));
        }
        expect(disk, hasLength(6));
        expect(all, contains('peer=198.51.100.7:46000'));
        expect(all, contains('available'));
      },
    );
  });
  group('кольцевой буфер', () {
    test('при переполнении вытесняются самые старые строки', () {
      final logs = LogService();
      for (var i = 1; i <= LogService.maxLines + 5; i++) {
        logs.add('[КОННЕКТ] строка номер $i');
      }
      expect(logs.lines, hasLength(LogService.maxLines));
      expect(logs.lines.first, contains('строка номер 6'));
      expect(
        logs.lines.last,
        contains('строка номер ${LogService.maxLines + 5}'),
      );
    });
  });

  group('версия и уведомления', () {
    test('версия растёт на каждое изменение', () {
      final logs = LogService();
      final before = logs.version;
      logs.add('[КОННЕКТ] Раз');
      expect(logs.version, before + 1);
      logs.clear();
      expect(logs.version, before + 2);
    });

    test('шторм строк = одно уведомление (коалесинг 50мс)', () async {
      var notifications = 0;
      LogService().addListener(() => notifications++);
      LogService().add('[КОННЕКТ] Шторм 1');
      LogService().add('[КОННЕКТ] Шторм 2');
      LogService().add('[КОННЕКТ] Шторм 3');
      expect(notifications, isZero); // таймер ещё не сработал
      await Future<void>.delayed(const Duration(milliseconds: 80));
      expect(notifications, 1);
    });
  });

  group('lines', () {
    test('представление немутабельное', () {
      LogService().add('[КОННЕКТ] Тест');
      expect(() => LogService().lines.add('взлом'), throwsUnsupportedError);
    });
  });
}
