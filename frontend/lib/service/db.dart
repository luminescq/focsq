// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import '../l10n/app_locale.dart';

import 'package:isar/isar.dart';
import 'package:path_provider/path_provider.dart';

import '../models/app_settings.dart';
import '../models/server_profile.dart';

class DbService {
  static Isar? _isar;

  static AppSettings? _settingsCache;

  static Future<void> init() async {
    if (_isar != null) return;
    final dir = await getApplicationSupportDirectory();
    _isar = await Isar.open(
      [ServerProfileSchema, AppSettingsSchema],
      directory: dir.path,
      relaxedDurability: false,
    );
  }

  static Isar get _db => _isar ?? (throw StateError(S.dbNotInitialized));
  static Stream<List<ServerProfile>> watchProfiles() {
    return _db.serverProfiles
        .where()
        .watch(fireImmediately: true)
        .map((profiles) => profiles..sort(_compareByName));
  }

  static Future<List<ServerProfile>> getProfiles() async {
    final profiles = await _db.serverProfiles.where().findAll();
    return profiles..sort(_compareByName);
  }

  static Future<void> saveProfile(ServerProfile profile) async {
    await _db.writeTxn(() => _db.serverProfiles.put(profile));
  }

  static Future<void> saveProfiles(List<ServerProfile> profiles) async {
    if (profiles.isEmpty) return;
    await _db.writeTxn(() => _db.serverProfiles.putAll(profiles));
  }

  static Future<void> deleteProfiles(List<int> ids) async {
    if (ids.isEmpty) return;
    await _db.writeTxn(() async {
      await _db.serverProfiles.deleteAll(ids);
      final settings = await _db.appSettings.get(AppSettings.singletonId);
      if (settings != null && ids.contains(settings.activeProfileId)) {
        settings.activeProfileId = null;
        await _db.appSettings.put(settings);
        _settingsCache = settings;
      }
    });
  }

  static Future<void> saveImportedProfiles(
    List<ServerProfile> profiles, {
    List<int> deleteIds = const [],
    void Function(AppSettings settings)? updateSettings,
  }) async {
    await _db.writeTxn(() async {
      if (deleteIds.isNotEmpty) {
        await _db.serverProfiles.deleteAll(deleteIds);
      }
      if (profiles.isNotEmpty) {
        await _db.serverProfiles.putAll(profiles);
      }
      if (updateSettings == null && deleteIds.isEmpty) return;
      final settings =
          await _db.appSettings.get(AppSettings.singletonId) ?? AppSettings();
      var changed = updateSettings != null;
      if (deleteIds.contains(settings.activeProfileId)) {
        settings.activeProfileId = null;
        changed = true;
      }
      updateSettings?.call(settings);
      if (changed) {
        await _db.appSettings.put(settings);
        _settingsCache = settings;
      }
    });
  }

  static Future<void> deleteProfile(int id) => deleteProfiles([id]);

  static Future<ServerProfile?> getProfile(int? id) async {
    if (id == null) return null;
    return _db.serverProfiles.get(id);
  }

  static Stream<AppSettings?> watchSettings() {
    return _db.appSettings.watchObject(
      AppSettings.singletonId,
      fireImmediately: true,
    );
  }

  static Future<ServerProfile?> getActiveProfile([
    AppSettings? settings,
  ]) async {
    settings ??= await getSettings();
    final saved = await getProfile(settings.activeProfileId);
    if (saved != null) return saved;
    final first = await getProfiles();
    return first.isEmpty ? null : first.first;
  }

  static int _compareByName(ServerProfile a, ServerProfile b) =>
      (a.name ?? '').toLowerCase().compareTo((b.name ?? '').toLowerCase());

  static Future<AppSettings> getSettings() async {
    final stored = await _db.appSettings.get(AppSettings.singletonId);
    if (stored != null) {
      _settingsCache = stored;
      return stored;
    }

    final created = AppSettings();
    await _db.writeTxn(() => _db.appSettings.put(created));
    _settingsCache = created;
    return created;
  }

  static Future<AppSettings> updateSettings(
    FutureOr<AppSettings> Function(AppSettings settings) update,
  ) {
    return _db.writeTxn(() async {
      final settings = await update(await getSettings());
      await _db.appSettings.put(settings);
      _settingsCache = settings;
      return settings;
    });
  }

  static bool isUpdateSkipped(String version) =>
      _settingsCache?.skipUpdateVersion == version;

  static Future<int?> getActiveProfileId() async =>
      (await getSettings()).activeProfileId;

  static Future<void> setActiveProfileId(int? id) =>
      updateSettings((s) => s..activeProfileId = id);
}
