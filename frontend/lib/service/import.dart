// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math';

import 'package:flutter/services.dart';

import '../models/server_profile.dart';
import 'db.dart';

enum ImportProblem {

  emptyClipboard,

  readError,

  invalidFormat,

  saveFailed,
}

class ImportResult {
  const ImportResult({
    this.imported = 0,
    this.duplicates = 0,
    this.invalid = 0,
    this.problem,
  });

  final int imported;

  final int duplicates;

  final int invalid;

  final ImportProblem? problem;

  bool get hasError => problem != null;
}

class LinkParser {
  LinkParser({Random? random}) : _random = random ?? Random();

  static const String scheme = 'csqtt://';
  static const String _connectPrefix = '${scheme}connect?';
  static const Set<String> _knownKeys = {'host', 'peer', 'password', 'hashes'};

  final Random _random;

  ServerProfile? parse(String link) {
    final trimmed = link.trim();
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
    final fixedLink = _separateParams(link);
    final uri = Uri.tryParse(fixedLink);
    if (uri == null) return null;

    final query = uri.queryParameters;
    if (query['v'] != '2') return null;

    final host = query['host'];
    final peer = query['peer'];
    final password = query['password'];
    if (host == null || host.isEmpty) return null;
    if (password == null || password.isEmpty) return null;

    final port = int.tryParse(peer ?? '');
    if (!ImportService.isValidPort(port)) return null;

    return ServerProfile(
      name: _generateName(),
      ip: host,
      port: port,
      password: password,
    );
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

  String _generateName() => 'Сервер #${_random.nextInt(9000) + 1000}';
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

  static List<ServerProfile> extractProfiles(String text) =>
      [for (final c in _extractCandidates(text)) if (_parser.parse(c) case final ServerProfile p) p];

  static ServerProfile? parseLink(String link) => _parser.parse(link);

  static bool isValidPort(int? port) => port != null && port > 0 && port <= 65535;

  static List<String> _extractCandidates(String text) =>
      _linkPattern.allMatches(text).map((m) => m.group(0)!).toList();

  static Future<ImportResult> importText(String text) async {
    final candidates = _extractCandidates(text);
    final profiles =
        <ServerProfile>[for (final c in candidates) if (_parser.parse(c) case final ServerProfile p) p];
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

    if (fresh.isEmpty) {
      return ImportResult(duplicates: duplicates, invalid: invalid);
    }

    try {
      for (final profile in fresh) {
        await DbService.saveProfile(profile);
      }
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
    );
  }
}