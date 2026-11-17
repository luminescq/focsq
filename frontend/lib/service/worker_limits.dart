// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import '../models/app_settings.dart';

/// Ограничения на число потоков (воркеров), общие для UI и ядра.
///
/// Ядро 2.5.0 нормализует запрошенное число так же: не больше 100 и кратно 10
/// (группа из 10 воркеров). Одна группа обслуживается одним credential; при
/// нехватке хешей ядро берёт дополнительные credential-когорты на тот же хеш.
class WorkerLimits {
  WorkerLimits._();

  static const int perGroup = 10;
  static const int maxHashes = 6;

  /// Серверный лимит потоков (TUNWORKERS, 3..=100).
  static const int baseMax = 100;
  static const int extraMax = 100;

  static const int min = perGroup;

  /// Приводит число к тому, которое реально примет сервер.
  static int normalizeCount(int? requested) {
    final value = requested ?? min;
    if (value <= 5) return 5;
    return value.clamp(min, extraMax) ~/ perGroup * perGroup;
  }

  /// Максимум потоков не зависит от числа хешей: до 6 хешей ядро использует
  /// несколько credential-когорт на один хеш.
  static int maxForHashes(int hashCount) => maxForHashesLimit;

  static const int maxForHashesLimit = baseMax;

  /// Максимум потоков для текущих настроек режима/хешей.
  static int maxPower(AppSettings settings) {
    var max = settings.extraThreads ? extraMax : baseMax;
    if (settings.hashes == 'Ручной') {
      final hashCount = settings.customHashes
          .split(',')
          .where((value) => value.trim().isNotEmpty)
          .length;
      if (hashCount > 0) {
        final byHashes = maxForHashes(hashCount);
        if (byHashes < max) max = byHashes;
      }
    }
    return normalizeCount(max);
  }

  /// Максимум для профиля с учётом лимита, отданного подпиской/сервером.
  static int maxPowerForProfile(AppSettings settings, {int? threadsLimit}) {
    var max = maxPower(settings);
    if (threadsLimit != null && threadsLimit < max) max = threadsLimit;
    return normalizeCount(max);
  }
}
