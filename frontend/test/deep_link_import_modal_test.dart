import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/modals/deep_link_import_modal.dart';
import 'package:frontend/models/server_profile.dart';
import 'package:frontend/service/deep_link.dart';

void main() {
  final profile = ServerProfile(
    name: 'Сервер #1000',
    ip: 'example.com',
    port: 443,
    password: 'super-secret-password',
  );

  test('дубликат определяется по адресу и порту', () {
    final existing = [
      ServerProfile(ip: 'example.com', port: 443),
      ServerProfile(ip: 'example.com', port: 8443),
    ];

    expect(DeepLinkService.isDuplicate(profile, existing), isTrue);
    expect(
      DeepLinkService.isDuplicate(
        ServerProfile(ip: 'other.example', port: 443),
        existing,
      ),
      isFalse,
    );
  });

  testWidgets('дубликат и хеши показаны без пароля', (tester) async {
    bool? result;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await DeepLinkImportModal.show(
                context,
                profile: profile,
                hasHashes: true,
                isDuplicate: true,
              );
            },
            child: const Text('Открыть'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Открыть'));
    await tester.pumpAndSettle();

    expect(find.text('Импорт профиля'), findsOneWidget);
    expect(
      find.text('Сервер уже добавлен — профиль не создастся'),
      findsOneWidget,
    );
    expect(find.text('Хеши будут обновлены'), findsOneWidget);
    expect(find.text('super-secret-password'), findsNothing);

    await tester.tap(find.text('Импортировать'));
    await tester.pumpAndSettle();
    expect(result, isTrue);
  });

  testWidgets('отмена возвращает false', (tester) async {
    bool? result;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await DeepLinkImportModal.show(
                context,
                profile: profile,
                hasHashes: false,
                isDuplicate: false,
              );
            },
            child: const Text('Открыть'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Открыть'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Отмена'));
    await tester.pumpAndSettle();

    expect(result, isFalse);
    expect(
      find.text('Сервер уже добавлен — профиль не создастся'),
      findsNothing,
    );
    expect(find.text('Хеши будут обновлены'), findsNothing);
  });
}
