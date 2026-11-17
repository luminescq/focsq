// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import '../l10n/app_locale.dart';
import '../l10n/core_line_localizer.dart';

import 'package:flutter/foundation.dart';

class LogService extends ChangeNotifier {
  LogService._();

  static final LogService _instance = LogService._();

  factory LogService() => _instance;

  static const int maxLines = 2000;

  static const int _keyedScanWindow = 200;

  static bool developerMode = false;

  final List<String> _lines = [];

  int _version = 0;

  Timer? _notifyTimer;

  late final UnmodifiableListView<String> lines = UnmodifiableListView(_lines);

  int get version => _version;

  static void setDeveloperMode(bool value) {
    final instance = _instance;
    if (developerMode == value) return;
    developerMode = value;
    if (instance._lines.isEmpty) return;
    instance.clear();
    instance.add(value ? S.devModeLogsOn : S.normalLogsOn);
  }

  static bool isErrorLine(String line) {
    final lower = line.toLowerCase();
    // «Превышен лимит сессии капчи» — штатная пауза, а не ошибка.
    final limit =
        lower.contains('лимит') && !lower.contains('лимит сессии капчи');
    return lower.contains('ошибка') ||
        lower.contains('error') ||
        lower.contains('фатал') ||
        lower.contains('fatal') ||
        lower.contains('failed') ||
        lower.contains('refused') ||
        lower.contains('denied') ||
        lower.contains('timeout') ||
        lower.contains('не удалась') ||
        lower.contains('недоступен') ||
        limit ||
        lower.contains('огранич') ||
        lower.contains('отказ') ||
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
    if (body.startsWith('[WORKER]') || body.startsWith('[ВОРКЕР]')) {
      return 'worker_ready';
    }
    if (body.startsWith('[NET]') || body.startsWith('[СЕТЬ]')) {
      return 'network_stats';
    }
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
      if (body.contains('Решение') ||
          body.contains('решается') ||
          body.contains('Solving')) {
        return 'captcha_progress';
      }
    }
    if (body.startsWith('[ЗВОНКИ]')) {
      if (body.contains('Авторизация VK')) return 'vk_auth';
      if (body.contains('Серверы TURN')) return 'vk_turn';
      if (body.contains('Креды получены')) return 'vk_creds';
    }
    if (body.startsWith('[TUN]')) {
      if (body.contains('Конфиг сети получен') ||
          body.contains('Network config received')) {
        return 'tun_config';
      }
      if (body.contains('Сетевой адаптер настроен') ||
          body.contains('adapter configured')) {
        return 'tun_adapter_ready';
      }
      if (body.contains('Маршрутизация и DNS') ||
          body.contains('Routes and DNS')) {
        return 'tun_routes_ready';
      }
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

    final visible = developerMode ? trimmed : _userLine(trimmed);
    if (visible == null || visible.isEmpty) return;

    final isErr = isErrorLine(visible);
    final stamped = '[${_timestamp()}] $visible';

    if (isErr || _lines.isEmpty) {
      _lines.add(stamped);
      _trimAndNotify();
      return;
    }

    final key = developerMode ? null : _resolveKey(visible);

    if (key != null) {
      final index = _findLineByKey(key);
      if (index != -1) {
        final existing = _lines[index];
        final currentCount = _extractCount(existing) + 1;
        if (key == 'worker_ready' ||
            key.startsWith('turn_') ||
            key == 'vk_auth') {
          _lines[index] = '[${_timestamp()}] $visible (x$currentCount)';
        } else {
          _lines[index] = stamped;
        }
        _trimAndNotify();
        return;
      }
    }

    final last = _lines.last;
    final lastBody = _extractBody(last);
    if (lastBody == visible) {
      final currentCount = _extractCount(last) + 1;
      _lines[_lines.length - 1] = '[${_timestamp()}] $visible (x$currentCount)';
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
    _version++;
    _scheduleNotify();
  }

  static String _timestamp() {
    final now = DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    return '${two(now.hour)}:${two(now.minute)}:${two(now.second)}';
  }

  /// Видимость строки решает старая логика фильтра — она не зависит от языка.
  /// Локализатор ядра вызывается уже ПОСЛЕ неё и переводит только то, что
  /// и так должно попасть в журнал.
  static String? _userLine(String line) {
    final legacy = _legacyUserLine(line);
    if (legacy == null) return null;
    if (LocaleService.isEnglish) {
      final localized = CoreLineLocalizer.localize(line.trim());
      if (localized != null) return localized;
    }
    return legacy;
  }

  static String? _legacyUserLine(String line) {
    final trimmed = line.trim();

    if (trimmed.startsWith('__CSQTT_EVENT__|')) {
      final payload = trimmed.substring('__CSQTT_EVENT__|'.length);
      final pipe = payload.indexOf('|');
      if (pipe < 0) return null;
      final type = payload.substring(0, pipe);
      final jsonStr = payload.substring(pipe + 1);

      if (type == 'READY') {
        return S.workerReadyLine;
      }
      if (type == 'STATS') {
        try {
          final map = jsonDecode(jsonStr) as Map<String, dynamic>;
          final active = (map['active'] as num?)?.toInt() ?? 0;
          final up = (map['bytes_up'] as num?)?.toInt() ?? 0;
          final down = (map['bytes_down'] as num?)?.toInt() ?? 0;
          // [FOCSQ] ↑/↓ раздельно: сумма не показывает, какое направление
          // не течёт (аплинк в TURN или даунлинк из аллокаций).
          final upMb = up / (1024.0 * 1024.0);
          final downMb = down / (1024.0 * 1024.0);
          return S.networkStatsLine(
            active,
            upMb.toStringAsFixed(2),
            downMb.toStringAsFixed(2),
          );
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
            return S.tunConfig(ip, dns);
          }
        } catch (_) {}
        return null;
      }
      if (type == 'SERVER_RESTART') {
        return S.serverRestartedByPanel;
      }
      return null;
    }

    if (trimmed.startsWith('CAPTCHA_SOLVE|')) return null;

    if (trimmed.contains('FATAL') ||
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
      return S.wintunCreatedLine;
    }
    if (trimmed.contains('TUN-адаптер настроен') ||
        trimmed.contains('адаптер настроен')) {
      return S.tunAdapterReadyLine;
    }
    if (trimmed.startsWith('[КЛИЕНТ] Tunnel IP:')) {
      final text = trimmed.substring('[КЛИЕНТ]'.length).trim();
      return '[TUN] $text';
    }
    if (trimmed.startsWith('[TUN] Настроен:')) {
      return S.tunRoutesReadyLine;
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
      return S.vkAuthPassedLine;
    }
    if (trimmed.contains('Звонки не прошли')) {
      final separator = trimmed.indexOf('·');
      final tail = separator > 0 ? trimmed.substring(separator) : '';
      return S.callsFailedLine(tail);
    }

    if (trimmed.startsWith('[КАПЧА]')) {
      final clean = trimmed.replaceAll(RegExp(r'\s*\([^)]*\)'), '').trim();
      if (clean.contains('Smart Captcha решена') || clean.contains('решена')) {
        return S.captchaSolvedLine;
      }
      if (clean.contains('Решаю') || clean.contains('старт')) {
        return S.captchaSolvingLine;
      }
      return clean;
    }

    if (trimmed.startsWith('[TURN]')) {
      if (isErrorLine(trimmed)) {
        return trimmed;
      }
      return null;
    }

    const keepPrefixes = [
      '[CONNECT]',
      '[CORE]',
      '[WINDOW]',
      '[STATUS]',
      '[TRAY]',
      '[VK]',
      '[AUTOSTART]',
      '[SERVICE]',
      '[AUTO API]',
      '[CALLS]',
      // Русские варианты оставлены для сырых строк ядра и совместимости.
      '[КОННЕКТ]',
      '[ЯДРО]',
      '[ОКНО]',
      '[СТАТУС]',
      '[ТРЕЙ]',
      '[ВК]',
      '[АВТОСТАРТ]',
      '[Туннель]',
    ];
    for (final prefix in keepPrefixes) {
      if (trimmed.startsWith(prefix)) return trimmed;
    }

    if (trimmed.startsWith('[ВОРКЕР') || trimmed.startsWith('[Воркер')) {
      if (isErrorLine(trimmed)) {
        return trimmed;
      }
      return S.workerReadyLine;
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
    if (_lines.isEmpty) return;
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
