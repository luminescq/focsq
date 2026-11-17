// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math';

import 'package:flutter/services.dart';

import '../l10n/app_locale.dart';
import '../models/server_profile.dart';
import 'csqtt_link_crypto.dart';
import 'db.dart';

enum ImportProblem { emptyClipboard, readError, invalidFormat, saveFailed }

class ImportResult {
  const ImportResult({
    this.imported = 0,
    this.duplicates = 0,
    this.invalid = 0,
    this.problem,
    this.hashesApplied = false,
  });

  final int imported;

  final int duplicates;

  final int invalid;

  final ImportProblem? problem;

  final bool hashesApplied;

  bool get hasError => problem != null;
}

class LinkParser {
  LinkParser({Random? random}) : _random = random ?? Random();

  static const String scheme = 'csqtt://';
  static const String _connectPrefix = '${scheme}connect?';
  static const String _connectSlash = '${scheme}connect/';
  static const Set<String> _knownKeys = {
    'host',
    'peer',
    'password',
    'hashes',
    'power',
    'workers',
  };

  final Random _random;

  ServerProfile? parse(String link) {
    final trimmed = _normalizeLink(link);
    if (!trimmed.startsWith(scheme)) return null;

    try {
      return trimmed.startsWith(_connectPrefix)
          ? _parseV2(trimmed)
          : _parseLegacy(trimmed);
    } on FormatException {
      return null;
    } on ArgumentError {
      return null;
    }
  }

  ServerProfile? _parseV2(String link) {
    final query = _v2Query(link);
    if (query == null) return null;

    final rawPower = int.tryParse(query['power'] ?? query['workers'] ?? '');
    return ServerProfile(
      name: _generateName(),
      ip: query['host']!,
      port: int.parse(query['peer']!),
      password: query['password']!,
      power: ImportService.normalizePower(rawPower),
    );
  }

  String? parseHashes(String link) {
    final trimmed = _normalizeLink(link);
    if (!trimmed.startsWith(_connectPrefix)) return null;
    try {
      final query = _v2Query(trimmed);
      if (query == null) return null;
      final hashesRaw = query['hashes'] ?? '';
      if (hashesRaw.isEmpty) return null;
      // Битая %-последовательность в хешах — это ошибка формата, а не пустые хеши.
      if (hashesRaw.contains('%') && !_isFullyDecodable(hashesRaw)) {
        return null;
      }
      final hashes = hashesRaw
          .split(',')
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .join(',');
      return hashes.isEmpty ? null : hashes;
    } on FormatException {
      return null;
    } on ArgumentError {
      return null;
    }
  }

  /// Браузер может передать ссылку с `&amp;`/`&#38;` вместо `&`, а также
  /// с лишним слэшем после `connect` (`csqtt://connect/?...`).
  static String _normalizeLink(String link) {
    var value = _normalizeConnectSlash(_unescapeHtmlEntities(link.trim()));
    if (value.endsWith('/') && !value.endsWith('//')) {
      value = value.substring(0, value.length - 1);
    }
    return value;
  }

  static String _normalizeConnectSlash(String value) {
    if (value.startsWith(_connectSlash)) {
      return '${scheme}connect${value.substring(_connectSlash.length)}';
    }
    return value;
  }

  static String _unescapeHtmlEntities(String value) =>
      value.replaceAll('&amp;', '&').replaceAll('&#38;', '&');

  /// Возвращает параметры корректной v2-ссылки.
  ///
  /// Сначала ссылка парсится как есть: если разделители на месте, значение
  /// вроде `password=abcpower=123` не должно приниматься за параметр power.
  /// Нормализация склеенных ключей применяется только как запасной вариант.
  Map<String, String>? _v2Query(String link) {
    final direct = _queryWithRequiredParams(_queryPairs(link));
    if (direct != null) return direct;
    final normalized = _separateParams(link);
    if (normalized == link) return null;
    return _queryWithRequiredParams(_queryPairs(normalized));
  }

  Map<String, String>? _queryWithRequiredParams(Map<String, String> query) {
    if (query['v'] != '2') return null;
    if (query['host']?.isEmpty ?? true) return null;
    if (query['password']?.isEmpty ?? true) return null;
    if (!ImportService.isValidPort(int.tryParse(query['peer'] ?? ''))) {
      return null;
    }
    return query;
  }

  /// Достаёт параметры из query вручную, как это делает Telegram: без
  /// строгого Uri-парсинга. Разделители — & и ;, фрагмент (#) отбрасывается,
  /// ключи и значения декодируются.
  Map<String, String> _queryPairs(String link) {
    final prefixLength = _connectPrefix.length;
    if (link.length <= prefixLength) return const {};
    var body = link.substring(prefixLength);
    if (body.startsWith('?')) body = body.substring(1);
    final hash = body.indexOf('#');
    if (hash >= 0) body = body.substring(0, hash);

    // Браузер мог закодировать сами разделители (%26 вместо &).
    if (!body.contains('&') && !body.contains(';') && body.contains('%26')) {
      try {
        body = Uri.decodeComponent(body);
      } catch (_) {}
    }

    final map = <String, String>{};
    for (final raw in body.split(RegExp('[&;]'))) {
      final pair = raw.trim();
      if (pair.isEmpty) continue;
      final eq = pair.indexOf('=');
      if (eq <= 0) continue;
      final key = _tryDecode(pair.substring(0, eq));
      final value = _tryDecode(pair.substring(eq + 1));
      if (key.isEmpty) continue;
      map[key] = value;
    }
    return map;
  }

  static String _tryDecode(String value) {
    try {
      return Uri.decodeComponent(value);
    } catch (_) {
      return value;
    }
  }

  static bool _isFullyDecodable(String value) {
    try {
      Uri.decodeComponent(value);
      return true;
    } catch (_) {
      return false;
    }
  }

  String _separateParams(String link) {
    var result = link;
    for (final key in _knownKeys) {
      result = result.replaceFirst('$key=', '&$key=');
    }
    return result;
  }

  ServerProfile? _parseLegacy(String link) {
    final withoutScheme = link.substring(scheme.length);

    final at = withoutScheme.lastIndexOf('@');
    if (at <= 0 || at == withoutScheme.length - 1) return null;

    final password = Uri.decodeComponent(withoutScheme.substring(0, at));
    final address = withoutScheme.substring(at + 1);

    String host;
    int? port;

    if (address.startsWith('[')) {
      final closeBracket = address.indexOf(']');
      if (closeBracket == -1) return null;
      host = address.substring(1, closeBracket);
      if (host.isEmpty) return null;
      final rest = address.substring(closeBracket + 1);
      if (!rest.startsWith(':')) return null;
      port = int.tryParse(rest.substring(1));
    } else {
      final parts = address.split(':');
      if (parts.length != 2 || parts[0].isEmpty) return null;
      host = parts[0];
      port = int.tryParse(parts[1]);
    }

    if (!ImportService.isValidPort(port)) return null;

    return ServerProfile(
      name: _generateName(),
      ip: host,
      port: port,
      password: password,
    );
  }

  String _generateName() => S.serverProfileName(_random.nextInt(9000) + 1000);
}

class ImportService {
  ImportService._();

  static final LinkParser _parser = LinkParser();

  static final RegExp _linkPattern = RegExp(
    r'csqtt://\S+',
    caseSensitive: false,
  );

  static Future<ImportResult> importFromClipboard() async {
    final String? text;
    try {
      final data = await Clipboard.getData(Clipboard.kTextPlain);
      text = data?.text?.trim();
    } catch (_) {
      return const ImportResult(problem: ImportProblem.readError);
    }

    if (text == null || text.isEmpty) {
      return const ImportResult(problem: ImportProblem.emptyClipboard);
    }
    return importText(text);
  }

  static List<ServerProfile> extractProfiles(String text) => [
    for (final c in _extractCandidates(text))
      if (_parser.parse(c) case final ServerProfile p) p,
  ];

  static ServerProfile? parseLink(String link) => _parser.parse(link);

  /// Расшифровывает непрозрачные `csqtt://<base64>` ссылки и объединяет их
  /// с уже открытыми (connect?... / legacy) из того же текста.
  static Future<List<String>> _normalizedCandidates(
    List<String> candidates,
  ) async {
    final normalized = <String>[];
    for (final candidate in candidates) {
      if (CsqttLinkCrypto.isOpaqueLink(candidate)) {
        final decrypted = await CsqttLinkCrypto.decrypt(candidate);
        if (decrypted != null && _parser.parse(decrypted) != null) {
          normalized.add(decrypted);
        }
        continue;
      }
      normalized.add(candidate);
    }
    return normalized;
  }

  static bool isValidPort(int? port) =>
      port != null && port > 0 && port <= 65535;

  static const int minPower = 10;
  static const int maxPower = 100;

  /// Приводит мощность из недоверенной ссылки/JSON к допустимому диапазону.
  /// Невалидные значения (0, отрицательные) отбрасываются.
  static int? normalizePower(int? value) {
    if (value == null || value <= 0) return null;
    return value.clamp(minPower, maxPower);
  }

  static List<String> _extractCandidates(String text) =>
      _linkPattern.allMatches(text).map((m) => m.group(0)!).toList();

  static Future<ImportResult> importText(String text) async {
    final candidates = await _normalizedCandidates(_extractCandidates(text));
    final profiles = <ServerProfile>[
      for (final c in candidates)
        if (_parser.parse(c) case final ServerProfile p) p,
    ];
    final invalid = candidates.length - profiles.length;

    if (profiles.isEmpty) {
      return const ImportResult(problem: ImportProblem.invalidFormat);
    }

    List<ServerProfile> existing;
    try {
      existing = await DbService.getProfiles();
    } catch (_) {
      return const ImportResult(problem: ImportProblem.saveFailed);
    }

    final fresh = <ServerProfile>[];
    var duplicates = 0;
    for (final profile in profiles) {
      final duplicate = existing.any(
        (e) => e.ip == profile.ip && e.port == profile.port,
      );
      if (duplicate) {
        duplicates++;
      } else {
        fresh.add(profile);
      }
    }

    String? hashes;
    for (final candidate in candidates) {
      hashes = _parser.parseHashes(candidate) ?? hashes;
    }

    if (fresh.isEmpty && hashes == null) {
      return ImportResult(duplicates: duplicates, invalid: invalid);
    }

    final appliedHashes = hashes;
    try {
      await DbService.saveImportedProfiles(
        fresh,
        updateSettings: appliedHashes == null
            ? null
            : (settings) => settings
                ..customHashes = appliedHashes
                ..hashes = 'Ручной'
                ..workMode = 'Звонки',
      );
    } catch (_) {
      return ImportResult(
        problem: ImportProblem.saveFailed,
        duplicates: duplicates,
        invalid: invalid,
      );
    }
    return ImportResult(
      imported: fresh.length,
      duplicates: duplicates,
      invalid: invalid,
      hashesApplied: appliedHashes != null,
    );
  }
}
