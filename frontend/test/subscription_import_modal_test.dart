// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/l10n/app_locale.dart';
import 'package:frontend/modals/subscription_import.dart';

void main() {
  testWidgets(
    'SubscriptionImportModal отображает поле ввода, подсказку и кнопки без переноса текста',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: Center(
              child: SingleChildScrollView(child: SubscriptionImportModal()),
            ),
          ),
        ),
      );

      // Проверяем элементы интерфейса
      expect(find.text(S.subscriptionImportTitle), findsOneWidget);
      expect(find.text(S.addFromSubscription), findsOneWidget);
      expect(find.text(S.subscriptionImportHint), findsOneWidget);
      expect(find.text(S.cancelButton), findsOneWidget);
      expect(find.text(S.importButton), findsOneWidget);

      // Проверяем, что текст кнопки "Импортировать" помещается в одну строку
      final textWidget = tester.widget<Text>(find.text(S.importButton));
      expect(textWidget.maxLines, 1);
      expect(textWidget.softWrap, isFalse);

      // Проверяем валидацию пустого URL
      await tester.tap(find.text(S.importButton));
      await tester.pumpAndSettle();

      expect(find.text(S.subscriptionUrlRequired), findsOneWidget);
    },
  );
}
