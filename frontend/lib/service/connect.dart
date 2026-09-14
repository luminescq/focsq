// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:ffi' as ffi;
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;

import '../src/rust/api/simple.dart' as rust;
import '../src/rust/frb_generated.dart';
import 'captcha.dart';
import 'db.dart';
import 'import.dart';
import 'logs.dart';
import 'tun_rights.dart';
import 'vk/auto_calls.dart';
import 'vk/token.dart';

enum ConnectState { disconnected, connecting, connected, disconnecting }

class ConnectStats {
  const ConnectStats({
    this.activeWorkers = 0,
    this.bytesUp = 0,
    this.bytesDown = 0,
  });

  final int activeWorkers;
  final int bytesUp;
  final int bytesDown;

  bool get isConnected => activeWorkers > 0;
}

class ConnectService extends ChangeNotifier {
  ConnectService._();

  static final ConnectService _instance = ConnectService._();

  factory ConnectService() => _instance;

  static const String _defaultClientIds = '8202606,6287487';
  static const String _listen = '127.0.0.1:9000';
  static const String _eventPrefix = '__CSQTT_EVENT__|';

  ConnectState _state = ConnectState.disconnected;

  ConnectState get state => _state;

  ConnectStats _stats = const ConnectStats();

  ConnectStats get stats => _stats;

  String? _lastError;

  String? get lastError => _lastError;

  bool _tunReady = false;

  void _log(String line) => LogService().add(line);

  void _setState(ConnectState value) {
    if (_state == value) return;
    final previous = _state;
    _state = value;
    if (value == ConnectState.connected || value == ConnectState.disconnected) {
      _connectWatchdog?.cancel();
      _connectWatchdog = null;
    }
    switch (value) {
      case ConnectState.connecting:
        _log('[СТАТУС] Подключение...');
      case ConnectState.connected:
        _log('[СТАТУС] Подключено · потоков ${_stats.activeWorkers}');
      case ConnectState.disconnecting:
        _log('[СТАТУС] Отключение...');
      case ConnectState.disconnected:
        if (previous != ConnectState.disconnected) {
          _log('[СТАТУС] Отключено');
        }
    }
    notifyListeners();
  }

  Timer? _connectWatchdog;
  static const Duration _connectWatchdogTimeout = Duration(seconds: 60);

  void _restartConnectWatchdog() {
    if (_state != ConnectState.connecting) return;
    _connectWatchdog?.cancel();
    _connectWatchdog = Timer(_connectWatchdogTimeout, _onConnectTimeout);
  }

  Future<void> _onConnectTimeout() async {
    if (_state != ConnectState.connecting) return;
    _connectWatchdog = null;
    _lastError =
        'Не удалось подключиться за '
        '${_connectWatchdogTimeout.inSeconds} с — проверьте интернет '
        'или отключите другой VPN';
    _log('[КОННЕКТ] ${_lastError!}');

    await stop();
    await waitForExit();
    if (_state != ConnectState.disconnected) {
      _log('[КОННЕКТ] Ядро не ответило на отмену — состояние сброшено');
      _coreRun = null;
      _clientRunning = false;
      _setState(ConnectState.disconnected);
    }
  }

  bool _rustReady = false;

  Future<void>? _rustInitFuture;
  StreamSubscription<String>? _coreLogSub;
  Future<void>? _coreRun;
  bool _clientRunning = false;

  final List<String> _autoCallIds = [];
  String? _autoApiToken;
  VkAutoCallsManager? _callsManager;

  Future<void> prewarm() async {
    try {
      await _ensureRust();
    } catch (_) {}

    if (Platform.isWindows) {
      try {
        await _ensureWintunDll();
      } catch (_) {}
    }
  }

  Future<void> _ensureRust() {
    final existing = _rustInitFuture;
    if (existing != null) return existing;
    final future = _initRust();
    _rustInitFuture = future;
    return future.whenComplete(() {
      if (identical(_rustInitFuture, future) && !_rustReady) {
        _rustInitFuture = null;
      }
    });
  }

  Future<void> _initRust() async {
    try {
      await RustLib.init();
      _coreLogSub = rust.setupLogStream().listen(
        onCoreLine,
        onError: (Object error) => _log('[ЯДРО] Ошибка потока логов: $error'),
        onDone: () => _log('[ЯДРО] Поток логов закрыт'),
      );
      _rustReady = true;
      _log('[ЯДРО] Мост инициализирован');
    } on Object catch (e) {
      throw Exception(_cleanError(e));
    }
  }

  Future<String?> toggle() async {
    final error = await (_state == ConnectState.disconnected
        ? start()
        : stop());
    if (error != null) _log('[КОННЕКТ] $error');
    return error;
  }

  Future<String?> start() async {
    if (_state != ConnectState.disconnected) return null;

    LogService().clear();

    _lastError = null;
    _tunReady = false;
    _setState(ConnectState.connecting);
    _restartConnectWatchdog();

    try {
      final error = await _startInternal();
      if (error != null) {
        _setState(ConnectState.disconnected);
      }
      return error;
    } catch (e) {
      _setState(ConnectState.disconnected);
      final msg = 'Ошибка запуска: $e';
      _log('[КОННЕКТ] $msg');
      return msg;
    }
  }

  Future<String?> _startInternal() async {
    await _ensureRust();

    final settings = await DbService.getSettings();
    final profile = await DbService.getActiveProfile(settings);
    if (profile == null) {
      return 'Сначала добавьте и выберите профиль сервера';
    }

    final ip = profile.ip?.trim() ?? '';
    final port = profile.port;
    final password = profile.password?.trim() ?? '';
    if (ip.isEmpty || !ImportService.isValidPort(port)) {
      return 'В профиле «${profile.name}» не заполнены адрес или порт';
    }
    if (password.isEmpty) {
      return 'В профиле «${profile.name}» не указан пароль';
    }

    if (Platform.isWindows) {
      if (!_isAdmin()) {
        return 'Запустите приложение от имени администратора '
            '(нужны права на создание TUN-адаптера)';
      }
    } else if (Platform.isLinux) {
      final issue = await TunRights.ensureForConnect();
      if (issue != null) {
        _setState(ConnectState.disconnected);
        return issue;
      }
    }

    var hashMode = switch (settings.hashes) {
      'Авто ВК' => 'auto_js',
      'Авто API' => 'auto_api',
      _ => 'manual',
    };
    final authMode = switch (settings.workMode) {
      'Авто ВК' => 'auto_js',
      'Капча' => 'legacy',
      _ => 'vkcalls',
    };

    if (authMode == 'auto_js' && hashMode != 'auto_js') {
      hashMode = 'auto_js';
    }

    String? vkToken;
    if (hashMode == 'auto_api' ||
        hashMode == 'auto_js' ||
        authMode == 'auto_js') {
      final vk = await VkToken.readWithStatus();
      if (vk.issue != null) {
        return 'Токен ВК недоступен: ${vk.issue}';
      }
      vkToken = vk.token;
      if (vkToken == null || vkToken.isEmpty) {
        return 'Требуется вход ВКонтакте (Настройки → Аккаунт ВКонтакте)';
      }
    }

    var vkValue = settings.customHashes.trim();
    var allowRedistribution = false;
    if (hashMode == 'manual' && vkValue.isEmpty) {
      return 'Для ручного режима заполните хеши (Настройки → Хеши)';
    }

    VkAutoCallsManager? manager;
    if (hashMode == 'auto_api') {
      manager = VkAutoCallsManager();
      final workers = _normalizeWorkers(profile.power);
      final batch = await manager.createForWorkers(
        token: vkToken!,
        workers: workers,
        onProgress: (created, total) =>
            _log('[АВТО API] Звонок создан: $created/$total'),
      );
      if (batch == null) {
        manager.close();

        if (manager.tokenInvalidSeen) {
          await VkToken.delete();
          return 'Токен ВК недействителен — выполните вход заново';
        }
        return 'Не удалось создать звонки VK — проверь интернет и попробуй ещё раз';
      }
      vkValue = batch.hashes.join(',');
      allowRedistribution = batch.needsWorkerRedistribution;
      _autoCallIds
        ..clear()
        ..addAll(batch.callIds);
      _autoApiToken = vkToken;
      _callsManager = manager;
    }

    final workers = _normalizeWorkers(profile.power);
    final config = rust.AppClientConfig(
      turn: '',
      port: '',
      listen: _listen,
      vk: vkValue,
      vkHashMode: hashMode,
      peer: '$ip:$port',
      workers: BigInt.from(workers),
      allowHashRedistribution: allowRedistribution,
      deviceId: settings.deviceId,
      password: password,
      vkAuthMode: authMode,
      captchaMode: 'auto',

      fingerprint: settings.fingerprint.toLowerCase(),
      clientIds: _defaultClientIds,
      obfs: 'audio',
      generation: BigInt.from(_nextGeneration()),
      salt: _generateSalt(),

      tunUds: _tunMode,
      validateVkHashes: false,
      vkJsToken: vkToken ?? '',
    );

    if (Platform.isWindows) {
      await _ensureWintunDll();
    }

    _clientRunning = true;

    _coreRun = rust
        .startCsqttClient(config: config)
        .then((_) => _onCoreExited(null))
        .catchError(_onCoreExited);
    return null;
  }

  Future<String?> stop() async {
    if (_coreRun == null) return null;
    _setState(ConnectState.disconnecting);
    try {
      await rust.stopCsqttClient();
      return null;
    } catch (error) {
      _lastError = _cleanError(error);
      return _lastError;
    }
  }

  Future<void> waitForExit([
    Duration timeout = const Duration(seconds: 6),
  ]) async {
    final run = _coreRun;
    if (run == null) return;
    try {
      await run.timeout(timeout);
    } catch (_) {}
  }

  Future<void> _onCoreExited(Object? error) async {
    if (_coreRun == null) return;
    _coreRun = null;
    _clientRunning = false;
    _tunReady = false;
    _stats = const ConnectStats();

    CaptchaService.abort('клиент остановлен');

    if (error != null) {
      _lastError = _cleanError(error);
      _log('[ЯДРО] ${_lastError!}');
    } else {
      _log('[ЯДРО] Ядро остановлено');
    }

    await _finishAutoApiCalls();
    _setState(ConnectState.disconnected);
  }

  Future<void> _finishAutoApiCalls() async {
    final ids = List<String>.of(_autoCallIds);
    final token = _autoApiToken;
    final manager = _callsManager;
    _autoCallIds.clear();
    _autoApiToken = null;
    _callsManager = null;
    if (ids.isEmpty || token == null || manager == null) return;

    _log('[АВТО API] Завершаю звонки (${ids.length})...');
    await manager.finishAll(token, ids);
    manager.close();
  }

  void onCoreLine(String line) {
    _log(line);

    if (line.startsWith('CAPTCHA_SOLVE|')) {
      CaptchaService.handleSolveRequest(
        line,
        _log,
        clientRunning: _clientRunning,
      );
      return;
    }
    if (!line.startsWith(_eventPrefix)) return;
    final withoutPrefix = line.substring(_eventPrefix.length);
    final typeEnd = withoutPrefix.indexOf('|');
    if (typeEnd < 0) return;
    final type = withoutPrefix.substring(0, typeEnd);
    final payloadRaw = withoutPrefix.substring(typeEnd + 1);

    Map<String, dynamic> payload = {};
    if (payloadRaw.isNotEmpty && payloadRaw.startsWith('{')) {
      try {
        final decoded = jsonDecode(payloadRaw);
        if (decoded is Map<String, dynamic>) payload = decoded;
      } catch (_) {}
    }

    switch (type) {
      case 'STATS':
        final active = (payload['active'] as num?)?.toInt() ?? 0;
        _stats = ConnectStats(
          activeWorkers: active,
          bytesUp: (payload['bytes_up'] as num?)?.toInt() ?? 0,
          bytesDown: (payload['bytes_down'] as num?)?.toInt() ?? 0,
        );

        if (active > 0 && _state == ConnectState.connecting) {
          _restartConnectWatchdog();
        }
        if (active > 0 &&
            _clientRunning &&
            _tunReady &&
            _state != ConnectState.disconnecting) {
          _setState(ConnectState.connected);
        }
        notifyListeners();

      case 'ACTIVE_ZERO':
        _stats = const ConnectStats();
        notifyListeners();

      case 'ERROR':
        final code = (payload['code'] as num?)?.toInt() ?? 0;
        final message = payload['message']?.toString() ?? '';
        final fatal = payload['fatal'] == true;
        _log('[ЯДРО] ${fatal ? 'ФАТАЛЬНО' : 'Ошибка'} ($code) $message');
        if (fatal) {
          _lastError = message.isEmpty ? 'Ошибка ядра' : message;
          _clientRunning = false;
          _setState(ConnectState.disconnected);
        }
        notifyListeners();

      case 'STOPPED':
        _clientRunning = false;
        _stats = const ConnectStats();
        _setState(ConnectState.disconnected);

      case 'CONFIG':
        _restartConnectWatchdog();
        final configStr =
            payload['config']?.toString() ??
            (payloadRaw.startsWith('TUNCONF:') ? payloadRaw : '');
        if (configStr.startsWith('TUNCONF:')) {
          final conf = configStr.replaceFirst('TUNCONF:', '').trim();
          final parts = conf.split(':');
          final ip = parts.isNotEmpty ? parts[0] : '';
          final dns = parts.length > 1 ? parts[1] : '';
          _log('[TUN] Конфиг сети получен: $ip (DNS: $dns)');
          _tunReady = true;
          if (_clientRunning &&
              _stats.activeWorkers > 0 &&
              _state != ConnectState.disconnecting) {
            _setState(ConnectState.connected);
          }
        }

      case 'SERVER_RESTART':
        _log('[ЯДРО] Сервер перезапущен панелью');

      case 'READY':
      case 'STARTED':
        _restartConnectWatchdog();
      case 'PROGRESS':
      case 'PATH_HEALTH':
        break;
      case 'CAPTCHA_REQUEST':
        _connectWatchdog?.cancel();
        _connectWatchdog = null;
      case 'CAPTCHA_DONE':
        _restartConnectWatchdog();

      default:
        break;
    }
  }

  Future<void> _ensureWintunDll() async {
    try {
      final appData = Platform.environment['LOCALAPPDATA'];
      final dir = Directory('$appData${Platform.pathSeparator}FOCSQ');
      await dir.create(recursive: true);
      final target = File('${dir.path}${Platform.pathSeparator}wintun.dll');
      final exeDll = File(
        '${File(Platform.resolvedExecutable).parent.path}'
        '${Platform.pathSeparator}wintun.dll',
      );

      if (await exeDll.exists()) {
        try {
          await exeDll.delete();
        } catch (_) {}
      }
      if (!await target.exists()) {
        final data = await rootBundle.load('assets/wintun.dll');
        await target.writeAsBytes(data.buffer.asUint8List(), flush: true);
        _log('[TUN] wintun.dll распакован: ${target.path}');
      }

      rust.setWintunDllPath(path: target.path);
    } catch (error) {
      _log('[TUN] Не удалось распаковать wintun.dll: $error');
    }
  }

  int _normalizeWorkers(int? requested) => requested ?? 9;

  int _lastGeneration = 0;

  int _nextGeneration() {
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final generation = now > _lastGeneration ? now : _lastGeneration + 1;
    _lastGeneration = generation;
    return generation;
  }

  static String get _tunMode => Platform.isWindows ? 'wintun' : 'tun';

  String _generateSalt() {
    final random = Random.secure();
    return List.generate(
      16,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
  }

  bool _isAdmin() {
    if (!Platform.isWindows) return true;
    try {
      final shell32 = ffi.DynamicLibrary.open('shell32.dll');
      final isUserAnAdmin = shell32
          .lookupFunction<ffi.Int32 Function(), int Function()>(
            'IsUserAnAdmin',
          );
      return isUserAnAdmin() != 0;
    } catch (_) {
      return false;
    }
  }

  static String _cleanError(Object error) {
    final text = error.toString();
    const markers = ['Stack backtrace:', '\nCaused by:'];
    var clean = text;
    for (final marker in markers) {
      final index = clean.indexOf(marker);
      if (index > 0) {
        clean = clean.substring(0, index).trim();
        break;
      }
    }
    return clean.isEmpty ? text : clean;
  }

  @override
  @protected
  void dispose() {
    _coreLogSub?.cancel();
    _connectWatchdog?.cancel();
    _callsManager?.close();
    super.dispose();
  }
}
