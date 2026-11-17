// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/l10n/app_locale.dart';
import 'package:frontend/modals/subscription_details_modal.dart';
import 'package:frontend/models/profile_group.dart';
import 'package:frontend/models/server_profile.dart';

void main() {
  testWidgets(
    'SubscriptionDetailsModal отображает ползунок автообновления и обновляет значение при сдвиге',
    (tester) async {
      final p1 = ServerProfile(
        id: 1,
        name: 'Sub:Demo - N1',
        subscriptionUrl: 'https://demo.test/feed',
      );
      final group = ProfileGroup(
        profiles: [p1],
        subscriptionUrl: 'https://demo.test/feed',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Center(
              child: SingleChildScrollView(
                child: SubscriptionDetailsModal(group: group),
              ),
            ),
          ),
        ),
      );

      // Проверяем наличие заголовков и полей
      expect(find.text(S.subscriptionDetailsTitle), findsOneWidget);
      expect(find.text('Demo'), findsOneWidget);
      expect(find.text('https://demo.test/feed'), findsOneWidget);
      expect(find.text(S.intervalLabel), findsOneWidget);

      // По умолчанию интервал 0 (Отключено)
      expect(find.text(S.autoUpdateOff), findsOneWidget);

      // Находим ползунок (Slider)
      final sliderFinder = find.byType(Slider);
      expect(sliderFinder, findsOneWidget);

      final slider = tester.widget<Slider>(sliderFinder);
      expect(slider.min, 0.0);
      // 8 интервалов: 0, 2, 30, 60, 120, 360, 720, 1440
      expect(slider.max, 7.0);
      expect(slider.divisions, 7);

      final topLeft = tester.getTopLeft(sliderFinder);
      final bottomRight = tester.getBottomRight(sliderFinder);
      final width = bottomRight.dx - topLeft.dx;
      final stepOffset = width / 7;

      // Индекс 1 -> 2 минуты
      await tester.tapAt(Offset(topLeft.dx + stepOffset, topLeft.dy + 10));
      await tester.pumpAndSettle();
      expect(find.text(S.subscriptionIntervalLabel(2)), findsOneWidget);

      // Индекс 2 -> 30 минут
      await tester.tapAt(Offset(topLeft.dx + stepOffset * 2, topLeft.dy + 10));
      await tester.pumpAndSettle();
      expect(find.text(S.subscriptionIntervalLabel(30)), findsOneWidget);

      // Индекс 3 -> 1 час
      await tester.tapAt(Offset(topLeft.dx + stepOffset * 3, topLeft.dy + 10));
      await tester.pumpAndSettle();
      expect(find.text(S.subscriptionIntervalLabel(60)), findsOneWidget);

      // Проверяем поле ссылки и иконку копирования
      expect(find.byIcon(Icons.copy_rounded), findsOneWidget);
      await tester.tap(find.byIcon(Icons.copy_rounded));
      await tester.pump();
      expect(find.byIcon(Icons.check_rounded), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));

      // Проверяем расположение кнопок: Обновить и Удалить в одной строке, Сохранить на всю ширину
      expect(find.text(S.refreshButton), findsOneWidget);
      expect(find.text(S.delete), findsOneWidget);
      expect(find.text(S.save), findsOneWidget);

      final refreshPos = tester.getCenter(find.text(S.refreshButton));
      final deletePos = tester.getCenter(find.text(S.delete));
      final savePos = tester.getCenter(find.text(S.save));

      // Обновить и Удалить на одной горизонтальной линии (y одинаковый)
      expect(refreshPos.dy, equals(deletePos.dy));
      expect(refreshPos.dx, lessThan(deletePos.dx));

      // Сохранить ниже них
      expect(savePos.dy, greaterThan(refreshPos.dy));
    },
  );

  testWidgets('SubscriptionDetailsModal allows editing subscription URL', (
    tester,
  ) async {
    final p1 = ServerProfile(
      id: 1,
      name: 'Sub:Demo - N1',
      subscriptionUrl: 'https://demo.test/feed',
    );
    final group = ProfileGroup(
      profiles: [p1],
      subscriptionUrl: 'https://demo.test/feed',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SingleChildScrollView(
              child: SubscriptionDetailsModal(group: group),
            ),
          ),
        ),
      ),
    );

    final urlFieldFinder = find.widgetWithText(
      TextField,
      'https://demo.test/feed',
    );
    expect(urlFieldFinder, findsOneWidget);

    await tester.enterText(urlFieldFinder, 'https://new-domain.test/sub');
    await tester.pumpAndSettle();

    expect(find.text('https://new-domain.test/sub'), findsOneWidget);
  });

  testWidgets('SubscriptionDetailsModal не сохраняет некорректную ссылку', (
    tester,
  ) async {
    final p1 = ServerProfile(
      id: 1,
      name: 'Sub:Demo - N1',
      subscriptionUrl: 'https://demo.test/feed',
    );
    final group = ProfileGroup(
      profiles: [p1],
      subscriptionUrl: 'https://demo.test/feed',
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SingleChildScrollView(
              child: SubscriptionDetailsModal(group: group),
            ),
          ),
        ),
      ),
    );

    await tester.enterText(find.byType(TextField).at(1), 'ftp://bad');
    await tester.tap(find.text(S.save));
    await tester.pump();

    expect(find.text(S.subscriptionUrlInvalid), findsOneWidget);
    expect(p1.subscriptionUrl, 'https://demo.test/feed');
  });
}
