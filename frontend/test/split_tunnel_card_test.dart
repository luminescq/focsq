// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/split_tunnel.dart';
import 'package:frontend/widgets/split_tunnel_card.dart';

void main() {
  Widget host(SplitTunnelCard card) => MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(child: SizedBox(width: 480, child: card)),
    ),
  );

  Future<void> expand(WidgetTester tester) async {
    await tester.tap(find.text('Раздельное туннелирование'));
    await tester.pumpAndSettle();
  }

  Future<void> selectMode(WidgetTester tester, SplitTunnelMode mode) async {
    final selector = find.byKey(const Key('split-apps-mode'));
    await tester.ensureVisible(selector);
    await tester.tap(selector);
    await tester.pumpAndSettle();
    await tester.tap(find.text(splitTunnelModeTitle(mode)).last);
    await tester.pumpAndSettle();
  }

  // [FOCSQ] Проверяем действия пользователя: черновики двух режимов, сохранение и видимый отказ диска.
  testWidgets(
    'mode changes retain unsaved lists and save only on explicit action',
    (tester) async {
      SplitTunnelSettings? saved;
      await tester.pumpWidget(
        host(
          SplitTunnelCard(
            supported: true,
            load: () async => const SplitTunnelSettings(),
            save: (settings) async => saved = settings,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await expand(tester);
      await selectMode(tester, SplitTunnelMode.exclude);
      await tester.enterText(
        find.byKey(const Key('split-apps-entries')),
        r'C:\Apps\direct.exe',
      );
      await selectMode(tester, SplitTunnelMode.include);
      await tester.enterText(
        find.byKey(const Key('split-apps-entries')),
        r'C:\Apps\tunnel.exe',
      );
      await selectMode(tester, SplitTunnelMode.exclude);
      final field = tester.widget<TextField>(
        find.byKey(const Key('split-apps-entries')),
      );
      expect(field.controller!.text, r'C:\Apps\direct.exe');
      expect(saved, isNull);
      await tester.ensureVisible(find.byKey(const Key('split-save')));
      await tester.tap(find.byKey(const Key('split-save')));
      await tester.pumpAndSettle();
      expect(saved!.apps.exclude, [r'C:\Apps\direct.exe']);
      expect(saved!.apps.include, [r'C:\Apps\tunnel.exe']);
      expect(
        find.text('Сохранено. Правила применятся после переподключения.'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'corrupt configuration stays visible and requires explicit new setup',
    (tester) async {
      var saved = false;
      await tester.pumpWidget(
        host(
          SplitTunnelCard(
            supported: true,
            load: () async =>
                throw const FormatException('Synthetic invalid JSON'),
            save: (_) async => saved = true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await expand(tester);
      expect(
        find.textContaining('Не удалось прочитать split_tunnel.json.'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('split-save')), findsNothing);
      expect(saved, isFalse);
      await tester.tap(find.text('Настроить заново'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('split-save')), findsOneWidget);
      expect(saved, isFalse);
    },
  );

  // [FOCSQ] Выбор процесса не стирает ручной путь к приложению, которое сейчас закрыто.
  testWidgets('running app picker preserves manually added paths until save', (
    tester,
  ) async {
    var saved = false;
    await tester.pumpWidget(
      host(
        SplitTunnelCard(
          supported: true,
          load: () async => const SplitTunnelSettings(
            apps: SplitTunnelRules(
              mode: SplitTunnelMode.exclude,
              exclude: [r'C:\Apps\closed.exe'],
            ),
          ),
          save: (_) async => saved = true,
          loadApplications: () async => [
            r'C:\Apps\running.exe',
            r'C:\Other\running.exe',
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();
    await expand(tester);
    final choose = find.text('Выбрать запущенные приложения');
    await tester.ensureVisible(choose);
    await tester.tap(choose);
    await tester.pumpAndSettle();
    expect(find.text(r'C:\Apps\running.exe'), findsOneWidget);
    expect(find.text(r'C:\Other\running.exe'), findsOneWidget);
    await tester.tap(
      find.widgetWithText(CheckboxListTile, r'C:\Apps\running.exe'),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Выбрать'));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(
      find.byKey(const Key('split-apps-entries')),
    );
    expect(
      field.controller!.text,
      'C:\\Apps\\closed.exe\nC:\\Apps\\running.exe',
    );
    expect(saved, isFalse);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unsupported platform has no editable controls', (tester) async {
    await tester.pumpWidget(host(const SplitTunnelCard(supported: false)));
    await expand(tester);
    expect(
      find.text('Настройка split tunnelling доступна для Windows.'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('split-apps-mode')), findsNothing);
  });
}
