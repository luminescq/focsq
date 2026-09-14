import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/screen/logs_screen.dart';
import 'package:frontend/service/app_visibility.dart';
import 'package:frontend/service/logs.dart';
import 'package:frontend/widgets/custom_alert.dart';

/// Стресс-тесты UI: «жёсткое использование» — спам алертами, шторм логов,
/// молниеносный набор поиска, постоянное пересоздание экранов.
///
/// В flutter test нет растеризатора, поэтому цель здесь не FPS, а поломки:
/// исключения в async-колбэках, накопление оверлеев, утечки слушателей,
/// потеря переживаемого состояния, зависшие таймеры.
void main() {
  setUp(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  tearDown(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  // =========================================================================
  // AppVisibility: шторм переключений видимости
  // =========================================================================

  group('AppVisibility — шторм переключений', () {
    test('10 000 реальных смен — уведомление на каждую, значение верно', () {
      final visibility = AppVisibility.instance;
      visibility.setVisible(false);
      var notifications = 0;
      void listener() => notifications++;
      visibility.visible.addListener(listener);

      var value = false;
      for (var i = 0; i < 10000; i++) {
        value = !value;
        visibility.setVisible(value);
      }

      visibility.visible.removeListener(listener);
      // 10000 чётных переворотов возвращают к стартовому значению.
      expect(notifications, 10000);
      expect(visibility.visible.value, isFalse);
    });

    test('10 000 одинаковых значений — ноль уведомлений (дедуп)', () {
      final visibility = AppVisibility.instance;
      visibility.setVisible(true);
      var notifications = 0;
      void listener() => notifications++;
      visibility.visible.addListener(listener);

      for (var i = 0; i < 10000; i++) {
        visibility.setVisible(true);
      }

      visibility.visible.removeListener(listener);
      expect(notifications, 0);
    });
  });

  // =========================================================================
  // LogService: шторм логов
  // =========================================================================

  group('LogService — шторм логов', () {
    test('20 000 строк одним куском: буфер обрезан, ОДНО уведомление', () async {
      var notifications = 0;
      LogService().addListener(() => notifications++);

      final sw = Stopwatch()..start();
      for (var i = 0; i < 20000; i++) {
        LogService().add('[КОННЕКТ] шторм $i');
      }
      sw.stop();

      // Кольцевой буфер вытеснил самое старое, хвост на месте.
      expect(LogService().lines.length, LogService.maxLines);
      expect(LogService().lines.first, contains('шторм 18000'));
      expect(LogService().lines.last, contains('шторм 19999'));

      // Коалесинг: весь синхронный шторм = один вызов слушателей.
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect(notifications, 1);
      expect(notifications, lessThan(20000));

      // Шторм не должен занимать заметного времени.
      expect(sw.elapsedMilliseconds, lessThan(3000));

      LogService().removeListener(() {});
    });

    test('пачки с паузами: уведомлений сильно меньше, чем строк', () async {
      var notifications = 0;
      LogService().addListener(() => notifications++);

      const batches = 50;
      const perBatch = 40;
      for (var b = 0; b < batches; b++) {
        for (var i = 0; i < perBatch; i++) {
          LogService().add('[КОННЕКТ] пачка $b строка $i');
        }
        await Future<void>.delayed(const Duration(milliseconds: 60));
      }

      expect(LogService().lines.length, LogService.maxLines);
      expect(notifications, lessThan(batches * perBatch));
      expect(notifications, greaterThan(0));
      expect(notifications, lessThanOrEqualTo(batches));
    });
  });

  // =========================================================================
  // CustomAlert: спам алертами
  // =========================================================================

  group('CustomAlert — спам алертами', () {
    /// Дождаться, пока все алерты скроются и таймеры отработают,
    /// иначе testWidgets провалится на «Timer is still pending».
    Future<void> drainAlerts(WidgetTester tester) async {
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
    }

    testWidgets('200 показов в один кадр — жив только последний', (tester) async {
      BuildContext? alertContext;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) {
            alertContext = context;
            return const SizedBox();
          },
        ),
      ));

      for (var i = 0; i < 200; i++) {
        CustomAlert.show(
          alertContext!,
          title: 'алерт $i',
          message: 'тело $i',
          type: AlertType.info,
        );
      }
      await tester.pump();

      expect(find.text('алерт 199'), findsOneWidget);
      expect(find.text('алерт 198'), findsNothing);
      expect(find.text('алерт 0'), findsNothing);

      await drainAlerts(tester);
      // Автоскрытие сняло последний алерт — ничего не осталось.
      expect(find.text('алерт 199'), findsNothing);
    });

    testWidgets('чередование показов и таймеров автоскрытия', (tester) async {
      BuildContext? alertContext;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) {
            alertContext = context;
            return const SizedBox();
          },
        ),
      ));
      // Каждые 300мс новый алерт — предыдущий обязан сниматься мгновенно,
      // даже когда их уже начал прятать 4-секундный таймер.
      for (var i = 0; i < 30; i++) {
        CustomAlert.show(
          alertContext!,
          title: 'волна $i',
          message: 'тело',
          type: AlertType.warning,
        );
        await tester.pump(const Duration(milliseconds: 300));

        expect(find.text('волна $i'), findsOneWidget);
        if (i > 0) expect(find.text('волна ${i - 1}'), findsNothing);
      }

      await drainAlerts(tester);
      expect(find.text('волна 29'), findsNothing);
    });

    testWidgets('закрыть крестиком и сразу показать новый во время анимации',
        (tester) async {
      BuildContext? alertContext;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) {
            alertContext = context;
            return const SizedBox();
          },
        ),
      ));

      // Самый жёсткий сценарий: тап по крестику запускает reverse-анимацию
      // (350мс), и не дожидаясь её конца показываем следующий алерт —
      // старый Entry снимается из оверлея, пока его контроллер ещё жив.
      for (var i = 0; i < 20; i++) {
        CustomAlert.show(
          alertContext!,
          title: 'спам $i',
          message: 'тело',
          type: AlertType.success,
        );
        // Первый pump строит entry (тикер ещё на нуле), второй доматывает
        // слайд-ин до конца — иначе кнопка закрытия за краем окна.
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 350));
        await tester.tap(find.byIcon(Icons.close_rounded));
        await tester.pump(); // reverse идёт — сразу следующий круг
      }
      await tester.pumpAndSettle();

      expect(find.textContaining('спам'), findsNothing);
    });
  });

  // =========================================================================
  // LogsScreen: жёсткое использование экрана логов
  // =========================================================================

  group('LogsScreen — жёсткое использование', () {
    /// Открыть экран логов и погасить сохранённый между тестами поиск.
    Future<void> pumpLogs(WidgetTester tester) async {
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: LogsScreen())),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '');
      await tester.pumpAndSettle();
    }

    testWidgets('шторм 5000 строк на живом экране', (tester) async {
      await pumpLogs(tester);

      // 50 пачек по 100 строк: каждая пачка — одно уведомление (50мс
      // коалесинг), между пачками прокручивается автовозвращение к концу.
      for (var batch = 0; batch < 50; batch++) {
        for (var i = 0; i < 100; i++) {
          LogService().add('[КОННЕКТ] событие $batch-$i');
        }
        await tester.pump(const Duration(milliseconds: 60));
      }
      await tester.pumpAndSettle();

      expect(LogService().lines.length, LogService.maxLines);
      expect(LogService().lines.last, contains('событие 49-99'));

      // Экран жив и отвечает на поиск по свежему хвосту.
      await tester.enterText(find.byType(TextField), 'событие 49-99');
      await tester.pumpAndSettle();
      // Совпадений минимум два: сам запрос в поле поиска и строка журнала.
      expect(find.text('событие 49-99'), findsWidgets);

      // Очистка поля возвращает полный журнал (проверяем по сервису:
      // ListView строит лениво, и середина списка может быть не собрана).
      await tester.enterText(find.byType(TextField), '');
      await tester.pumpAndSettle();
      expect(LogService().lines.length, LogService.maxLines);
      expect(LogService().lines.last, contains('событие 49-99'));
    });

    testWidgets('30 пересозданий экрана: поиск пережил, слушатели не текут',
        (tester) async {
      await pumpLogs(tester);

      // Переключение вкладок пересоздаёт экран целиком (AnimatedSwitcher).
      await tester.enterText(find.byType(TextField), 'TURN');
      await tester.pumpAndSettle();

      for (var i = 0; i < 30; i++) {
        await tester
            .pumpWidget(const MaterialApp(home: Scaffold(body: SizedBox())));
        await tester
            .pumpWidget(const MaterialApp(home: Scaffold(body: LogsScreen())));
        await tester.pump();
      }
      await tester.pumpAndSettle();

      // Переживаемое состояние на месте.
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.controller!.text, 'TURN');

      // Новый лог после 30 пересозданий долетает до панели: если бы
      // слушатели снятых экранов копились, тест упал бы на их колбэках.
      LogService().add('[КОННЕКТ] TURN жив');
      await tester.pumpAndSettle();
      expect(find.text('TURN жив'), findsOneWidget);
    });

    testWidgets('молниеносный набор поиска без пауз между кадрами',
        (tester) async {
      await pumpLogs(tester);
      LogService().add('[КОННЕКТ] TURN аллокация');
      LogService().add('[КОННЕКТ] обычное сообщение');
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pumpAndSettle();

      // Каждый символ — новая перестройка тулбара и панели; settle не ждём.
      const queries = [
        'т', 'tur', 'turn', 'turn ', 'turn а', 'turn ал', 'turn алл',
        'turn алло', 'turn аллок', 'turn аллокац', 'turn аллокация',
      ];
      for (final q in queries) {
        await tester.enterText(find.byType(TextField), q);
        await tester.pump(const Duration(milliseconds: 16));
      }
      await tester.pumpAndSettle();
      expect(find.text('TURN аллокация'), findsOneWidget);

      // Мимо цели — заглушка «ничего не найдено».
      await tester.enterText(find.byType(TextField), 'такого нет');
      await tester.pumpAndSettle();
      expect(find.textContaining('Ничего не найдено'), findsOneWidget);

      // Крестик очищает поиск и возвращает полный журнал.
      await tester.tap(find.byIcon(Icons.close));
      await tester.pumpAndSettle();
      expect(find.text('TURN аллокация'), findsOneWidget);
      expect(find.text('обычное сообщение'), findsOneWidget);
    });

    testWidgets('два экрана логов живы одновременно — у каждого свои контроллеры',
        (tester) async {
      // Переход вкладок в AnimatedSwitcher 500мс держит старый и новый
      // экран живыми вместе. Если бы контроллеры были static, оба ListView
      // повисли бы на одном ScrollController — это запрещено и в дебаге
      // падает ассертом. Прогоняем худший случай напрямую.
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Stack(children: [LogsScreen(), LogsScreen()]),
        ),
      ));
      await tester.pumpAndSettle();

      LogService().add('[КОННЕКТ] два экрана');
      await tester.pumpAndSettle();
      expect(find.text('два экрана'), findsNWidgets(2));

      final fields = tester.widgetList<TextField>(find.byType(TextField));
      expect(fields, hasLength(2));
      expect(fields.first.controller, isNot(same(fields.last.controller)));
    });
  });
}
