// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

/// Ошибка VK, которую JS-хук WebView присылает строкой `[net]`.
///
/// Формат строки:
/// `[net] https://api.vk.ru/method/auth.getOauthToken http=200
///  error_code=5 error_subcode= msg=User authorization failed: user is
///  blocked. text=`
class VkNetIssue {
  const VkNetIssue({
    required this.code,
    this.subcode,
    this.message = '',
    this.text = '',
  });

  /// `error.error_code` из ответа VK.
  final int code;

  /// `error.error_subcode`; `null`, если VK его не прислал.
  final int? subcode;

  /// `error.error_msg` — технический текст VK (английский).
  final String message;

  /// `error.error_text` — текст для человека (может быть пустым).
  final String text;

  /// Для UI важнее человеческий текст, иначе показываем `error_msg`.
  String get detail => text.isNotEmpty ? text : message;

  bool get isFloodControl => code == 9;

  bool get isPasskeyRequired => code == 5 && subcode == 1108;

  bool get isAccountBlocked =>
      code == 5 && '$message $text'.toLowerCase().contains('user is blocked');
}

/// Возвращает `null`, если строка не про ошибку VK.
///
/// Понимает и новый формат (`error_code`/`error_subcode`), и старый
/// (`code`/`subcode`) — на случай журналов от предыдущих сборок.
VkNetIssue? parseVkNetIssue(String line) {
  if (!line.startsWith('[net]')) return null;
  final code = _intField(line, 'error_code') ?? _intField(line, 'code');
  if (code == null) return null;
  return VkNetIssue(
    code: code,
    subcode: _intField(line, 'error_subcode') ?? _intField(line, 'subcode'),
    message: _textField(line, 'msg') ?? '',
    text: _textField(line, 'text') ?? '',
  );
}

int? _intField(String line, String name) {
  final match = RegExp('\\b$name=(\\d+)').firstMatch(line);
  return match == null ? null : int.tryParse(match.group(1)!);
}

/// Значение поля до следующего ` name=` или конца строки.
String? _textField(String line, String name) {
  final match = RegExp('\\b$name=(.*?)(?=\\s+[a-z_]+=|\$)').firstMatch(line);
  return match?.group(1)?.trim();
}
