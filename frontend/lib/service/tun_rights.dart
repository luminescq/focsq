// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

import '../l10n/app_locale.dart';
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
      final lines = await File(
        statusFile ?? '/proc/self/status',
      ).readAsLines(encoding: const SystemEncoding());
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
      LogService().add(S.tunNoWrapperLog);
      return S.tunNoWrapperIssue;
    }

    if (env['FOCSQ_NO_GRANT'] == '1') {
      LogService().add(S.tunGrantDisabledLog);
      return S.tunGrantDisabledIssue;
    }

    final error = await (grant?.call(wrapper) ?? _pkexecSetcap(wrapper, env));
    if (error != null) {
      LogService().add(S.tunGrantErrorLog(error));
      return error;
    }

    LogService().add(S.tunGrantedLog);
    await (onGranted?.call(wrapper) ?? _restartThroughWrapper(wrapper));
    return null;
  }

  static Future<String?> _pkexecSetcap(
    String wrapper,
    Map<String, String> env,
  ) async {
    try {
      final result = await Process.run('pkexec', [
        'setcap',
        'cap_net_admin+ep',
        wrapper,
      ], environment: env);
      if (result.exitCode == 0) return null;
      final stderr = (result.stderr is String)
          ? (result.stderr as String).trim()
          : '';
      return stderr.isEmpty
          ? S.tunPkexecCancelled(result.exitCode)
          : S.tunPkexecFailed(stderr);
    } catch (error) {
      return S.tunPkexecUnavailable(wrapper, error);
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
      LogService().add(S.tunRestartFailed(error));
      return;
    }
    exit(0);
  }
}
