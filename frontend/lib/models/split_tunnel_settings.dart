// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import 'dart:convert';

// [FOCSQ] Строковые значения — общий контракт Flutter/Rust и файла настроек.
enum SplitTunnelMode { off, exclude, include }

enum SplitTunnelKind { apps, domains, ips }

// [FOCSQ] Оба списка сохраняются независимо: смена режима не стирает правила.
class SplitTunnelRules {
  final SplitTunnelMode mode;
  final List<String> include;
  final List<String> exclude;

  const SplitTunnelRules({
    this.mode = SplitTunnelMode.off,
    this.include = const [],
    this.exclude = const [],
  });

  List<String> get active =>
      mode == SplitTunnelMode.include ? include : exclude;

  SplitTunnelRules copyWith({
    SplitTunnelMode? mode,
    List<String>? include,
    List<String>? exclude,
  }) => SplitTunnelRules(
    mode: mode ?? this.mode,
    include: List.unmodifiable(include ?? this.include),
    exclude: List.unmodifiable(exclude ?? this.exclude),
  );

  Map<String, Object> toJson() => {
    'mode': mode.name,
    'include': include,
    'exclude': exclude,
  };

  static SplitTunnelRules fromJson(Object? value, SplitTunnelKind kind) {
    if (value is! Map<String, dynamic>) {
      throw FormatException('Некорректный раздел ${kind.name}.');
    }
    final mode = SplitTunnelMode.values.where((m) => m.name == value['mode']);
    if (mode.isEmpty) {
      throw FormatException('Неизвестный режим ${kind.name}.');
    }
    List<String> readList(String name) {
      final list = value[name];
      if (list is! List || list.any((entry) => entry is! String)) {
        throw FormatException('Некорректный список ${kind.name}/$name.');
      }
      return normalizeSplitEntries(kind, list.cast<String>());
    }

    return SplitTunnelRules(
      mode: mode.first,
      include: readList('include'),
      exclude: readList('exclude'),
    );
  }
}

// [FOCSQ] Отсутствие файла соответствует прежнему полному туннелю, без миграции Isar.
class SplitTunnelSettings {
  static const int version = 1;
  static const int maxEntries = 1024;
  static const int maxFileBytes = 512 * 1024;

  final SplitTunnelRules apps;
  final SplitTunnelRules domains;
  final SplitTunnelRules ips;

  const SplitTunnelSettings({
    this.apps = const SplitTunnelRules(),
    this.domains = const SplitTunnelRules(),
    this.ips = const SplitTunnelRules(),
  });

  bool get enabled => SplitTunnelKind.values.any(
    (kind) => rules(kind).mode != SplitTunnelMode.off,
  );

  SplitTunnelRules rules(SplitTunnelKind kind) => switch (kind) {
    SplitTunnelKind.apps => apps,
    SplitTunnelKind.domains => domains,
    SplitTunnelKind.ips => ips,
  };

  SplitTunnelSettings withRules(SplitTunnelKind kind, SplitTunnelRules value) =>
      SplitTunnelSettings(
        apps: kind == SplitTunnelKind.apps ? value : apps,
        domains: kind == SplitTunnelKind.domains ? value : domains,
        ips: kind == SplitTunnelKind.ips ? value : ips,
      );

  // [FOCSQ] Валидируем также неактивный список, чтобы повреждённые правила не скрывались.
  SplitTunnelSettings normalized() {
    var result = const SplitTunnelSettings();
    for (final kind in SplitTunnelKind.values) {
      final group = rules(kind);
      final normalized = group.copyWith(
        include: normalizeSplitEntries(kind, group.include),
        exclude: normalizeSplitEntries(kind, group.exclude),
      );
      if (normalized.mode == SplitTunnelMode.include &&
          normalized.include.isEmpty) {
        throw FormatException(
          '${splitTunnelKindTitle(kind)}: добавьте хотя бы одно правило для режима «Только выбранное».',
        );
      }
      result = result.withRules(kind, normalized);
    }
    return result;
  }

  Map<String, Object> toJson() => {
    'version': version,
    'apps': apps.toJson(),
    'domains': domains.toJson(),
    'ips': ips.toJson(),
  };

  String toJsonString() => jsonEncode(normalized().toJson());

  static SplitTunnelSettings fromJsonString(String text) {
    final value = jsonDecode(text);
    if (value is! Map<String, dynamic> || value['version'] != version) {
      throw const FormatException(
        'Неподдерживаемый формат настроек split tunnelling.',
      );
    }
    return SplitTunnelSettings(
      apps: SplitTunnelRules.fromJson(value['apps'], SplitTunnelKind.apps),
      domains: SplitTunnelRules.fromJson(
        value['domains'],
        SplitTunnelKind.domains,
      ),
      ips: SplitTunnelRules.fromJson(value['ips'], SplitTunnelKind.ips),
    ).normalized();
  }
}

String splitTunnelKindTitle(SplitTunnelKind kind) => switch (kind) {
  SplitTunnelKind.apps => 'Приложения',
  SplitTunnelKind.domains => 'Домены',
  SplitTunnelKind.ips => 'IP и подсети',
};

String splitTunnelModeTitle(SplitTunnelMode mode) => switch (mode) {
  SplitTunnelMode.off => 'Выключено',
  SplitTunnelMode.exclude => 'Исключить выбранное',
  SplitTunnelMode.include => 'Только выбранное',
};

// [FOCSQ] Пути EXE делим только по строкам: пробел и запятая допустимы в имени файла.
List<String> parseSplitEntries(SplitTunnelKind kind, String text) {
  final prepared = kind == SplitTunnelKind.ips
      ? text.replaceAll(RegExp(r'\s*-\s*'), '-')
      : text;
  return normalizeSplitEntries(
    kind,
    prepared.split(
      kind == SplitTunnelKind.apps ? RegExp(r'[\r\n]+') : RegExp(r'[\s,;]+'),
    ),
  );
}

List<String> normalizeSplitEntries(
  SplitTunnelKind kind,
  Iterable<String> entries,
) {
  final result = <String>[];
  final seen = <String>{};
  for (final raw in entries) {
    if (raw.trim().isEmpty) continue;
    final value = switch (kind) {
      SplitTunnelKind.apps => _normalizeApplication(raw),
      SplitTunnelKind.domains => _normalizeDomain(raw),
      SplitTunnelKind.ips => _normalizeIpRule(raw),
    };
    if (seen.add(kind == SplitTunnelKind.apps ? value.toLowerCase() : value)) {
      result.add(value);
      if (result.length > SplitTunnelSettings.maxEntries) {
        throw FormatException(
          '${splitTunnelKindTitle(kind)}: максимум ${SplitTunnelSettings.maxEntries} правил в списке.',
        );
      }
    }
  }
  return List.unmodifiable(result);
}

// [FOCSQ] Полный путь различает одноимённые EXE; относительный путь не зависит от cwd.
String _normalizeApplication(String raw) {
  var value = raw.trim();
  if (value.startsWith('"') && value.endsWith('"') && value.length > 1) {
    value = value.substring(1, value.length - 1);
  }
  value = value.replaceAll('/', r'\');
  final drive = RegExp(r'^[a-zA-Z]:\\').hasMatch(value);
  final unc = RegExp(r'^\\\\[^\\]+\\[^\\]+\\').hasMatch(value);
  final tail = drive ? value.substring(2) : value;
  if (value.length > 32767 ||
      (!drive && !unc) ||
      !value.toLowerCase().endsWith('.exe') ||
      RegExp(r'[\x00-\x1f"<>|?*: ]$').hasMatch(value) ||
      RegExp(r'[\x00-\x1f"<>|?*:]').hasMatch(tail) ||
      value.split(r'\').any((part) => part == '.' || part == '..')) {
    throw const FormatException(
      'Приложения: укажите полный путь к .exe без аргументов, например C:\\Apps\\browser.exe.',
    );
  }
  return drive ? value[0].toUpperCase() + value.substring(1) : value;
}

// [FOCSQ] Правило домена охватывает его поддомены; URL, порт и IP здесь не подходят.
String _normalizeDomain(String raw) {
  var value = raw.trim().toLowerCase().replaceAll(
    RegExp('[\u3002\uff0e\uff61]'),
    '.',
  );
  if (value.endsWith('.')) value = value.substring(0, value.length - 1);
  if (value.startsWith('*.')) value = value.substring(2);
  if (value.isEmpty ||
      value.length > 253 ||
      RegExp(r'[\s\x00-\x20\x7f/\\:@?#%]').hasMatch(value) ||
      RegExp('[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]')
          .hasMatch(value)) {
    throw const FormatException(
      'Домены: введите имя без протокола, пути и порта, например example.com.',
    );
  }
  final labels = value
      .split('.')
      .map((label) {
        final ascii = label.runes.every((rune) => rune < 128)
            ? label
            : 'xn--${_punycode(label)}';
        if (ascii.length > 63 ||
            !RegExp(r'^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$').hasMatch(ascii)) {
          throw const FormatException(
            'Домены: недопустимая метка в имени домена.',
          );
        }
        return ascii;
      })
      .join('.');
  if (labels.length > 253 || RegExp(r'^[0-9.]+$').hasMatch(labels)) {
    throw const FormatException(
      'Домены: недопустимое имя; IP задайте в разделе «IP и подсети».',
    );
  }
  return labels;
}

// [FOCSQ] RFC 3492 кодирует Unicode-метки в тот же ASCII-вид, который получит ядро.
String _punycode(String label) {
  final input = label.runes.toList();
  var n = 128;
  var delta = 0;
  var bias = 72;
  final output = StringBuffer();
  for (final code in input) {
    if (code < 128) {
      output.writeCharCode(code);
    }
  }
  var handled = output.length;
  final basic = handled;
  if (basic > 0) output.write('-');
  while (handled < input.length) {
    final next = input
        .where((code) => code >= n)
        .reduce((a, b) => a < b ? a : b);
    delta += (next - n) * (handled + 1);
    n = next;
    for (final code in input) {
      if (code < n) delta++;
      if (code != n) continue;
      var q = delta;
      for (var k = 36; ; k += 36) {
        final threshold = k <= bias ? 1 : (k >= bias + 26 ? 26 : k - bias);
        if (q < threshold) break;
        final digit = threshold + (q - threshold) % (36 - threshold);
        output.writeCharCode(digit < 26 ? digit + 97 : digit - 26 + 48);
        q = (q - threshold) ~/ (36 - threshold);
      }
      output.writeCharCode(q < 26 ? q + 97 : q - 26 + 48);
      var adapted = handled == basic ? delta ~/ 700 : delta ~/ 2;
      adapted += adapted ~/ (handled + 1);
      var k = 0;
      while (adapted > 455) {
        adapted ~/= 35;
        k += 36;
      }
      bias = k + (36 * adapted) ~/ (adapted + 38);
      delta = 0;
      handled++;
    }
    delta++;
    n++;
  }
  return output.toString();
}

// [FOCSQ] IPv4/CIDR и диапазоны нормализуются до сохранения, IPv6 явно отклоняется.
String _normalizeIpRule(String raw) {
  final value = raw.trim();
  if (value.contains('-')) {
    final parts = value.split('-');
    if (parts.length == 2) {
      final first = _ipv4(parts[0].trim());
      final last = _ipv4(parts[1].trim());
      if (first <= last) return '${_ipv4Text(first)}-${_ipv4Text(last)}';
    }
    throw const FormatException(
      'IP и подсети: диапазон должен идти от меньшего IPv4 к большему.',
    );
  }
  final parts = value.split('/');
  if (parts.length > 2) {
    throw const FormatException('IP и подсети: неверная маска CIDR.');
  }
  final address = _ipv4(parts[0]);
  if (parts.length == 1) return _ipv4Text(address);
  final prefix = int.tryParse(parts[1]);
  if (prefix == null ||
      prefix < 0 ||
      prefix > 32 ||
      !RegExp(r'^\d{1,2}$').hasMatch(parts[1])) {
    throw const FormatException(
      'IP и подсети: длина маски IPv4 должна быть от 0 до 32.',
    );
  }
  final mask = prefix == 0 ? 0 : (0xffffffff << (32 - prefix)) & 0xffffffff;
  return '${_ipv4Text(address & mask)}/$prefix';
}

int _ipv4(String text) {
  final parts = text.split('.');
  if (parts.length != 4 ||
      parts.any(
        (part) => !RegExp(r'^\d{1,3}$').hasMatch(part) || int.parse(part) > 255,
      )) {
    throw const FormatException(
      'IP и подсети: используйте IPv4, например 10.0.0.5, 10.0.0.0/8 или 10.0.0.1-10.0.0.20.',
    );
  }
  return parts.fold(0, (result, part) => (result << 8) | int.parse(part));
}

String _ipv4Text(int address) =>
    [24, 16, 8, 0].map((shift) => (address >> shift) & 255).join('.');
