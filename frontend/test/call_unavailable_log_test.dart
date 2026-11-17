import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/l10n/app_locale.dart';
import 'package:frontend/service/connect.dart';
import 'package:frontend/service/logs.dart';

/// Регрессия: дохлый хеш (CALL_UNAVAILABLE от ядра) обязан попадать в
/// пользовательский журнал как ошибка в обоих языках, а сырая строка
/// события — нет.
void main() {
  setUp(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  tearDownAll(() {
    LogService.developerMode = false;
    LogService().clear();
    LocaleService.dirOverrideForTest = null;
  });

  test('CALL_UNAVAILABLE пишет ошибку хеша в журнал (RU)', () {
    ConnectService().onCoreLine(
      '__CSQTT_EVENT__|CALL_UNAVAILABLE|{"hash":"dead-call","code":951}',
    );

    final lines = LogService().lines;
    expect(
      lines.where(
        (l) => l.contains('[ЗВОНКИ] Ошибка: хеш звонка недоступен (код 951)'),
      ),
      isNotEmpty,
      reason: 'ошибка дохлого хеша должна быть видна в обычном режиме',
    );
    expect(
      lines.where((l) => l.contains('__CSQTT_EVENT__')),
      isEmpty,
      reason: 'сырые строки событий в журнал не попадают',
    );
  });

  test('CALL_UNAVAILABLE пишет ошибку хеша в журнал (EN)', () async {
    final temp = await Directory.systemTemp.createTemp('focsq_locale_test');
    LocaleService.dirOverrideForTest = () => temp;
    await LocaleService.setLanguage(AppLanguage.english);
    LogService().clear();

    ConnectService().onCoreLine(
      '__CSQTT_EVENT__|CALL_UNAVAILABLE|{"hash":"dead-call","code":951}',
    );

    expect(
      LogService().lines.where(
        (l) => l.contains('[CALLS] Error: call hash unavailable (code 951)'),
      ),
      isNotEmpty,
    );

    await LocaleService.setLanguage(AppLanguage.russian);
    LocaleService.dirOverrideForTest = null;
  });

  test('битый payload CALL_UNAVAILABLE не роняет обработку', () {
    ConnectService().onCoreLine('__CSQTT_EVENT__|CALL_UNAVAILABLE|{"hash":');
    // Защитный дефолт: код неизвестен → 0, но событие не теряется и не роняет
    // обработчик.
    expect(
      LogService().lines.where(
        (l) => l.contains('[ЗВОНКИ] Ошибка: хеш звонка недоступен (код 0)'),
      ),
      isNotEmpty,
    );
  });
}
