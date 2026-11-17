// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../models/server_profile.dart';
import '../l10n/app_locale.dart';
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
    sb.writeln(
      'App Version: ${packageInfo.version}+${packageInfo.buildNumber}',
    );
    sb.writeln('OS: ${Platform.operatingSystem} ${_cleanOsVersion()}');
    sb.writeln('Locale: ${Platform.localeName}');
    sb.writeln('Processors: ${Platform.numberOfProcessors}');
    sb.writeln('---');
    sb.writeln(S.reportAutostartLine(S.reportAutostartState(autostartEnabled)));

    if (Platform.isLinux) {
      sb.writeln('---');
      sb.writeln(S.reportLinuxEnv);
      for (final line in _linuxEnvironment()) {
        sb.writeln('- $line');
      }
    }

    sb.writeln('---');
    sb.writeln(S.reportConnectionSection);
    sb.writeln(S.reportStateLine(connect.state.name));
    final stats = connect.stats;
    sb.writeln(
      S.reportStreamsLine(
        stats.activeWorkers,
        _formatBytes(stats.bytesUp),
        _formatBytes(stats.bytesDown),
      ),
    );
    if (connect.lastError != null) {
      sb.writeln(S.reportLastErrorLine(connect.lastError!));
    }

    sb.writeln('---');
    sb.writeln(S.reportProfilesLine(profiles.length));
    if (active != null) {
      sb.writeln(_describeProfile(active, isActive: true));
      sb.writeln(S.reportActiveFirst);
    }
    for (final profile in profiles) {
      if (profile.id == active?.id) continue;
      sb.writeln(_describeProfile(profile));
    }

    final logs = LogService().lines;
    if (logs.isNotEmpty) {
      sb.writeln('---');
      sb.writeln(S.reportLogTail(_logTailLines));
      final tail = logs.length > _logTailLines
          ? logs.sublist(logs.length - _logTailLines)
          : logs;
      for (final line in tail) {
        sb.writeln('| $line');
      }
    }

    return sb.toString();
  }

  static String _describeProfile(
    ServerProfile profile, {
    bool isActive = false,
  }) {
    return S.reportProfile(
      profile.name ?? S.unnamedProfile,
      profile.ip ?? '?',
      '${profile.port ?? "?"}',
      '${profile.power ?? "?"}',
      isActive,
    );
  }

  static List<String> _linuxEnvironment() {
    final lines = <String>[];
    final env = Platform.environment;

    final tun = File('/dev/net/tun').statSync();
    if (tun.type == FileSystemEntityType.notFound) {
      lines.add(S.reportTunMissing);
    } else {
      lines.add(S.reportTunPresent(tun.modeString()));
    }

    lines.add(S.reportCapsLine(_describeLinuxCaps()));

    final viaLauncher = env['FOCSQ_LAUNCHER'] == '1';
    lines.add(viaLauncher ? S.reportLauncherYes : S.reportLauncherNo);

    final systemdResolved = Directory('/run/systemd/resolve').existsSync();
    lines.add(systemdResolved ? S.reportDnsResolved : S.reportDnsPlain);

    final desktop = (env['XDG_CURRENT_DESKTOP'] ?? S.reportUnknownValue).trim();
    final session = (env['XDG_SESSION_TYPE'] ?? S.reportUnknownValue).trim();
    final gnomeHint = desktop.toUpperCase().contains('GNOME')
        ? S.reportGnomeHint
        : '';
    lines.add(S.reportDesktopLine(desktop, session, gnomeHint));

    return lines;
  }

  static String _describeLinuxCaps() {
    try {
      final status = File('/proc/self/status').readAsStringSync();
      final uid = parseEffectiveUid(status);
      if (uid == 0) return S.reportCapsRoot;
      if (parseHasCapNetAdmin(status)) {
        return S.reportCapsGranted;
      }
      if (uid != null) {
        return S.reportCapsUser(uid);
      }
    } catch (_) {}
    return S.reportCapsUnknown;
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
      final caps = BigInt.tryParse(
        line.substring('CapEff:'.length).trim(),
        radix: 16,
      );
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
    if (bytes < 1024) return '$bytes ${S.unitB}';
    if (bytes < 1024 * 1024)
      return '${(bytes / 1024).toStringAsFixed(1)} ${S.unitKB}';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / 1024 / 1024).toStringAsFixed(1)} ${S.unitMB}';
    }
    return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} ${S.unitGB}';
  }

  static String _cleanOsVersion() {
    final raw = Platform.operatingSystemVersion;
    if (Platform.isLinux) {
      try {
        final distro = parsePrettyName(
          File('/etc/os-release').readAsStringSync(),
        );
        if (distro.isNotEmpty) return '$distro (${S.reportKernel(raw)})';
      } catch (_) {}
      return S.reportKernel(raw);
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
