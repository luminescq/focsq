import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Guard: в lib/ вне l10n не должно появляться непереведённых русских строк.
/// Разрешены:
///  - дата-ключи БД ('Ручной', 'Авто ВК', ...) — по ним работает логика ядра;
///  - файлы, где кириллица — это шаблоны распознавания сырых строк ядра;
///  - dev-инструменты (perf_probe).
/// Комментарии игнорируются. Новую пользовательскую строку добавляй в
/// lib/l10n/app_locale.dart, а не литералом.
void main() {
  test('lib/ не содержит непереведённой кириллицы вне l10n', () {
    // Разрешённые файлы: только шаблоны распознавания / dev-инструменты.
    const allowedFiles = <String>{
      'lib/service/logs.dart', // шаблоны сырых строк ядра
      'lib/screen/logs_screen.dart', // определение уровня строк лога
      'lib/perf_probe.dart', // dev-инструмент
    };
    // Дата-ключи БД: значения хранятся в Isar и сравниваются по строке.
    const allowedLiterals = <String>{
      'Капча',
      'Звонки',
      'Авто ВК',
      'Авто API',
      'Ручной',
      'Простая',
      'Средняя',
    };

    final cyrillic = RegExp(r'[\u0400-\u04FF]');
    final offenders = <String>[];

    final libDir = Directory('lib');
    for (final entity in libDir.listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      final normalized = entity.path.replaceAll('\\', '/');
      final rel = normalized.contains('/lib/')
          ? normalized.substring(normalized.indexOf('/lib/') + 1)
          : normalized;
      if (rel.startsWith('lib/l10n/')) continue;
      if (allowedFiles.contains(rel)) continue;

      final source = entity.readAsStringSync();
      // Грубая чистка комментариев (URL-ы вида https:// не трогаем).
      final noComments = source
          .replaceAll(RegExp(r'(?<!:)//[^\n]*'), '')
          .replaceAll(RegExp(r'/\*[\s\S]*?\*/'), '');

      final lines = noComments.split('\n');
      for (var i = 0; i < lines.length; i++) {
        final line = lines[i];
        if (!cyrillic.hasMatch(line)) continue;
        var withoutKeys = line;
        for (final key in allowedLiterals) {
          withoutKeys = withoutKeys
              .replaceAll("'$key'", '')
              .replaceAll('"$key"', '');
        }
        final onlyAllowedKey = !cyrillic.hasMatch(withoutKeys);
        if (onlyAllowedKey) continue;
        offenders.add('$rel:${i + 1} ${line.trim()}');
      }
    }

    expect(
      offenders,
      isEmpty,
      reason:
          'непереведённые строки — добавь их в lib/l10n/app_locale.dart:\n'
          '${offenders.join('\n')}',
    );
  });
}
