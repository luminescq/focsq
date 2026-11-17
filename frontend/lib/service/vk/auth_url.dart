// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

/// Разбор URL, на который VK возвращает результат OAuth.
///
/// access_token приходит во фрагменте (`#access_token=...`), но VK ID
/// может положить параметры и в query, а хост редиректа отличается:
/// oauth.vk.ru, oauth.vk.com, id.vk.ru, m.vk.com. Поэтому хост только
/// проверяется на принадлежность VK, а параметры ищутся в обеих частях.
class VkAuthCapture {
  const VkAuthCapture({
    required this.host,
    required this.path,
    required this.keys,
    this.accessToken,
    this.code,
    this.error,
    this.errorDescription,
    this.silentToken = false,
    this.expiresIn,
    this.userId,
  });

  final String host;
  final String path;

  /// Все имена параметров из query и фрагмента — только имена, для журнала.
  final List<String> keys;

  final String? accessToken;
  final String? code;
  final String? error;
  final String? errorDescription;
  final bool silentToken;
  final String? expiresIn;
  final String? userId;

  bool get hasToken => accessToken != null && accessToken!.isNotEmpty;

  bool get hasCode => code != null && code!.isNotEmpty;

  bool get hasError => error != null && error!.isNotEmpty;

  /// Сводка для журнала. Значения секретов сюда не попадают.
  String get debugSummary {
    final parts = <String>[
      '$host$path',
      'keys=[${keys.join(',')}]',
      if (expiresIn != null) 'expires_in=$expiresIn',
      if (userId != null) 'user_id=$userId',
    ];
    return parts.join(' ');
  }
}

/// Снимает JSON-кавычки и экранирование, которые возвращает
/// `evaluateJavaScript` плагина (`"https:\/\/..."`).
String unwrapEvaluatedUrl(String raw) {
  var value = raw.trim();
  if (value.length >= 2) {
    final first = value[0];
    final last = value[value.length - 1];
    if ((first == '"' && last == '"') || (first == "'" && last == "'")) {
      value = value.substring(1, value.length - 1);
    }
  }
  return value.replaceAll(r'\"', '"').replaceAll(r'\/', '/');
}

/// Хосты, на которые VK редиректит после входа.
bool isVkHost(String host) {
  final value = host.toLowerCase();
  return value == 'vk.com' ||
      value.endsWith('.vk.com') ||
      value == 'vk.ru' ||
      value.endsWith('.vk.ru');
}

/// Возвращает null, если URL не принадлежит VK или в нём нет параметров.
VkAuthCapture? parseVkAuthUrl(String raw) {
  final value = unwrapEvaluatedUrl(raw);
  final uri = Uri.tryParse(value);
  if (uri == null || uri.host.isEmpty || !isVkHost(uri.host)) return null;

  final hashAt = value.indexOf('#');
  final beforeFragment = hashAt >= 0 ? value.substring(0, hashAt) : value;
  final fragment = hashAt >= 0 ? value.substring(hashAt + 1) : '';

  final queryAt = beforeFragment.indexOf('?');
  final query = queryAt >= 0 ? beforeFragment.substring(queryAt + 1) : '';

  final params = <String, String>{};
  _readPairs(query, params);
  // Фрагмент читаем вторым: при совпадении имени он приоритетнее.
  _readPairs(fragment, params);
  if (params.isEmpty) return null;

  final payload = params['payload'];

  return VkAuthCapture(
    host: uri.host,
    path: uri.path,
    keys: params.keys.toList(growable: false),
    accessToken: _nonEmpty(params['access_token']),
    code: _nonEmpty(params['code']),
    error: _nonEmpty(params['error']) ?? _nonEmpty(params['error_code']),
    // error_description — текст для человека, VK кодирует пробелы как '+'.
    errorDescription:
        _text(params['error_description']) ?? _text(params['error_msg']),
    silentToken:
        params.containsKey('silent_token') ||
        _looksSilent(payload) ||
        _looksSilent(params['type']) ||
        _looksSilent(fragment),
    expiresIn: _nonEmpty(params['expires_in']),
    userId: _nonEmpty(params['user_id']),
  );
}

void _readPairs(String raw, Map<String, String> out) {
  if (raw.isEmpty) return;
  for (final part in raw.split('&')) {
    if (part.isEmpty) continue;
    final eq = part.indexOf('=');
    final key = _decode(eq < 0 ? part : part.substring(0, eq));
    if (key.isEmpty) continue;
    out[key] = _decode(eq < 0 ? '' : part.substring(eq + 1));
  }
}

String _decode(String value) {
  try {
    return Uri.decodeComponent(value);
  } catch (_) {
    return value;
  }
}

String? _nonEmpty(String? value) {
  final trimmed = value?.trim();
  return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
}

/// Человекочитаемое значение: '+' → пробел (form-encoding VK).
String? _text(String? value) => _nonEmpty(value)?.replaceAll('+', ' ');

/// `type=silent_token`, `"type":"silent_token"`, `\"type\":\"silent_token\"`.
final RegExp _silentType = RegExp(
  r'''\\?["']?type\\?["']?\s*[:=]\s*\\?["']?silent_token''',
  caseSensitive: false,
);

bool _looksSilent(String? value) =>
    value != null && _silentType.hasMatch(value);
