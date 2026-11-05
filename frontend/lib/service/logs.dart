// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:flutter/foundation.dart';

class _SafeLogRecord {
  const _SafeLogRecord(this.line, this.timestamp);
  final String line;
  final String timestamp;
}

class LogService extends ChangeNotifier {
  LogService._();

  static final LogService _instance = LogService._();

  factory LogService() => _instance;

  static const int maxLines = 2000;

  static const int _keyedScanWindow = 200;

  static bool developerMode = false;

  final List<String> _lines = [];
  final List<_SafeLogRecord> _rawRecords = [];
  bool _replaying = false;
  void Function(String)? _diagnosticWriter;
  Future<void> Function()? _diagnosticClose;
  Future<void>? _diagnosticClosing;
  String? _diagnosticFilePath;

  String? get diagnosticFilePath => _diagnosticFilePath;

  /// The writer receives every safe raw event, including hidden/repeated events.
  void configureDiagnosticOutput({
    required String? filePath,
    required void Function(String)? writer,
    Future<void> Function()? close,
  }) {
    _diagnosticFilePath = filePath;
    _diagnosticWriter = writer;
    _diagnosticClose = close;
    _diagnosticClosing = null;
    notifyListeners();
  }

  /// Detach output and finish its pending records before the app exits.
  Future<void> closeDiagnosticOutput() {
    final closing = _diagnosticClosing;
    if (closing != null) return closing;
    final close = _diagnosticClose;
    _diagnosticWriter = null;
    _diagnosticClose = null;
    if (close == null) return Future<void>.value();
    return _diagnosticClosing = _finishDiagnosticOutput(close);
  }

  Future<void> _finishDiagnosticOutput(Future<void> Function() close) async {
    try {
      await close();
    } catch (error) {
      add('[ЖУРНАЛ] Ошибка завершения подробного лога (${error.runtimeType})');
    }
  }

  /// Apply once at ingress, before developer mode, the raw ring, or file output.
  static String redactSecrets(String line) {
    var safe = line.replaceAllMapped(
      RegExp(r'\b(GETCONF|DISCONNECT):[^\r\n]+', caseSensitive: false),
      (match) => '${match.group(1)}:[REDACTED]',
    );
    safe = safe.replaceAllMapped(
      RegExp(r'(CAPTCHA_SOLVE|CAPTCHA_RESULT)\|[^\r\n]+', caseSensitive: false),
      (match) => '${match.group(1)}|[REDACTED]',
    );
    safe = safe.replaceAllMapped(
      RegExp(r'^\[КРЕД[^\]]*\].*$', caseSensitive: false, multiLine: true),
      (_) => '[КРЕДЫ] [REDACTED]',
    );
    safe = safe.replaceAllMapped(
      RegExp(
        r'([a-z][a-z0-9+.-]*://)[^\s/@]+(?::[^\s/@]*)?@',
        caseSensitive: false,
      ),
      (match) => '${match.group(1)}[REDACTED]@',
    );
    safe = safe.replaceAllMapped(
      RegExp(r'\bBearer\s+[^\s,;"\x27]+', caseSensitive: false),
      (_) => 'Bearer [REDACTED]',
    );
    const keys =
        r'(?:[a-z0-9]+_)*(?:password|passwd|pass|access_token|accesstoken|refresh_token|refreshtoken|id_token|idtoken|anonymtoken|token|credential|credentials|username|user|secret|private_key|wrap_key|key|hash|hashes|joinlink|link|sid)';
    safe = safe.replaceAllMapped(
      RegExp(
        r'''(["']?\b''' + keys + r'''\b["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\[[^\]]*\]|[^\s&,;|}\]]+)''',
        caseSensitive: false,
      ),
      (match) {
        final value = match.group(2)!;
        final marker = value.startsWith('"')
            ? '"[REDACTED]"'
            : value.startsWith("'")
            ? "'[REDACTED]'"
            : value.startsWith('[')
            ? '["[REDACTED]"]'
            : '[REDACTED]';
        return '${match.group(1)}$marker';
      },
    );
    safe = safe.replaceAllMapped(
      RegExp(
        r'((?:access_token|refresh_token|id_token|anonymtoken|token|password|key|hash|joinlink|link|sid)%3d)(?:(?!%26|%23)[^\s&#])*',
        caseSensitive: false,
      ),
      (match) => '${match.group(1)}[REDACTED]',
    );
    safe = safe.replaceAllMapped(
      RegExp(
        r'''((?:https?://)?(?:www\.)?vk\.com/call/join/)[^\s?&#"'<>]+''',
        caseSensitive: false,
      ),
      (match) => '${match.group(1)}[REDACTED]',
    );
    safe = safe.replaceAllMapped(
      RegExp(
        r'''((?:https?%3a%2f%2f)?(?:www\.)?vk\.com%2fcall%2fjoin%2f)[^\s&#"'<>]+''',
        caseSensitive: false,
      ),
      (match) => '${match.group(1)}[REDACTED]',
    );
    if (safe.contains('FATAL_HANDSHAKE')) {
      safe = safe.replaceAll(
        'проверьте пароль подключения и совместимость клиента с сервером',
        'причина отсутствия ответа пока не установлена',
      );
    }
    return safe;
  }

  int _version = 0;

  Timer? _notifyTimer;

  late final UnmodifiableListView<String> lines = UnmodifiableListView(_lines);

  int get version => _version;

  static void setDeveloperMode(bool value) {
    final instance = _instance;
    if (developerMode == value) return;
    developerMode = value;
    // Re-render the safe raw ring; toggling detail must not erase evidence.
    instance._lines.clear();
    instance._replaying = true;
    try {
      for (final record in instance._rawRecords) {
        instance._appendVisible(record.line, record.timestamp);
      }
    } finally {
      instance._replaying = false;
    }
    instance._version++;
    instance.notifyListeners();
  }

  static bool isErrorLine(String line) {
    final lower = line.toLowerCase();
    final stage = RegExp(r'\bstage=([a-z0-9_]+)').firstMatch(lower)?.group(1);
    // [FOCSQ] У прямого split relay те же этапы и ненулевые счётчики ошибок.
    if (stage != null && RegExp(r'\[(?:turn|handshake|split)\]').hasMatch(lower)) {
      if (RegExp(
            r'(?:^|_)(?:error|failed|failure|timeout|denied|refused|reject|rejected)(?:_|$)',
          ).hasMatch(stage) ||
          stage == 'no_config') {
        return true;
      }
      final errorValues = RegExp(
        r'\b(?:[a-z0-9]+_)*(?:errors?|failed|failures?|drops?)\s*=\s*(true|false|[+-]?\d+(?:\.\d+)?)(?=$|[\s,;])',
      );
      for (final match in errorValues.allMatches(lower)) {
        final value = match.group(1)!;
        if (value == 'true' || (num.tryParse(value) ?? 0) > 0) return true;
      }
      return RegExp(r'\bfatal(?:_|\b)|✗').hasMatch(lower);
    }
    return lower.contains('ошибка') ||
        lower.contains('error') ||
        lower.contains('фатал') ||
        lower.contains('fatal') ||
        lower.contains('failed') ||
        lower.contains('refused') ||
        lower.contains('timeout') ||
        lower.contains('таймаут') ||
        lower.contains('тайм-аут') ||
        lower.contains('нет ответа') ||
        lower.contains('не подтвердил') ||
        lower.contains('не удалась') ||
        lower.contains('недоступен') ||
        lower.contains('✗');
  }

  static final _timestampRegex = RegExp(r'^\[\d{2}:\d{2}:\d{2}\]\s*');
  static final _countRegex = RegExp(r'\s*[\(\[]x\d+[\)\]]\s*$');

  static String _extractBody(String line) {
    var s = line.replaceFirst(_timestampRegex, '').trim();
    s = s.replaceFirst(_countRegex, '').trim();
    return s;
  }

  static int _extractCount(String line) {
    final match = RegExp(r'[\(\[]x(\d+)[\)\]]\s*$').firstMatch(line);
    if (match != null) {
      return int.tryParse(match.group(1)!) ?? 1;
    }
    return 1;
  }

  static String? _resolveKey(String body) {
    if (body.contains('FATAL_HANDSHAKE')) {
      final peer = RegExp(r'\bpeer=([^\s,;]+)').firstMatch(body)?.group(1);
      var cause = body.substring(
        body.indexOf('FATAL_HANDSHAKE') + 'FATAL_HANDSHAKE'.length,
      );
      cause = cause
          .replaceAll(
            RegExp(r'\bworker\s*=\s*\d+', caseSensitive: false),
            'worker=*',
          )
          .replaceAll(RegExp(r'\s+'), ' ')
          .trim();
      return 'handshake_timeout:${peer ?? "current"}:$cause';
    }
    if (body.startsWith('[ВОРКЕР] Поток готов')) return 'worker_ready';
    if (body.startsWith('[СЕТЬ]')) return 'network_stats';
    if (body.startsWith('[TURN]')) {
      if (body.contains('CreatePermission')) return 'turn_permission';
      if (body.contains('ChannelBind')) return 'turn_channel';
      if (body.contains('Сессия готова') ||
          body.contains('готова к передаче')) {
        return 'turn_ready';
      }
      if (body.contains('Refresh аллокации')) return 'turn_refresh';
      if (body.contains('Аллокация активна')) return 'turn_alloc';
    }
    if (body.startsWith('[КАПЧА]')) {
      if (body.contains('Решение') || body.contains('решается')) {
        return 'captcha_progress';
      }
    }
    if (body.startsWith('[ЗВОНКИ]')) {
      if (body.contains('Авторизация VK')) return 'vk_auth';
      if (body.contains('Серверы TURN')) return 'vk_turn';
      if (body.contains('Креды получены')) return 'vk_creds';
    }
    if (body.startsWith('[TUN]')) {
      if (body.contains('Конфиг сети получен')) return 'tun_config';
      if (body.contains('Сетевой адаптер настроен')) return 'tun_adapter_ready';
      if (body.contains('Маршрутизация и DNS')) return 'tun_routes_ready';
    }
    return null;
  }

  int _findLineByKey(String key) {
    final start = _lines.length > _keyedScanWindow
        ? _lines.length - _keyedScanWindow
        : 0;
    for (var i = _lines.length - 1; i >= start; i--) {
      final body = _extractBody(_lines[i]);
      if (_resolveKey(body) == key) {
        return i;
      }
    }
    return -1;
  }

  void add(String line) {
    final trimmed = line.trim();
    if (trimmed.isEmpty) return;
    final safe = redactSecrets(trimmed);
    final timestamp = _timestamp();
    _rawRecords.add(_SafeLogRecord(safe, timestamp));
    if (_rawRecords.length > maxLines) {
      _rawRecords.removeRange(0, _rawRecords.length - maxLines);
    }
    final writer = _diagnosticWriter;
    if (writer != null) {
      try {
        writer('[${DateTime.now().toUtc().toIso8601String()}] $safe');
      } catch (error) {
        // A failing disk sink must not break connection handling or recurse.
        _diagnosticWriter = null;
        final failure =
            '[ЛОГ] Ошибка записи подробного журнала (${error.runtimeType})';
        _rawRecords.add(_SafeLogRecord(failure, timestamp));
        if (_rawRecords.length > maxLines) _rawRecords.removeAt(0);
        _appendVisible(failure, timestamp);
      }
    }
    _appendVisible(safe, timestamp);
  }

  void _appendVisible(String safe, String timestamp) {
    final visible = developerMode ? safe : _userLine(safe);
    if (visible == null || visible.isEmpty) return;
    final stamped = '[$timestamp] $visible';
    final key = developerMode ? null : _resolveKey(visible);

    if (key != null && key.startsWith('handshake_timeout:')) {
      final index = _findLineByKey(key);
      if (index != -1) {
        final existing = _lines[index];
        final count = _extractCount(existing) + 1;
        // Retain the first worker and its peer/transport metadata as the sample.
        _lines[index] = '[$timestamp] ${_extractBody(existing)} (x$count)';
        _trimAndNotify();
        return;
      }
    }

    if (isErrorLine(visible) || _lines.isEmpty) {
      _lines.add(stamped);
      _trimAndNotify();
      return;
    }

    if (key != null) {
      final index = _findLineByKey(key);
      if (index != -1) {
        final count = _extractCount(_lines[index]) + 1;
        if (key == 'worker_ready' ||
            key.startsWith('turn_') ||
            key == 'vk_auth') {
          _lines[index] = '[$timestamp] $visible (x$count)';
        } else {
          _lines[index] = stamped;
        }
        _trimAndNotify();
        return;
      }
    }

    if (_extractBody(_lines.last) == visible) {
      final count = _extractCount(_lines.last) + 1;
      _lines[_lines.length - 1] = '[$timestamp] $visible (x$count)';
      _trimAndNotify();
      return;
    }
    _lines.add(stamped);
    _trimAndNotify();
  }

  void _trimAndNotify() {
    if (_lines.length > maxLines) {
      _lines.removeRange(0, _lines.length - maxLines);
    }
    if (_replaying) return;
    _version++;
    _scheduleNotify();
  }

  static String _timestamp() {
    final now = DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    return '${two(now.hour)}:${two(now.minute)}:${two(now.second)}';
  }

  static String? _userLine(String line) {
    final trimmed = line.trim();

    if (trimmed.startsWith('__CSQTT_EVENT__|')) {
      final payload = trimmed.substring('__CSQTT_EVENT__|'.length);
      final pipe = payload.indexOf('|');
      if (pipe < 0) return null;
      final type = payload.substring(0, pipe);
      final jsonStr = payload.substring(pipe + 1);

      if (type == 'READY') {
        return '[ВОРКЕР] Поток готов ✓';
      }
      if (type == 'STATS') {
        try {
          final map = jsonDecode(jsonStr) as Map<String, dynamic>;
          final active = (map['active'] as num?)?.toInt() ?? 0;
          final up = (map['bytes_up'] as num?)?.toInt() ?? 0;
          final down = (map['bytes_down'] as num?)?.toInt() ?? 0;
          final totalMb = (up + down) / (1024.0 * 1024.0);
          return '[СЕТЬ] Активных потоков: $active | Трафик: ${totalMb.toStringAsFixed(2)} МБ';
        } catch (_) {
          return null;
        }
      }
      if (type == 'PROGRESS') {
        return null;
      }
      if (type == 'CONFIG') {
        try {
          final map = jsonDecode(jsonStr) as Map<String, dynamic>;
          final configVal = map['config']?.toString() ?? '';
          if (configVal.startsWith('TUNCONF:')) {
            final conf = configVal.replaceFirst('TUNCONF:', '').trim();
            final parts = conf.split(':');
            final ip = parts.isNotEmpty ? parts[0] : '';
            final dns = parts.length > 1 ? parts[1] : '';
            return '[TUN] Конфиг сети получен: $ip (DNS: $dns)';
          }
        } catch (_) {}
        return null;
      }
      if (type == 'SERVER_RESTART') {
        return '[СЕРВЕР] Сервер перезапущен панелью';
      }
      return null;
    }

    if (trimmed.startsWith('CAPTCHA_SOLVE|')) return null;

    if (trimmed.contains('FATAL_AUTH') ||
        trimmed.contains('FATAL_LIMIT') ||
        trimmed.contains('[ФАТАЛ]') ||
        trimmed.contains('[ОШИБКА]') ||
        trimmed.contains('невосстановимая') ||
        trimmed.contains('panic') ||
        trimmed.contains('PANIC')) {
      return trimmed;
    }

    if (trimmed.contains('Exclude-маршрут') ||
        trimmed.contains('Exclude-подсеть') ||
        (trimmed.startsWith('[TUN]') &&
            trimmed.contains('перехвата ещё нет'))) {
      return null;
    }
    if (trimmed.contains('Wintun-адаптер создан') ||
        trimmed.contains('wintun-адаптер создан') ||
        trimmed.contains('Wintun создан')) {
      return '[TUN] Wintun-адаптер создан ✓';
    }
    if (trimmed.contains('TUN-адаптер настроен') ||
        trimmed.contains('адаптер настроен')) {
      return '[TUN] Сетевой адаптер настроен (IP/DNS/маршруты) ✓';
    }
    if (trimmed.startsWith('[КЛИЕНТ] Tunnel IP:')) {
      final text = trimmed.substring('[КЛИЕНТ]'.length).trim();
      return '[TUN] $text';
    }
    if (trimmed.startsWith('[TUN] Настроен:')) {
      return '[TUN] Маршрутизация и DNS применены ✓';
    }
    if (trimmed.startsWith('[TUN]')) {
      return trimmed;
    }

    if (trimmed.startsWith('[КЛИЕНТ] Слушаю:')) {
      return trimmed;
    }
    if (trimmed.startsWith('[КЛИЕНТ] Воркеров:')) {
      return trimmed;
    }
    if (trimmed.startsWith('[КЛИЕНТ] Протокол:')) {
      return trimmed;
    }
    if (trimmed.startsWith('[WRAP]')) {
      return null;
    }

    if (trimmed.startsWith('[VK JS]')) {
      if (trimmed.contains('Звонок VK создан') ||
          trimmed.contains('Владелец вышел')) {
        return trimmed;
      }
      return null;
    }
    if (trimmed.startsWith('[АВТО API]')) {
      return trimmed;
    }
    if (trimmed.startsWith('[ЗВОНКИ]')) {
      if (trimmed.contains('Запрос параметров') ||
          trimmed.contains('Инициализация') ||
          trimmed.contains('Креды получены')) {
        return null;
      }
      return trimmed;
    }
    if (trimmed.startsWith('[КРЕД')) {
      return null;
    }
    if (trimmed.contains('[VKCalls] Identity - Name:')) {
      return null;
    }
    if (trimmed.contains('Success via VK Calls') ||
        trimmed.contains('[VKCalls] SUCCESS')) {
      return '[ЗВОНКИ] Авторизация VK пройдена ✓';
    }
    if (trimmed.contains('Звонки не прошли')) {
      final separator = trimmed.indexOf('·');
      final tail = separator > 0 ? trimmed.substring(separator) : '';
      return '[ЗВОНКИ] Звонки не прошли $tail'.trim();
    }

    if (trimmed.startsWith('[КАПЧА]')) {
      final clean = trimmed.replaceAll(RegExp(r'\s*\([^)]*\)'), '').trim();
      if (clean.contains('Smart Captcha решена') || clean.contains('решена')) {
        return '[КАПЧА] Капча успешно решена ✓';
      }
      if (clean.contains('Решаю') || clean.contains('старт')) {
        return '[КАПЧА] Решение капчи...';
      }
      return clean;
    }

    if (trimmed.startsWith('[TURN]') ||
        trimmed.startsWith('[HANDSHAKE]') ||
        trimmed.startsWith('[GETCONF]') ||
        trimmed.startsWith('[TRANSPORT]')) {
      return trimmed;
    }

    const keepPrefixes = [
      // [FOCSQ] Правила, отказы определения EXE и ошибки direct видны в обычном журнале.
      '[SPLIT]',
      '[КОННЕКТ]',
      '[ЯДРО]',
      '[ОКНО]',
      '[Туннель]',
      '[СТАТУС]',
      '[ТРЕЙ]',
      '[ВК]',
      '[АВТОСТАРТ]',
      '[ЛОГ]',
      '[ЖУРНАЛ]',
      '[ДИАГНОСТИКА]',
    ];
    for (final prefix in keepPrefixes) {
      if (trimmed.startsWith(prefix)) return trimmed;
    }

    if (trimmed.startsWith('[ВОРКЕР') || trimmed.startsWith('[Воркер')) {
      if (isErrorLine(trimmed)) {
        return trimmed;
      }
      if (trimmed.contains('Поток готов')) return '[ВОРКЕР] Поток готов ✓';
      return trimmed;
    }

    return null;
  }

  void addAll(Iterable<String> newLines) {
    for (final line in newLines) {
      add(line);
    }
  }

  void clear() {
    _notifyTimer?.cancel();
    _notifyTimer = null;
    if (_lines.isEmpty && _rawRecords.isEmpty) return;
    _rawRecords.clear();
    _lines.clear();
    _version++;
    notifyListeners();
  }

  void _scheduleNotify() {
    _notifyTimer ??= Timer(const Duration(milliseconds: 50), () {
      _notifyTimer = null;
      notifyListeners();
    });
  }
}
