// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../models/server_profile.dart';
import 'autostart.dart';
import 'connect.dart';
import 'db.dart';
import 'logs.dart';

class ReportService {
  ReportService._();

  static const int _logTailLines = 30;

  static Future<String> generateReport() async {
    final packageInfo = await PackageInfo.fromPlatform();
    final autostartEnabled = await AutostartService.isEnabled();
    final connect = ConnectService();
    final profiles = await DbService.getProfiles();
    final activeId = await DbService.getActiveProfileId();
    final active = activeId == null
        ? null
        : profiles.where((p) => p.id == activeId).firstOrNull;

    final StringBuffer sb = StringBuffer();
    sb.writeln('=== FOCSQ Diagnostic Report ===');
    sb.writeln('App Version: ${packageInfo.version}+${packageInfo.buildNumber}');
    sb.writeln('OS: ${Platform.operatingSystem} ${_cleanOsVersion()}');
    sb.writeln('Locale: ${Platform.localeName}');
    sb.writeln('Processors: ${Platform.numberOfProcessors}');
    sb.writeln('---');
    sb.writeln('Автозапуск: ${_describeAutostart(autostartEnabled)}');

    if (Platform.isLinux) {
      sb.writeln('---');
      sb.writeln('Linux-окружение:');
      for (final line in _linuxEnvironment()) {
        sb.writeln('- $line');
      }
    }

    sb.writeln('---');
    sb.writeln('Подключение:');
    sb.writeln('- Состояние: ${connect.state.name}');
    final stats = connect.stats;
    sb.writeln(
      '- Потоки: ${stats.activeWorkers} | Отдано: ${_formatBytes(stats.bytesUp)} | '
      'Получено: ${_formatBytes(stats.bytesDown)}',
    );
    if (connect.lastError != null) {
      sb.writeln('- Последняя ошибка: ${connect.lastError}');
    }

    sb.writeln('---');
    sb.writeln('Профили: ${profiles.length}');
    if (active != null) {
      sb.writeln(_describeProfile(active, isActive: true));
      sb.writeln('Активный профиль указан первым.');
    }
    for (final profile in profiles) {
      if (profile.id == active?.id) continue;
      sb.writeln(_describeProfile(profile));
    }

    final logs = LogService().lines;
    if (logs.isNotEmpty) {
      sb.writeln('---');
      sb.writeln('Журнал (последние $_logTailLines строк):');
      final tail = logs.length > _logTailLines
          ? logs.sublist(logs.length - _logTailLines)
          : logs;
      for (final line in tail) {
        sb.writeln('| $line');
      }
    }

    return sb.toString();
  }

  static String _describeProfile(ServerProfile profile, {bool isActive = false}) {
    final marker = isActive ? '[активный] ' : '';
    return '- $marker${profile.name ?? "Без имени"}: '
        '${profile.ip ?? "?"}:${profile.port ?? "?"}, '
        'потоков: ${profile.power ?? "?"}';
  }

  static String _describeAutostart(bool? enabled) {
    if (enabled == null) return 'не поддерживается на этой платформе';
    return enabled ? 'включён' : 'выключен';
  }


  static List<String> _linuxEnvironment() {
    final lines = <String>[];
    final env = Platform.environment;

    final tun = File('/dev/net/tun').statSync();
    if (tun.type == FileSystemEntityType.notFound) {
      lines.add('/dev/net/tun: отсутствует (модуль ядра tun не загружен?)');
    } else {
      lines.add('/dev/net/tun: есть (${tun.modeString})');
    }

    lines.add('Права: ${_describeLinuxCaps()}');

    final viaLauncher = env['FOCSQ_LAUNCHER'] == '1';
    lines.add(
      viaLauncher
          ? 'Лаунчер: да (проверки библиотек и права TUN выполнялись)'
          : 'Лаунчер: нет — запущен бинарник напрямую, запускай focsq.sh',
    );

    final systemdResolved = Directory('/run/systemd/resolve').existsSync();
    lines.add(
      systemdResolved
          ? 'DNS: systemd-resolved (переключение через resolvectl)'
          : 'DNS: без systemd-resolved (будет перезаписан /etc/resolv.conf)',
    );

    final desktop = (env['XDG_CURRENT_DESKTOP'] ?? 'неизвестен').trim();
    final session = (env['XDG_SESSION_TYPE'] ?? 'неизвестна').trim();
    final gnomeHint = desktop.toUpperCase().contains('GNOME')
        ? ' — для трея нужно расширение AppIndicator'
        : '';
    lines.add('Рабочий стол: $desktop, сессия $session$gnomeHint');

    return lines;
  }

  static String _describeLinuxCaps() {
    try {
      final status = File('/proc/self/status').readAsStringSync();
      final uid = parseEffectiveUid(status);
      if (uid == 0) return 'root — туннель доступен';
      if (parseHasCapNetAdmin(status)) {
        return 'CAP_NET_ADMIN есть (через focsq-tun/лаунчер) — туннель доступен';
      }
      if (uid != null) {
        return 'обычный пользователь (uid $uid), CAP_NET_ADMIN нет — запусти через focsq.sh (выдаст право на focsq-tun)';
      }
    } catch (_) {}
    return 'неизвестны — /proc/self/status не прочитан';
  }

  static String parsePrettyName(String osRelease) {
    var result = '';
    for (final line in osRelease.split('\n')) {
      if (!line.startsWith('PRETTY_NAME=')) continue;
      final value = line.substring('PRETTY_NAME='.length).trim();
      if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        result = value.substring(1, value.length - 1);
      } else {
        result = value;
      }
    }
    return result;
  }

  static int? parseEffectiveUid(String procStatus) {
    for (final line in procStatus.split('\n')) {
      if (!line.startsWith('Uid:')) continue;
      final fields = line.trim().split(RegExp(r'\s+'));
      if (fields.length >= 3) return int.tryParse(fields[2]);
      return null;
    }
    return null;
  }

  static bool parseHasCapNetAdmin(String procStatus) {
    const capNetAdminBit = 12;
    for (final line in procStatus.split('\n')) {
      if (!line.startsWith('CapEff:')) continue;
      final caps =
          BigInt.tryParse(line.substring('CapEff:'.length).trim(), radix: 16);
      return caps != null &&
          (caps >> capNetAdminBit) & BigInt.one == BigInt.one;
    }
    return false;
  }

  static Future<bool> copyToClipboard() async {
    try {
      final report = await generateReport();
      await Clipboard.setData(ClipboardData(text: report));
      return true;
    } catch (_) {
      return false;
    }
  }

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes Б';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} КБ';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / 1024 / 1024).toStringAsFixed(1)} МБ';
    }
    return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} ГБ';
  }

  static String _cleanOsVersion() {
    final raw = Platform.operatingSystemVersion;
    if (Platform.isLinux) {
      try {
        final distro =
            parsePrettyName(File('/etc/os-release').readAsStringSync());
        if (distro.isNotEmpty) return '$distro (ядро $raw)';
      } catch (_) {}
      return 'ядро $raw';
    }
    if (Platform.isWindows) {
      final buildMatch = RegExp(r'Build\s+(\d+)').firstMatch(raw);
      if (buildMatch != null) {
        final buildNum = int.tryParse(buildMatch.group(1)!) ?? 0;
        if (buildNum >= 22000) {
          return raw.replaceFirst('Windows 10', 'Windows 11');
        }
      }
    }
    return raw;
  }
}
