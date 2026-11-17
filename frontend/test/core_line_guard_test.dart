import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/l10n/core_line_localizer.dart';

/// Guard по мотивам Sonora: сканирует исходники Rust-ядра и требует, чтобы
/// каждый кириллический литерал внутри лог-макросов был покрыт правилом
/// `CoreLineLocalizer`. Если ядро добавило новое русское сообщение — тест
/// падает и подсказывает, какое правило добавить в словарь.
///
/// Ядро при этом не модифицируется: перевод живёт на границе приёма.
void main() {
  test('ядро: все русские лог-строки покрыты словарём локализации', () {
    final backend = Directory('../backend');
    expect(
      backend.existsSync(),
      isTrue,
      reason: 'не найден каталог ../backend — guard запускается из frontend',
    );

    final macroCall = RegExp(
      r'\b(?:log_error|log_output|log_info|log_warn|log_debug|println|eprintln)\s*!\s*\(',
    );
    final stringLiteral = RegExp(r'"((?:[^"\\]|\\.)*)"');
    final cyrillic = RegExp(r'[\u0400-\u04FF]');
    final placeholder = RegExp(r'\{[^{}]*\}');

    final uncovered = <String>[];

    for (final entity in backend.listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.rs')) continue;
      final normalized = entity.path.replaceAll('\\', '/');
      if (normalized.contains('/target/') || normalized.contains('/vendor/')) {
        continue;
      }
      var source = entity.readAsStringSync();
      // Тест-модули в релиз не попадают — вырезаем до конца файла.
      final cut = source.indexOf('#[cfg(test)]');
      if (cut != -1) source = source.substring(0, cut);

      for (final macro in macroCall.allMatches(source)) {
        var depth = 0;
        var i = macro.end - 1;
        while (i < source.length) {
          final ch = source[i];
          if (ch == '(') {
            depth++;
          } else if (ch == ')') {
            depth--;
            if (depth == 0) break;
          }
          i++;
        }
        final inner = source.substring(macro.end - 1, i + 1);
        final joined = stringLiteral
            .allMatches(inner)
            .map((m) => m.group(1)!)
            .join()
            .replaceAll(r'\"', '"')
            .replaceAll(r'\\', '\\');
        if (!cyrillic.hasMatch(joined)) continue;

        final sample = joined.replaceAll(placeholder, 'X');
        if (!CoreLineLocalizer.covers(sample)) {
          final rel = normalized.substring(
            normalized.indexOf('/backend/') + '/backend/'.length,
          );
          final line = source.substring(0, macro.start).split('\n').length;
          uncovered.add('$rel:$line "$joined"');
        }
      }
    }

    expect(
      uncovered,
      isEmpty,
      reason:
          'непокрытые строки ядра — добавь правило в '
          'lib/l10n/core_line_localizer.dart:\n${uncovered.join('\n')}',
    );
  });
}
