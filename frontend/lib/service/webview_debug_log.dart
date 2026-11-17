// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart' show kDebugMode;

/// Временный журнал WebView для отладки входа VK ID.
///
/// Пишет события окна, console/JS-ошибки и ответы `api.vk.*` в файл
/// `wv_debug.log` рядом с исполняемым файлом приложения. Если каталог
/// приложения недоступен для записи (Program Files), используется
/// локальный каталог данных, затем временный.
///
/// Включён только в debug-сборках ([kDebugMode]): обёртки fetch/XHR и
/// глобальные переменные — заметный признак автоматизации для антибота VK,
/// поэтому в Release хук не внедряется. Перед релизом класс можно удалить
/// вместе с вызовами из `VkToken.login`.
class WebViewDebugLog {
  WebViewDebugLog._(this.file, this._raf);

  /// Префикс, по которому сообщения из JS отличаются от прочих.
  static const String messagePrefix = 'WVDBG|';

  static const int _maxLineLength = 4000;

  static WebViewDebugLog? _active;

  static const bool enabled = kDebugMode;

  final File file;
  final RandomAccessFile _raf;

  static Future<WebViewDebugLog?> start(String tag) async {
    if (!enabled) return null;
    await stopActive();

    for (final dir in _candidateDirs()) {
      try {
        await dir.create(recursive: true);
        final file = File('${dir.path}${Platform.pathSeparator}wv_debug.log');
        final raf = await file.open(mode: FileMode.append);
        final log = WebViewDebugLog._(file, raf);
        _active = log;
        log.add('=== ${DateTime.now().toIso8601String()} $tag ===');
        return log;
      } catch (_) {
        // Пробуем следующий каталог.
      }
    }
    return null;
  }

  static Iterable<Directory> _candidateDirs() sync* {
    yield File(Platform.resolvedExecutable).parent;
    final localAppData = Platform.environment['LOCALAPPDATA'];
    if (localAppData != null && localAppData.isNotEmpty) {
      yield Directory('$localAppData${Platform.pathSeparator}FOCSQ');
    }
    yield Directory(
      '${Directory.systemTemp.path}${Platform.pathSeparator}FOCSQ',
    );
  }

  static String? takeMessage(String message) {
    if (!message.startsWith(messagePrefix)) return null;
    return message.substring(messagePrefix.length);
  }

  void add(String message) {
    final text = redact(message);
    final line = text.length > _maxLineLength
        ? '${text.substring(0, _maxLineLength)}...'
        : text;
    try {
      _raf.writeStringSync('${DateTime.now().toIso8601String()} $line\n');
    } catch (_) {
      // Журнал не должен ломать вход.
    }
  }

  Future<void> stop() async {
    if (identical(_active, this)) _active = null;
    try {
      await _raf.close();
    } catch (_) {}
  }

  static Future<void> stopActive() async {
    final active = _active;
    _active = null;
    await active?.stop();
  }

  static final RegExp _secretQuery = RegExp(
    r'''([?&#\s"']|^)'''
    r'''(access_token|auth_token|anonymous_token|refresh_token|id_token'''
    r'''|success_token|payload|code|login|phone|password|sid'''
    r'''|auth_user_hash|hash)'''
    r'''=[^&#\s"']*''',
    caseSensitive: false,
  );

  static final RegExp _secretJson = RegExp(
    r'"(access_token|auth_token|anonymous_token|refresh_token|id_token'
    r'|success_token|payload|code|login|phone|password|sid'
    r'|auth_user_hash|hash)"'
    r'\s*:\s*"[^"]*"',
    caseSensitive: false,
  );

  /// Вырезает секреты из URL/JSON, чтобы журнал можно было пересылать.
  static String redact(String text) {
    return text
        .replaceAllMapped(_secretQuery, (m) => '${m[1]}${m[2]}=***')
        .replaceAllMapped(_secretJson, (m) => '"${m[1]}":"***"');
  }

  /// Скрипт, который внедряется в каждую страницу WebView до её скриптов.
  /// Собирает console, JS-ошибки, падения загрузки ресурсов и ответы
  /// `api.vk.*` (без request-параметров и тел успешных ответов).
  static const String injectedScript = r'''
(function () {
  'use strict';
  if (window.__focsqWvDebug) { return; }
  window.__focsqWvDebug = true;

  var PREFIX = 'WVDBG|';
  var MAX = 2000;

  function send(line) {
    try {
      if (window.chrome && chrome.webview && chrome.webview.postMessage) {
        chrome.webview.postMessage(PREFIX + line);
      }
    } catch (e) {}
  }

  function clip(value) {
    var text;
    if (value === null || value === undefined) { return ''; }
    if (value instanceof Error) {
      text = value.stack || value.message || String(value);
    } else if (typeof value === 'object') {
      try { text = JSON.stringify(value); } catch (e) { text = String(value); }
    } else {
      text = String(value);
    }
    return text.length > MAX ? text.slice(0, MAX) + '...' : text;
  }

  var levels = ['log', 'info', 'warn', 'error', 'debug'];
  for (var i = 0; i < levels.length; i++) {
    (function (level) {
      var original = console[level];
      if (typeof original !== 'function') { return; }
      console[level] = function () {
        try {
          var parts = [];
          for (var j = 0; j < arguments.length; j++) {
            parts.push(clip(arguments[j]));
          }
          send('[console.' + level + '] ' + parts.join(' '));
        } catch (e) {}
        return original.apply(console, arguments);
      };
    })(levels[i]);
  }

  window.addEventListener('error', function (event) {
    try {
      var target = event.target;
      if (target && target !== window && (target.src || target.href)) {
        send('[resource] ' + (target.tagName || 'resource') +
             ' failed: ' + clip(target.src || target.href));
        return;
      }
      var error = event.error;
      send('[js.error] ' + clip(event.message) +
           ' @ ' + clip(event.filename) +
           ':' + (event.lineno || 0) + ':' + (event.colno || 0) +
           (error && error.stack ? '\n' + error.stack : ''));
    } catch (e) {}
  }, true);

  window.addEventListener('unhandledrejection', function (event) {
    try {
      var reason = event.reason;
      send('[js.rejection] ' +
           clip(reason && reason.stack ? reason.stack : reason));
    } catch (e) {}
  });

  function page(kind) {
    try {
      send('[page] ' + kind + ' ' + location.origin + location.pathname +
           ' title=' + clip(document.title));
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', function () {
    page('domcontentloaded');
  });
  window.addEventListener('load', function () { page('load'); });
  window.addEventListener('pagehide', function () { page('pagehide'); });

  function describe(url) {
    try {
      var parsed = new URL(url, location.href);
      return parsed.origin + parsed.pathname;
    } catch (e) {
      return String(url).split('?')[0].split('#')[0];
    }
  }

  function methodName(url) {
    try {
      var path = new URL(url, location.href).pathname;
      return path.indexOf('/method/') === 0 ? path.substring(8) : path;
    } catch (e) {
      return describe(url);
    }
  }

  function isVkApi(url) {
    return typeof url === 'string' && url.indexOf('api.vk.') !== -1;
  }

  function report(url, status, body) {
    try {
      var method = methodName(url);
      if (!isVkApi(url) && status < 400) { return; }
      if (method.indexOf('statEvents.') === 0) { return; }
      var suffix = '';
      if (body) {
        var parsed = null;
        try { parsed = JSON.parse(body); } catch (e) {}
        var error = parsed && parsed.error;
        if (error) {
          suffix = ' error_code=' + clip(error.error_code) +
                   ' error_subcode=' + clip(error.error_subcode) +
                   ' msg=' + clip(error.error_msg) +
                   ' text=' + clip(error.error_text);
        }
      }
      send('[net] ' + describe(url) + ' http=' + status + suffix);
    } catch (e) {}
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function (input, init) {
      var url = '';
      try {
        url = typeof input === 'string' ? input : (input && input.url) || '';
      } catch (e) {}
      var request = originalFetch.apply(this, arguments);
      if (url) {
        request.then(function (response) {
          try {
            if (isVkApi(url)) {
              response.clone().text().then(function (body) {
                report(url, response.status, body);
              }).catch(function () {
                report(url, response.status, '');
              });
            } else if (response.status >= 400) {
              report(url, response.status, '');
            }
          } catch (e) {}
        }).catch(function (error) {
          send('[net] ' + describe(url) + ' failed: ' +
               clip(error && error.message ? error.message : error));
        });
      }
      return request;
    };
  }

  var originalOpen = XMLHttpRequest.prototype.open;
  if (typeof originalOpen === 'function') {
    XMLHttpRequest.prototype.open = function (method, url) {
      try {
        if (isVkApi(url)) {
          this.addEventListener('loadend', function () {
            var body = '';
            try {
              body = this.responseText;
            } catch (e) {
              try {
                body = typeof this.response === 'string'
                  ? this.response
                  : JSON.stringify(this.response);
              } catch (e2) {}
            }
            report(url, this.status, body);
          });
        }
      } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
  }

  function fingerprint() {
    var data = {};
    try {
      data.ua = navigator.userAgent;
      data.lang = navigator.language;
      data.langs = navigator.languages;
      data.platform = navigator.platform;
      data.vendor = navigator.vendor;
      data.cores = navigator.hardwareConcurrency;
      data.memory = navigator.deviceMemory || null;
      data.touch = navigator.maxTouchPoints;
      data.webdriver = navigator.webdriver;
      data.webview = !!(window.chrome && window.chrome.webview);
      data.dpr = window.devicePixelRatio;
      data.screen = [screen.width, screen.height, screen.availWidth,
                     screen.availHeight, screen.colorDepth];
      data.window = [innerWidth, innerHeight, outerWidth, outerHeight];
      data.tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      data.locale = Intl.DateTimeFormat().resolvedOptions().locale;
      data.plugins = navigator.plugins ? navigator.plugins.length : null;
      try {
        var c2 = document.createElement('canvas');
        c2.width = 200;
        c2.height = 40;
        var ctx2 = c2.getContext('2d');
        ctx2.textBaseline = 'top';
        ctx2.font = '14px Arial';
        ctx2.fillStyle = '#f60';
        ctx2.fillRect(0, 0, 100, 20);
        ctx2.fillStyle = '#069';
        ctx2.fillText('FOCSQ fingerprint', 2, 15);
        var pixels = c2.toDataURL();
        var h = 5381;
        for (var i = pixels.length; i > 0; i--) {
          h = ((h * 33) ^ pixels.charCodeAt(i - 1)) >>> 0;
        }
        data.canvas = h.toString(16);
      } catch (e) {}
      try {
        var canvas = document.createElement('canvas');
        var gl = canvas.getContext('webgl');
        if (gl) {
          var ext = gl.getExtension('WEBGL_debug_renderer_info');
          if (ext) {
            data.glVendor = gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);
            data.glRenderer = gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);
          }
        }
      } catch (e) {}
    } catch (e) {}
    send('[fp] ' + JSON.stringify(data));

    try {
      var uaData = navigator.userAgentData;
      if (uaData && uaData.getHighEntropyValues) {
        uaData.getHighEntropyValues([
          'architecture', 'bitness', 'model', 'platformVersion',
          'uaFullVersion', 'fullVersionList', 'wow64'
        ]).then(function (high) {
          send('[fp.ch] ' + JSON.stringify({
            brands: uaData.brands,
            mobile: uaData.mobile,
            platform: uaData.platform,
            high: high
          }));
        }).catch(function () {});
      }
    } catch (e) {}
  }
  fingerprint();

  send('[boot] document created ' + clip(location.href));
})();
''';
}
