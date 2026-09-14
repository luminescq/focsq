// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

import 'logs.dart';

class TunRights {
  TunRights._();

  static String? get wrapperPath {
    final wrapper =
        '${File(Platform.resolvedExecutable).parent.path}/focsq-tun';
    return File(wrapper).existsSync() ? wrapper : null;
  }

  static Future<bool> hasCapNetAdmin({String? statusFile}) async {
    try {
      final lines = await File(statusFile ?? '/proc/self/status')
          .readAsLines(encoding: const SystemEncoding());
      for (final line in lines) {
        if (!line.startsWith('CapEff:')) continue;
        final value = int.tryParse(line.substring(7).trim(), radix: 16);
        return value != null && (value & (1 << 12)) != 0;
      }
      return false;
    } catch (_) {
      return false;
    }
  }

  static Future<String?> ensureForConnect({
    String? wrapper,
    String? statusFile,
    Map<String, String>? env,
    Future<String?> Function(String wrapper)? grant,
    Future<void> Function(String wrapper)? onGranted,
  }) async {
    wrapper ??= wrapperPath;
    env ??= Platform.environment;

    if (await hasCapNetAdmin(statusFile: statusFile)) return null;

    if (wrapper == null) {
      LogService().add('[TUN] Права нет, обёртки focsq-tun нет рядом');
      return 'Нет права на туннель (cap_net_admin) и рядом с приложением '
          'нет обёртки focsq-tun. Запусти приложение через focsq.sh';
    }

    if (env['FOCSQ_NO_GRANT'] == '1') {
      LogService().add('[TUN] Права нет, выдача отключена (FOCSQ_NO_GRANT)');
      return 'Нет права на туннель (cap_net_admin). Запусти приложение '
          'через focsq.sh или без переменной FOCSQ_NO_GRANT';
    }

    final error =
        await (grant?.call(wrapper) ?? _pkexecSetcap(wrapper, env));
    if (error != null) {
      LogService().add('[TUN] Выдача права не удалась: $error');
      return error;
    }

    LogService()
        .add('[TUN] Право выдано (focsq-tun) — перезапускаю приложение');
    await (onGranted?.call(wrapper) ?? _restartThroughWrapper(wrapper));
    return null;
  }

  static Future<String?> _pkexecSetcap(
    String wrapper,
    Map<String, String> env,
  ) async {
    try {
      final result = await Process.run(
        'pkexec',
        ['setcap', 'cap_net_admin+ep', wrapper],
        environment: env,
      );
      if (result.exitCode == 0) return null;
      final stderr = (result.stderr is String)
          ? (result.stderr as String).trim()
          : '';
      return stderr.isEmpty
          ? 'Выдача права TUN отменена или не удалась '
              '(pkexec, код ${result.exitCode})'
          : 'Выдача права TUN не удалась: $stderr';
    } catch (error) {
      return 'pkexec недоступен — выдай право вручную в терминале:\n'
          'sudo setcap cap_net_admin+ep "$wrapper"\n($error)';
    }
  }

  static Future<void> _restartThroughWrapper(String wrapper) async {
    final exe = Platform.resolvedExecutable;
    final env = Map<String, String>.from(Platform.environment)
      ..remove('FOCSQ_TUN_REEXEC');
    try {
      await Process.start(
        wrapper,
        [exe],
        environment: env,
        mode: ProcessStartMode.detached,
      );
    } catch (error) {
      LogService().add('[TUN] Перезапуск не удался: $error');
      return;
    }
    exit(0);
  }
}
