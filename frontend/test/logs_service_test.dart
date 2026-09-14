import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/logs.dart';

/// Тесты LogService: группировка повторов (xN), обновление статусных строк
/// на месте, фильтрация пользовательского режима, ошибки, кольцевой буфер.
void main() {
  setUp(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  tearDownAll(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  group('isErrorLine', () {
    test('распознаёт ошибки в обоих языках', () {
      expect(LogService.isErrorLine('[ОШИБКА] что-то'), isTrue);
      expect(LogService.isErrorLine('connection refused'), isTrue);
      expect(LogService.isErrorLine('request timeout'), isTrue);
      expect(LogService.isErrorLine('[ЯДРО] не удалась авторизация'), isTrue);
      expect(LogService.isErrorLine('[ВОРКЕР] Поток готов ✓'), isFalse);
      // Русинусное «Таймаут» — не в списке ключевых слов (там латиница)
      expect(LogService.isErrorLine('Таймаут ожидания'), isFalse);
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
      expect(LogService().lines.single, matches(r'^\[\d{2}:\d{2}:\d{2}\] \[КОННЕКТ\] Привет$'));
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

      final networkLines =
          logs.lines.where((l) => l.contains('[СЕТЬ]')).toList();
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

    test('режим разработчика пропускает сырой поток', () {
      final logs = LogService();
      LogService.developerMode = true;
      logs.add('[WRAP] секретный технический шаг');
      expect(logs.lines.last, contains('[WRAP] секретный'));

      // Переключение очищает журнал. Разделитель «Обычный режим»
      // сам проходит через пользовательский фильтр и НЕ отображается
      // (виден только при включении dev-режима) — журнал пуст.
      LogService.setDeveloperMode(false);
      expect(logs.lines, isEmpty);

      // Теперь тот же WRAP снова скрыт
      logs.add('[WRAP] ещё один шаг');
      expect(logs.lines, isEmpty);
    });

    test('Exclude-маршруты скрыты от пользователя', () {
      LogService().add('[TUN] Exclude-маршрут TURN 87.240.137.1 добавлен');
      expect(LogService().lines, isEmpty);
    });
  });

  group('кольцевой буфер', () {
    test('при переполнении вытесняются самые старые строки', () {
      final logs = LogService();
      for (var i = 1; i <= LogService.maxLines + 5; i++) {
        logs.add('[КОННЕКТ] строка номер $i');
      }
      expect(logs.lines, hasLength(LogService.maxLines));
      expect(logs.lines.first, contains('строка номер 6'));
      expect(logs.lines.last, contains('строка номер ${LogService.maxLines + 5}'));
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
