// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/server_profile.dart';
import 'package:frontend/widgets/subscription_card.dart';

void main() {
  testWidgets(
    'SubscriptionCard рендерит заголовок с облаком, ноды и обновляет',
    (tester) async {
      var refreshed = false;
      var nodeTapped = false;
      var editTapped = false;

      final p1 = ServerProfile(
        id: 1,
        name: 'Sub:Demo demo - Node 0 (demo)',
        subscriptionUrl: 'https://demo.test/feed',
      );
      final p2 = ServerProfile(
        id: 2,
        name: 'Sub:Demo demo - Node 1 (demo)',
        subscriptionUrl: 'https://demo.test/feed',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 320,
                child: SubscriptionCard(
                  title: 'Demo demo',
                  onRefresh: () => refreshed = true,
                  items: [
                    SubscriptionItemData(
                      profile: p1,
                      isSelected: false,
                      onTap: () => nodeTapped = true,
                      onEditTap: () => editTapped = true,
                    ),
                    SubscriptionItemData(
                      profile: p2,
                      isSelected: false,
                      onTap: () {},
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );

      // Проверяем наличие заголовка и облака
      expect(find.text('Demo demo'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_download_rounded), findsOneWidget);
      expect(find.byIcon(Icons.refresh_rounded), findsOneWidget);

      // Проверяем отображение очищенных имён узлов
      expect(find.text('Node 0 (demo)'), findsOneWidget);
      expect(find.text('Node 1 (demo)'), findsOneWidget);
      expect(find.byIcon(Icons.speed_rounded), findsNWidgets(2));
      expect(find.byIcon(Icons.edit_rounded), findsOneWidget);

      // Нажатие на кнопку обновления в шапке
      await tester.tap(find.byIcon(Icons.refresh_rounded));
      await tester.pump();
      expect(refreshed, isTrue);

      // Нажатие на строку узла
      await tester.tap(find.text('Node 0 (demo)'));
      await tester.pump();
      expect(nodeTapped, isTrue);

      // Нажатие на карандаш
      await tester.tap(find.byIcon(Icons.edit_rounded));
      await tester.pump();
      expect(editTapped, isTrue);
    },
  );

  testWidgets(
    'SubscriptionCard показывает индикатор загрузки при isRefreshing: true',
    (tester) async {
      final p1 = ServerProfile(
        id: 1,
        name: 'Sub:Demo - N1',
        subscriptionUrl: 'https://demo.test',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 320,
              child: SubscriptionCard(
                title: 'Demo',
                isRefreshing: true,
                onRefresh: () {},
                items: [SubscriptionItemData(profile: p1, onTap: () {})],
              ),
            ),
          ),
        ),
      );

      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(find.byIcon(Icons.refresh_rounded), findsNothing);
    },
  );

  testWidgets(
    'SubscriptionCard сворачивается по шапке, действия появляются на hover',
    (tester) async {
      var edited = false;
      var deleted = false;
      final p1 = ServerProfile(
        id: 1,
        name: 'Sub:Demo - N1',
        subscriptionUrl: 'https://demo.test',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 320,
              child: SubscriptionCard(
                title: 'Demo',
                onEdit: () => edited = true,
                onDelete: () => deleted = true,
                items: [SubscriptionItemData(profile: p1, onTap: () {})],
              ),
            ),
          ),
        ),
      );

      expect(find.text('N1'), findsOneWidget);

      await tester.tap(find.text('Demo'));
      await tester.pumpAndSettle();
      expect(find.text('N1'), findsNothing);

      await tester.tap(find.text('Demo'));
      await tester.pumpAndSettle();
      expect(find.text('N1'), findsOneWidget);

      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      await mouse.moveTo(tester.getCenter(find.text('Demo')));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.edit_rounded), findsOneWidget);
      expect(find.byIcon(Icons.delete_outline_rounded), findsOneWidget);

      await tester.tap(find.byIcon(Icons.edit_rounded));
      await tester.pump();
      expect(edited, isTrue);

      await tester.tap(find.byIcon(Icons.delete_outline_rounded));
      await tester.pump();
      expect(deleted, isTrue);
    },
  );
}
