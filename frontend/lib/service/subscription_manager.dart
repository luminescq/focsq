// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../l10n/app_locale.dart';
import '../models/profile_group.dart';
import '../models/server_profile.dart';
import 'db.dart';
import 'logs.dart';
import 'subscription.dart';

/// Метаданные подписки (настройки автообновления, время последнего обновления и заголовок)
class SubscriptionMeta {
  String url;
  String title;
  int autoUpdateMinutes; // 0 = отключено, 2, 30, 60, 120, 360, 720, 1440
  DateTime? lastUpdated;
  DateTime? lastAttempt;

  SubscriptionMeta({
    required this.url,
    this.title = '',
    this.autoUpdateMinutes = 0,
    this.lastUpdated,
    this.lastAttempt,
  });

  Map<String, dynamic> toJson() => {
    'url': url,
    'title': title,
    'autoUpdateMinutes': autoUpdateMinutes,
    'lastUpdated': lastUpdated?.toIso8601String(),
    'lastAttempt': lastAttempt?.toIso8601String(),
  };

  factory SubscriptionMeta.fromJson(Map<String, dynamic> json) {
    DateTime? parseDate(Object? value) {
      final raw = value as String?;
      return raw == null ? null : DateTime.tryParse(raw);
    }

    return SubscriptionMeta(
      url: (json['url'] as String?) ?? '',
      title: (json['title'] as String?) ?? '',
      autoUpdateMinutes: (json['autoUpdateMinutes'] as num?)?.toInt() ?? 0,
      lastUpdated: parseDate(json['lastUpdated']),
      lastAttempt: parseDate(json['lastAttempt']),
    );
  }
}

/// Сервис управления подписками, их настройками и фоновым автообновлением.
class SubscriptionManager {
  SubscriptionManager._();
  static final SubscriptionManager instance = SubscriptionManager._();

  static const String _fileName = 'subscriptions_meta.json';
  final Map<String, SubscriptionMeta> _cache = {};
  final Map<String, Future<SubscriptionResult>> _inFlight = {};
  final Map<String, int> _epochs = {};
  Future<void>? _initFuture;
  Timer? _autoUpdateTimer;

  /// Инициализация сервиса и загрузка сохранённых метаданных
  Future<void> init() => _initFuture ??= _init();

  Future<void> _init() async {
    try {
      final file = await _getMetaFile();
      if (await file.exists()) {
        final content = await file.readAsString();
        final dynamic decoded = jsonDecode(content);
        if (decoded is Map<String, dynamic>) {
          for (final entry in decoded.entries) {
            if (entry.value is Map<String, dynamic>) {
              _cache[entry.key] = SubscriptionMeta.fromJson(
                entry.value as Map<String, dynamic>,
              );
            }
          }
        }
      }
    } catch (e) {
      debugPrint('Failed to load subscription metadata: $e');
    }

    // Запускаем фоновый таймер проверки автообновления.
    _autoUpdateTimer?.cancel();
    // Проверяем чаще минимального интервала (2 минуты), иначе автообновление
    // срабатывало бы с задержкой до одного периода таймера.
    _autoUpdateTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _checkAutoUpdates(),
    );
  }

  int _epochOf(String url) => _epochs[url] ?? 0;

  /// Инвалидирует идущие обновления: их результат уже не относится к текущей
  /// подписке (удалена или переехала на другой адрес).
  void _invalidate(String url) {
    _epochs[url] = _epochOf(url) + 1;
  }

  /// Возвращает метаданные подписки или создаёт запись по умолчанию
  SubscriptionMeta getMeta(String url, {String? defaultTitle}) {
    final key = url.trim();
    if (_cache.containsKey(key)) {
      return _cache[key]!;
    }
    final meta = SubscriptionMeta(
      url: key,
      title: defaultTitle ?? '',
      autoUpdateMinutes: 0,
    );
    _cache[key] = meta;
    return meta;
  }

  /// Сохранение метаданных подписки
  Future<void> saveMeta(SubscriptionMeta meta) async {
    final key = meta.url.trim();
    if (key.isEmpty) return;
    _cache[key] = meta;
    await _persist();
  }

  /// Удаление метаданных подписки
  Future<void> deleteMeta(String url) async {
    final key = url.trim();
    _cache.remove(key);
    await _persist();
  }

  /// Ручное или фоновое обновление подписки из сети.
  ///
  /// Повторный вызов для того же URL возвращает уже идущую операцию, чтобы
  /// ручное обновление и таймер не дублировали импорт.
  Future<SubscriptionResult> updateSubscription(String url) {
    final key = url.trim();
    final running = _inFlight[key];
    if (running != null) return running;
    final meta = getMeta(key);
    final epoch = _epochOf(key);
    late final Future<SubscriptionResult> future;
    future = _performUpdate(key, meta, epoch).whenComplete(() {
      if (identical(_inFlight[key], future)) _inFlight.remove(key);
    });
    _inFlight[key] = future;
    return future;
  }

  Future<SubscriptionResult> _performUpdate(
    String url,
    SubscriptionMeta meta,
    int epoch,
  ) async {
    bool isStale() => _epochOf(url) != epoch;

    meta.lastAttempt = DateTime.now();
    final result = await SubscriptionService.importSubscription(
      url,
      customTitle: meta.title.isNotEmpty ? meta.title : null,
      isCancelled: isStale,
    );
    // Подписку удалили или переименовали адрес, пока шла загрузка: не
    // воскрешаем её метаданные и не пишем в журнал ложный успех.
    if (isStale()) return result;

    final title = meta.title.isNotEmpty ? meta.title : result.title;
    final logTitle = _logTitle(title, url);
    if (result.success) {
      meta.lastUpdated = DateTime.now();
      if (meta.title.isEmpty && result.title.isNotEmpty) {
        meta.title = result.title;
      }
      await saveMeta(meta);
      if (result.importedCount > 0 || result.updatedCount > 0) {
        LogService().add(
          S.subscriptionUpdatedLog(
            logTitle,
            result.importedCount,
            result.updatedCount,
          ),
        );
      }
      if (result.hashesApplied) {
        LogService().add(S.subscriptionHashesLog(logTitle));
      }
    } else {
      // Фиксируем попытку, чтобы недоступный сервер не опрашивался каждые 30 с.
      await saveMeta(meta);
      LogService().add(S.subscriptionUpdateFailedLog(logTitle));
    }
    return result;
  }

  /// Имя подписки для журнала. URL не пишем: в нём может быть токен.
  String _logTitle(String title, String url) {
    if (title.isNotEmpty) return title;
    final host = Uri.tryParse(url)?.host ?? '';
    return host.isEmpty ? S.subscriptionDetailsTitle : host;
  }

  /// Переносит подписку и её профили на новый адрес.
  ///
  /// Метаданные (интервал автообновления, заголовок, время обновления)
  /// сохраняются под новым ключом, старый ключ удаляется.
  Future<void> changeUrl({
    required String oldUrl,
    required String newUrl,
    required List<ServerProfile> profiles,
    SubscriptionMeta? meta,
  }) async {
    final from = oldUrl.trim();
    final to = newUrl.trim();
    if (to.isEmpty || from == to) return;

    _invalidate(from);

    final base = meta ?? getMeta(from);
    final changed = <ServerProfile>[];
    for (final profile in profiles) {
      if (profile.subscriptionUrl == to) continue;
      profile.subscriptionUrl = to;
      changed.add(profile);
    }
    if (changed.isNotEmpty) {
      await DbService.saveProfiles(changed);
    }

    _cache.remove(from);
    _cache[to] = SubscriptionMeta(
      url: to,
      title: base.title,
      autoUpdateMinutes: base.autoUpdateMinutes,
      lastUpdated: base.lastUpdated,
      lastAttempt: base.lastAttempt,
    );
    await _persist();
    LogService().add(S.subscriptionUrlChangedLog);
  }

  /// Переименование подписки и всех её профилей в базе
  Future<void> renameSubscription({
    required String url,
    required String oldTitle,
    required String newTitle,
    required List<ServerProfile> profiles,
  }) async {
    final trimmedNew = newTitle.trim();
    if (trimmedNew.isEmpty || trimmedNew == oldTitle) return;

    final updatedProfiles = <ServerProfile>[];
    for (final p in profiles) {
      final updatedName = renamedProfileName(p, oldTitle, trimmedNew);
      if (updatedName != p.name) {
        p.name = updatedName;
        updatedProfiles.add(p);
      }
    }

    if (updatedProfiles.isNotEmpty) {
      await DbService.saveProfiles(updatedProfiles);
    }

    final meta = getMeta(url, defaultTitle: oldTitle);
    meta.title = trimmedNew;
    await saveMeta(meta);
  }

  /// Удаление всех серверов подписки и её метаданных
  Future<void> deleteSubscription({
    required String url,
    required List<ServerProfile> profiles,
  }) async {
    _invalidate(url.trim());

    // Сначала метаданные: если процесс упадёт между шагами, оставшиеся
    // профили не будут автоматически воскрешены таймером автообновления.
    await deleteMeta(url);
    final ids = profiles.map((p) => p.id).toList();
    if (ids.isNotEmpty) {
      await DbService.deleteProfiles(ids);
    }
  }

  /// Фоновая проверка необходимости обновления по расписанию
  Future<void> _checkAutoUpdates() async {
    final now = DateTime.now();
    // Снимок: во время await пользователь может удалить или переименовать
    // подписку, и обход живой коллекции упал бы с ConcurrentModificationError.
    final metas = [
      for (final meta in _cache.values.toList())
        (meta: meta, epoch: _epochOf(meta.url)),
    ];
    for (final entry in metas) {
      final meta = entry.meta;
      if (meta.autoUpdateMinutes <= 0 || meta.url.isEmpty) continue;
      // Пока шли предыдущие обновления, подписку могли удалить или перенести
      // на другой адрес — тогда её снимок уже неактуален.
      if (_epochOf(meta.url) != entry.epoch ||
          !identical(_cache[meta.url], meta)) {
        continue;
      }
      final last = meta.lastAttempt ?? meta.lastUpdated;
      final shouldUpdate =
          last == null ||
          now.difference(last).inMinutes >= meta.autoUpdateMinutes;

      if (shouldUpdate) {
        final title = _logTitle(meta.title, meta.url);
        try {
          await updateSubscription(meta.url);
        } catch (e) {
          debugPrint('Background update of subscription "$title" failed: $e');
        }
      }
    }
  }

  Future<File> _getMetaFile() async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}/$_fileName');
  }

  Future<void> _persist() async {
    try {
      final file = await _getMetaFile();
      final data = <String, dynamic>{};
      for (final entry in _cache.entries) {
        data[entry.key] = entry.value.toJson();
      }
      // Пишем во временный файл и подменяем основной, чтобы падение по
      // середине записи не оставило обрезанный JSON.
      final tmp = File('${file.path}.tmp');
      await tmp.writeAsString(jsonEncode(data), flush: true);
      try {
        await tmp.rename(file.path);
      } on FileSystemException {
        if (await file.exists()) await file.delete();
        await tmp.rename(file.path);
      }
    } catch (e) {
      debugPrint('Failed to write subscription metadata: $e');
    }
  }
}
