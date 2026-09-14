// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';

import 'logs.dart';

class AutostartService {
  AutostartService._();

  static const String _taskName = 'FOCSQ';

  static const String _args = '--minimized';

  static bool _initialized = false;
  static String? _exePath;

  static Future<void> init() async {
    if (_initialized) return;
    _exePath = Platform.resolvedExecutable;
    _initialized = true;
  }

  static Future<int> _run(List<String> arguments) async {
    await init();
    final commandLine = '"schtasks" ${arguments.join(' ')}';
    return Isolate.run(() => runHiddenProcess(commandLine));
  }

  static Future<bool> isEnabled() async {
    await init();
    if (Platform.isLinux) return _linuxIsEnabled();
    if (!Platform.isWindows) return false;
    try {
      final result = await _run(['/Query', '/TN', _taskName]);
      return result == 0;
    } catch (error) {
      LogService().add('[АВТОСТАРТ] Ошибка чтения состояния: $error');
      return false;
    }
  }

  static Future<bool> enable() async {
    await init();
    if (Platform.isLinux) return _linuxEnable();
    if (!Platform.isWindows) {
      LogService().add(
        '[АВТОСТАРТ] На этой платформе автозапуск не поддерживается',
      );
      return false;
    }
    try {
      final result = await _run([
        '/Create',
        '/TN',
        _taskName,
        '/TR',
        '"$_exePath" $_args',
        '/SC',
        'ONLOGON',
        '/RL',
        'HIGHEST',
        '/F',
      ]);
      if (result != 0) {
        LogService().add('[АВТОСТАРТ] Не удалось создать задачу (код $result)');
        return false;
      }
      LogService().add('[АВТОСТАРТ] Задача создана (запуск от админа)');
      return true;
    } catch (error) {
      LogService().add('[АВТОСТАРТ] Ошибка включения: $error');
      return false;
    }
  }

  static Future<bool> disable() async {
    await init();
    if (Platform.isLinux) return _linuxDisable();
    if (!Platform.isWindows) return false;
    try {
      final result = await _run(['/Delete', '/TN', _taskName, '/F']);
      if (result != 0) {
        LogService().add('[АВТОСТАРТ] Не удалось удалить задачу (код $result)');
        return false;
      }
      LogService().add('[АВТОСТАРТ] Задача удалена');
      return true;
    } catch (error) {
      LogService().add('[АВТОСТАРТ] Ошибка выключения: $error');
      return false;
    }
  }

  static String? _linuxDesktopFile() {
    final env = Platform.environment;
    final config =
        env['XDG_CONFIG_HOME'] ??
        (env['HOME'] == null ? null : '${env['HOME']}/.config');
    if (config == null) return null;
    return '$config/autostart/focsq.desktop';
  }

  static String buildDesktopEntry(String execPath, [String args = _args]) {
    return '[Desktop Entry]\n'
        'Type=Application\n'
        'Name=FOCSQ\n'
        'Exec="$execPath" $args\n'
        'TryExec=$execPath\n'
        'Terminal=false\n'
        'X-GNOME-Autostart-enabled=true\n';
  }

  static String? _launcherPath() {
    final executable = Platform.resolvedExecutable;
    return File(executable).existsSync() ? executable : null;
  }

  static Future<bool> _linuxIsEnabled() async {
    final path = _linuxDesktopFile();
    return path != null && File(path).existsSync();
  }

  static Future<bool> _linuxEnable() async {
    final path = _linuxDesktopFile();
    final launcher = _launcherPath();
    if (path == null || launcher == null) {
      LogService().add(
        '[АВТОСТАРТ] Нет HOME/XDG_CONFIG_HOME — некуда писать запись',
      );
      return false;
    }
    try {
      final file = File(path);
      await file.parent.create(recursive: true);
      await file.writeAsString(buildDesktopEntry(launcher), flush: true);
      LogService().add('[АВТОСТАРТ] Запись создана: $path');
      LogService().add(
        '[АВТОСТАРТ] Перед первым автозапуском запусти FOCSQ из меню '
        'и подтверди выдачу права туннеля (однократно)',
      );
      return true;
    } catch (error) {
      LogService().add('[АВТОСТАРТ] Ошибка включения: $error');
      return false;
    }
  }

  static Future<bool> _linuxDisable() async {
    final path = _linuxDesktopFile();
    if (path == null) return false;
    try {
      final file = File(path);
      if (await file.exists()) {
        await file.delete();
        LogService().add('[АВТОСТАРТ] Запись удалена');
      }
      return true;
    } catch (error) {
      LogService().add('[АВТОСТАРТ] Ошибка выключения: $error');
      return false;
    }
  }

  static Future<bool> toggle(bool value) => value ? enable() : disable();
}

const int _createNoWindow = 0x08000000;

const int _waitObject0 = 0;

const int _waitTimeoutMs = 30000;

final _kernel32 = DynamicLibrary.open('kernel32.dll');

final _createProcessW = _kernel32
    .lookupFunction<
      Int32 Function(
        Pointer<Uint16> applicationName,
        Pointer<Uint16> commandLine,
        Pointer<Void> processAttributes,
        Pointer<Void> threadAttributes,
        Int32 inheritHandles,
        Uint32 creationFlags,
        Pointer<Void> environment,
        Pointer<Uint16> currentDirectory,
        Pointer<StartupInfoW> startupInfo,
        Pointer<ProcessInformation> processInformation,
      ),
      int Function(
        Pointer<Uint16>,
        Pointer<Uint16>,
        Pointer<Void>,
        Pointer<Void>,
        int,
        int,
        Pointer<Void>,
        Pointer<Uint16>,
        Pointer<StartupInfoW>,
        Pointer<ProcessInformation>,
      )
    >('CreateProcessW');

final _waitForSingleObject = _kernel32
    .lookupFunction<
      Uint32 Function(Pointer<Void> handle, Uint32 milliseconds),
      int Function(Pointer<Void>, int)
    >('WaitForSingleObject');

final _getExitCodeProcess = _kernel32
    .lookupFunction<
      Int32 Function(Pointer<Void> process, Pointer<Uint32> exitCode),
      int Function(Pointer<Void>, Pointer<Uint32>)
    >('GetExitCodeProcess');

final _closeHandle = _kernel32
    .lookupFunction<Int32 Function(Pointer<Void>), int Function(Pointer<Void>)>(
      'CloseHandle',
    );

final _getLastError = _kernel32
    .lookupFunction<Uint32 Function(), int Function()>('GetLastError');

final class StartupInfoW extends Struct {
  @Uint32()
  external int cb;

  external Pointer<Uint16> lpReserved;

  external Pointer<Uint16> lpDesktop;

  external Pointer<Uint16> lpTitle;

  @Uint32()
  external int dwX;

  @Uint32()
  external int dwY;

  @Uint32()
  external int dwXSize;

  @Uint32()
  external int dwYSize;

  @Uint32()
  external int dwXCountChars;

  @Uint32()
  external int dwYCountChars;

  @Uint32()
  external int dwFillAttribute;

  @Uint32()
  external int dwFlags;

  @Uint16()
  external int wShowWindow;

  @Uint16()
  external int cbReserved2;

  external Pointer<Uint8> lpReserved2;

  external Pointer<Void> hStdInput;

  external Pointer<Void> hStdOutput;

  external Pointer<Void> hStdError;
}

final class ProcessInformation extends Struct {
  external Pointer<Void> hProcess;

  external Pointer<Void> hThread;

  @Uint32()
  external int dwProcessId;

  @Uint32()
  external int dwThreadId;
}

int runHiddenProcess(String commandLine) {
  final nativeCommandLine = commandLine.toNativeUtf16().cast<Uint16>();
  final startupInfo = calloc<StartupInfoW>();
  final processInfo = calloc<ProcessInformation>();
  try {
    startupInfo.ref.cb = sizeOf<StartupInfoW>();
    final ok = _createProcessW(
      nullptr,
      nativeCommandLine,
      nullptr,
      nullptr,
      0,
      _createNoWindow,
      nullptr,
      nullptr,
      startupInfo,
      processInfo,
    );
    if (ok == 0) {
      throw Exception(
        'Не удалось запустить процесс (GetLastError: ${_getLastError()})',
      );
    }
    final wait = _waitForSingleObject(processInfo.ref.hProcess, _waitTimeoutMs);
    if (wait != _waitObject0) {
      throw Exception(
        'Процесс не завершился за $_waitTimeoutMsмс (код ожидания $wait)',
      );
    }
    final exitCode = calloc<Uint32>();
    try {
      if (_getExitCodeProcess(processInfo.ref.hProcess, exitCode) == 0) {
        throw Exception('GetExitCodeProcess не удался');
      }
      return exitCode.value;
    } finally {
      calloc.free(exitCode);
    }
  } finally {
    if (processInfo.ref.hProcess != nullptr) {
      _closeHandle(processInfo.ref.hProcess);
    }
    if (processInfo.ref.hThread != nullptr) {
      _closeHandle(processInfo.ref.hThread);
    }
    calloc.free(startupInfo);
    calloc.free(processInfo);
    malloc.free(nativeCommandLine);
  }
}
