// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:package_info_plus/package_info_plus.dart';

import 'browser.dart';
import 'db.dart';
import 'logs.dart';

sealed class UpdateCheckResult {
  const UpdateCheckResult();
}

class UpdateAvailable extends UpdateCheckResult {
  const UpdateAvailable({
    required this.version,
    required this.currentVersion,
    required this.releaseUrl,
    required this.notes,
  });

  final String version;
  final String currentVersion;
  final String releaseUrl;
  final String notes;
}

class UpToDate extends UpdateCheckResult {
  const UpToDate(this.currentVersion);

  final String currentVersion;
}

class NoReleases extends UpdateCheckResult {
  const NoReleases(this.currentVersion);

  final String currentVersion;
}

class UpdateCheckError extends UpdateCheckResult {
  const UpdateCheckError(this.message);

  final String message;
}

class _HttpError implements Exception {
  const _HttpError(this.message);

  final String message;

  @override
  String toString() => message;
}

class UpdateService {
  UpdateService._();

  static const String _releasesApi =
      'https://api.github.com/repos/luminescq/focsq/releases/latest';
  static const String _releasesPage =
      'https://github.com/luminescq/focsq/releases';

  static const Duration _autoInterval = Duration(hours: 6);

  static DateTime? _lastAutoCheck;
  static String? _cachedVersion;
  static String? _installedVersion;

  static String get releasesPage => _releasesPage;

  static String? get cachedVersion => _cachedVersion;

  static String? get installedVersion => _installedVersion;

  static bool get hasAvailableUpdate =>
      _cachedVersion != null &&
      _installedVersion != null &&
      _cachedVersion != _installedVersion;

  static bool get isUpdateDismissed =>
      _cachedVersion != null && DbService.isUpdateSkipped(_cachedVersion!);

  static bool updateModalShown = false;

  static final List<void Function()> _listeners = [];

  static void Function() addListener(void Function() listener) {
    _listeners.add(listener);
    return () => _listeners.remove(listener);
  }

  static void _notifyListeners() {
    for (final listener in List.of(_listeners)) {
      listener();
    }
  }

  static void _log(String line) => LogService().add(line);

  static Future<UpdateCheckResult> autoCheck() async {
    final now = DateTime.now();
    if (_lastAutoCheck != null &&
        now.difference(_lastAutoCheck!) < _autoInterval) {
      return const UpToDate('кэш');
    }
    _lastAutoCheck = now;

    final result = await _check();
    if (result is UpdateAvailable) {
      try {
        final settings = await DbService.getSettings();
        if (settings.skipUpdateVersion == result.version) {
          return result;
        }
      } catch (_) {}
    }
    return result;
  }

  static Future<UpdateCheckResult> manualCheck() => _check();

  static Future<UpdateCheckResult> _check() async {
    final info = await PackageInfo.fromPlatform();
    final current = _normalize(info.version);
    _installedVersion = current;

    try {
      final release = await _fetchJson();
      if (release.isEmpty) {
        return NoReleases(current);
      }
      final tag = _normalize(release['tag_name']?.toString() ?? '');
      final htmlUrl = release['html_url']?.toString();
      final body = release['body']?.toString() ?? '';
      final url = (htmlUrl != null && htmlUrl.isNotEmpty)
          ? htmlUrl
          : _releasesPage;

      if (tag.isEmpty) {
        return const UpdateCheckError('релиз без тега версии');
      }
      _cachedVersion = tag;
      _notifyListeners();

      if (_compareVersions(current, tag) < 0) {
        _log('[ОБНОВЛЕНИЕ] Доступна версия $tag (установлена $current)');
        return UpdateAvailable(
          version: tag,
          currentVersion: current,
          releaseUrl: url,
          notes: _releaseNotes(body),
        );
      }
      return UpToDate(current);
    } on FormatException catch (error) {
      return UpdateCheckError('сервер вернул некорректный JSON: '
          '${error.message}');
    } on _HttpError catch (error) {
      return UpdateCheckError('сервер ответил: ${error.message}');
    } on HttpException catch (error) {
      return UpdateCheckError('нет сети: ${error.message}');
    } on TimeoutException {
      return const UpdateCheckError('сервер не ответил за 15 секунд');
    } catch (error) {
      return UpdateCheckError('проверка не удалась: $error');
    }
  }

  static Future<Map<String, dynamic>> _fetchJson() async {
    final uri = Uri.parse(UrlService.validated(_releasesApi));
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client.getUrl(uri).timeout(
            const Duration(seconds: 15),
          );
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(HttpHeaders.userAgentHeader, 'focsq-update-check');
      final response = await request.close().timeout(
        const Duration(seconds: 15),
      );
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode == 404) {
        return const {};
      }
      if (response.statusCode != 200) {
        throw _HttpError('HTTP ${response.statusCode}');
      }
      final decoded = jsonDecode(body);
      if (decoded is! Map<String, dynamic>) {
        throw const FormatException('ответ не является JSON-объектом');
      }
      return decoded;
    } finally {
      client.close();
    }
  }

  static String _normalize(String version) =>
      version.trim().replaceFirst(RegExp('^v'), '');

  static int _compareVersions(String a, String b) {
    final pa = _normalize(a).split('.');
    final pb = _normalize(b).split('.');
    final count = pa.length > pb.length ? pa.length : pb.length;
    for (var i = 0; i < count; i++) {
      final sa = pa.length > i ? pa[i] : '';
      final sb = pb.length > i ? pb[i] : '';
      final na = int.tryParse(sa);
      final nb = int.tryParse(sb);
      if (na != null && nb != null) {
        if (na != nb) return na.compareTo(nb);
        continue;
      }
      final cmp = sa.compareTo(sb);
      if (cmp != 0) return cmp;
    }
    return 0;
  }

  static String _releaseNotes(String body) => body
      .split('\n')
      .map((line) => line.trimRight())
      .join('\n')
      .trim();
}