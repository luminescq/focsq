import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/widgets/custom_alert.dart';

void main() {
  testWidgets('алерт показывается через overlay навигатора', (tester) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: const Scaffold(body: Text('screen')),
      ),
    );

    CustomAlert.show(
      navigatorKey.currentContext!,
      title: 'Успех',
      message: 'Профиль добавлен',
      type: AlertType.success,
      overlay: navigatorKey.currentState!.overlay,
    );
    await tester.pump();

    expect(find.text('Успех'), findsOneWidget);
    expect(find.text('Профиль добавлен'), findsOneWidget);
  });
}
