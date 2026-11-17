// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';

import '../l10n/app_locale.dart';
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
import 'worker_limits.dart';
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
    // [FOCSQ] Watchdog «Отключение…»: ядро может не дойти до STOPPED
    // (зависшая финализация/задача) — без таймера UI залипал навсегда.
    if (value != ConnectState.disconnecting) {
      _disconnectWatchdog?.cancel();
      _disconnectWatchdog = null;
    } else {
      _armDisconnectWatchdog();
    }
    switch (value) {
      case ConnectState.connecting:
        _log(S.logConnecting);
      case ConnectState.connected:
        _log(S.logConnected(_stats.activeWorkers));
      case ConnectState.disconnecting:
        _log(S.logDisconnecting);
      case ConnectState.disconnected:
        if (previous != ConnectState.disconnected) {
          _log(S.logDisconnected);
        }
    }
    notifyListeners();
  }

  Timer? _connectWatchdog;
  static const Duration _connectWatchdogTimeout = Duration(seconds: 60);

  Timer? _disconnectWatchdog;
  static const Duration _disconnectWatchdogTimeout = Duration(seconds: 30);

  void _armDisconnectWatchdog() {
    _disconnectWatchdog?.cancel();
    _disconnectWatchdog = Timer(
      _disconnectWatchdogTimeout,
      _onDisconnectTimeout,
    );
  }

  Future<void> _onDisconnectTimeout() async {
    if (_state != ConnectState.disconnecting) return;
    _disconnectWatchdog = null;
    _log(S.logCoreNoStop(_disconnectWatchdogTimeout.inSeconds));
    _coreRun = null;
    _clientRunning = false;
    _tunReady = false;
    _stats = const ConnectStats();
    _setState(ConnectState.disconnected);
  }

  void _restartConnectWatchdog() {
    if (_state != ConnectState.connecting) return;
    _connectWatchdog?.cancel();
    _connectWatchdog = Timer(_connectWatchdogTimeout, _onConnectTimeout);
  }

  Future<void> _onConnectTimeout() async {
    if (_state != ConnectState.connecting) return;
    _connectWatchdog = null;
    _lastError = S.connectWatchdog(_connectWatchdogTimeout.inSeconds);
    _log('[CONNECT] ${_lastError!}');

    await stop();
    await waitForExit();
    if (_state != ConnectState.disconnected) {
      _log(S.logCoreNoCancel);
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
        onError: (Object error) => _log(S.logLogStreamError(error)),
        onDone: () => _log(S.logLogStreamClosed),
      );
      _rustReady = true;
      _log(S.logBridgeReady);
    } on Object catch (e) {
      throw Exception(_cleanError(e));
    }
  }

  Future<String?> toggle() async {
    final error = await (_state == ConnectState.disconnected
        ? start()
        : stop());
    if (error != null) _log('[CONNECT] $error');
    return error;
  }

  Future<String?> start() async {
    if (_state != ConnectState.disconnected) return null;

    LogService().clear();

    _lastError = null;
    _tunReady = false;
    // Поколение запуска: stop()/watchdog инкрементируют счётчик, и незрелый
    // _startInternal после каждого await понимает, что его отменили.
    final attempt = ++_startAttempt;
    _setState(ConnectState.connecting);
    _restartConnectWatchdog();

    try {
      final error = await _startInternal(attempt);
      if (error != null) {
        _setState(ConnectState.disconnected);
      }
      return error;
    } catch (e) {
      _setState(ConnectState.disconnected);
      final msg = S.errStartFailed(e);
      _log('[CONNECT] $msg');
      return msg;
    }
  }

  Future<String?> _startInternal(int attempt) async {
    await _ensureRust();
    if (attempt != _startAttempt) return null;

    final settings = await DbService.getSettings();
    final profile = await DbService.getActiveProfile(settings);
    if (profile == null) {
      return S.errNoProfile;
    }

    final ip = profile.ip?.trim() ?? '';
    final port = profile.port;
    final password = profile.password?.trim() ?? '';
    if (ip.isEmpty || !ImportService.isValidPort(port)) {
      return S.errProfileIncomplete(profile.name);
    }
    if (password.isEmpty) {
      return S.errNoPassword(profile.name);
    }

    if (Platform.isWindows) {
      if (!_isAdmin()) {
        return S.errAdminRequired;
      }
    } else if (Platform.isLinux) {
      final issue = await TunRights.ensureForConnect();
      if (issue != null) {
        _setState(ConnectState.disconnected);
        return issue;
      }
    }
    if (attempt != _startAttempt) return null;

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
        return S.errVkTokenIssue(vk.issue!);
      }
      vkToken = vk.token;
      if (vkToken == null || vkToken.isEmpty) {
        return S.errVkLoginRequired;
      }
    }

    var vkValue = settings.customHashes.trim();
    var allowRedistribution = false;
    if (hashMode == 'manual' && vkValue.isEmpty) {
      return S.errManualHashesRequired;
    }

    VkAutoCallsManager? manager;
    if (hashMode == 'auto_api') {
      manager = VkAutoCallsManager();
      final workers = WorkerLimits.normalizeCount(profile.power);
      final batch = await manager.createForWorkers(
        token: vkToken!,
        workers: workers,
        onProgress: (created, total) => _log(S.logCallCreated(created, total)),
      );
      if (batch == null) {
        manager.close();

        if (manager.tokenInvalidSeen) {
          await VkToken.delete();
          return S.errVkTokenInvalid;
        }
        return S.errVkCallsCreateFailed;
      }
      vkValue = batch.hashes.join(',');
      allowRedistribution = batch.needsWorkerRedistribution;
      _autoCallIds
        ..clear()
        ..addAll(batch.callIds);
      _autoApiToken = vkToken;
      _callsManager = manager;
    }

    // Долгий await (создание звонков) мог быть отменён stop()/watchdog.
    if (attempt != _startAttempt) {
      await _finishAutoApiCalls();
      return null;
    }

    final workers = _workersForProfile(profile.power, hashMode, vkValue);
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
      domainRouting: settings.zDomainRouting,
      excludeRuZones: settings.zExcludeRuZones,
      templateDomains: settings.zTemplateDomains,
      domainWhitelist: settings.zDomainWhitelist,
      directDomains: settings.zDirectDomains,
      ipRouting: settings.zIpRouting,
      ipWhitelist: settings.zIpWhitelist,
      directIps: settings.zDirectIps,
    );

    if (Platform.isWindows) {
      await _ensureWintunDll();
    }

    // Последний барьер: без него stop() во время долгого await запустил бы
    // ядро, когда UI уже показал «Отключено».
    if (attempt != _startAttempt) {
      await _finishAutoApiCalls();
      return null;
    }

    _clientRunning = true;

    _coreRun = rust
        .startCsqttClient(config: config)
        .then((_) => _onCoreExited(null))
        .catchError(_onCoreExited);
    return null;
  }

  Future<String?> stop() async {
    if (_coreRun == null) {
      // Ядро ещё не запущено, но start() может висеть в ожидании (Авто API,
      // pkexec) — отменяем поколение запуска и возвращаем UI в исходное.
      if (_state == ConnectState.connecting) {
        _startAttempt++;
        _connectWatchdog?.cancel();
        _connectWatchdog = null;
        _setState(ConnectState.disconnected);
      }
      return null;
    }
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

    CaptchaService.abort(S.clientStoppedReason);

    if (error != null) {
      _lastError = _cleanError(error);
      _log('[CORE] ${_lastError!}');
    } else {
      _log(S.logCoreStopped);
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

    _log(S.logFinishingCalls(ids.length));
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
        _log(S.logCoreError(fatal, code, message));
        if (fatal) {
          _lastError = message.isEmpty ? S.errCoreGeneric : message;
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
          _log(S.tunConfig(ip, dns));
          _tunReady = true;
          if (_clientRunning &&
              _stats.activeWorkers > 0 &&
              _state != ConnectState.disconnecting) {
            _setState(ConnectState.connected);
          }
        }

      case 'SERVER_RESTART':
        _log(S.logServerRestart);

      case 'CALL_UNAVAILABLE':
        // Ядро дедуплицирует по хешу, поэтому каждая строка — новый дохлый хеш.
        // Сам хеш не выводим: это идентификатор звонка, не для логов.
        final code = (payload['code'] as num?)?.toInt() ?? 0;
        _log(S.callUnavailableLine(code));

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
        _log('${S.wintunExtracted}${target.path}');
      }

      rust.setWintunDllPath(path: target.path);
    } catch (error) {
      _log(S.wintunExtractFailed(error));
    }
  }

  /// Число потоков, которое реально примет ядро: кратно 10, не больше 100.
  /// В ручном режиме число хешей больше не ограничивает максимум: ядро 2.5.0
  /// добавляет credential-когорты на тот же хеш.
  int _workersForProfile(int? requested, String hashMode, String vkValue) {
    var workers = WorkerLimits.normalizeCount(requested);
    if (hashMode != 'manual') return workers;

    final hashCount = vkValue
        .split(',')
        .where((hash) => hash.trim().isNotEmpty)
        .length;
    final maxByHashes = WorkerLimits.maxForHashes(hashCount);
    if (workers > maxByHashes) {
      _log(S.logWorkersCapped(workers, maxByHashes));
      workers = maxByHashes;
    }
    return workers;
  }

  int _lastGeneration = 0;

  /// Счётчик попыток запуска: растёт на каждый start() и на каждую отмену
  /// (stop()/watchdog), чтобы незрелый _startInternal прекращался сам.
  int _startAttempt = 0;

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
