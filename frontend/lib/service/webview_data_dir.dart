// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

/// WebView2 нельзя держать данные рядом с .exe (Program Files) — там нет прав
/// на запись у обычного процесса. Возвращаем абсолютный путь внутри локальных
/// данных пользователя; на других платформах параметр не используется.
String webviewDataDir(String name) {
  if (!Platform.isWindows) return name;
  final localAppData = Platform.environment['LOCALAPPDATA'];
  final base = (localAppData == null || localAppData.isEmpty)
      ? Directory.systemTemp.path
      : localAppData;
  return '$base${Platform.pathSeparator}FOCSQ'
      '${Platform.pathSeparator}$name';
}
