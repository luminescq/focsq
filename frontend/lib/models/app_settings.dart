// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math';

import 'package:isar/isar.dart';

part 'app_settings.g.dart';

@collection
class AppSettings {
  static const int singletonId = 0;

  AppSettings() : deviceId = generateDeviceId();

  Id id = singletonId;

  late String deviceId;

  int? activeProfileId;

  String workMode = 'Звонки';

  String masking = 'Простая';

  String fingerprint = 'Firefox';

  String hashes = 'Авто ВК';

  String customHashes = '';

  bool extraThreads = false;

  bool tray = false;

  bool developerMode = false;

  bool banDisclaimerAccepted = false;

  // Префикс z держит поле последним в алфавитном порядке: Isar присваивает
  // property id по порядку имён, и вставка в середину сдвигает id остальных
  // свойств, ломая миграцию уже существующих баз (isar#1460).
  bool zAllowInsecureHttp = false;

  String? skipUpdateVersion;

  bool zDomainRouting = false;

  bool zExcludeRuZones = false;

  bool zTemplateDomains = false;

  bool zDomainWhitelist = false;

  String zDirectDomains = '';

  bool zIpRouting = false;

  bool zIpWhitelist = false;

  String zDirectIps = '';
}

String generateDeviceId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0F) | 0x40;
  bytes[8] = (bytes[8] & 0x3F) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-'
      '${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-'
      '${hex.substring(20)}';
}
