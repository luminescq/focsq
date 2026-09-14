// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';
import 'logs.dart';

class UrlService {
  UrlService._();

  static String validated(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null ||
        (uri.scheme != 'http' && uri.scheme != 'https') ||
        uri.host.isEmpty) {
      throw FormatException('только http/https с указанным хостом: $url');
    }
    if (!_isPublicHost(uri.host)) {
      throw FormatException('приватный/зарезервированный хост: $url');
    }
    return url;
  }

  static Future<bool> openInBrowser(String url) {
    final safe = validated(url);
    try {
      if (Platform.isWindows) {
        return _run(['cmd', '/c', 'start', '', safe]);
      }
      if (Platform.isLinux) {
        return _run(['xdg-open', safe]);
      }
      LogService().add('[СЕРВИС] Открытие браузера на этой ОС не поддержано');
      return Future.value(false);
    } on FormatException catch (error) {
      LogService().add('[СЕРВИС] Ссылка отклонена: ${error.message}');
      return Future.value(false);
    }
  }

  static Future<bool> _run(List<String> command) async {
    try {
      final result = await Process.run(command.first, command.sublist(1));
      return result.exitCode == 0;
    } catch (error) {
      LogService().add('[СЕРВИС] Браузер не открылся: $error');
      return false;
    }
  }

  static bool _isPublicHost(String host) {
    final name = host.toLowerCase();
    if (name == 'localhost' ||
        name.endsWith('.localhost') ||
        name.endsWith('.local') ||
        name.endsWith('.internal')) {
      return false;
    }
    final address = InternetAddress.tryParse(host);
    if (address == null) return true;
    final bytes = address.rawAddress;
    if (bytes.length == 4) {
      return _isPublicIPv4(bytes[0], bytes[1]);
    }
    return _isPublicIPv6(bytes);
  }

  static bool _isPublicIPv4(int a, int b) {
    if (a == 0 || a == 10 || a == 127) return false;
    if (a == 169 && b == 254) return false;
    if (a == 172 && b >= 16 && b <= 31) return false;
    if (a == 192 && b == 168) return false;
    if (a == 100 && b >= 64 && b <= 127) return false;
    if (a == 198 && (b == 18 || b == 19)) return false;
    if (a >= 224) return false;
    return true;
  }

  static bool _isPublicIPv6(List<int> bytes) {
    if (bytes.every((byte) => byte == 0)) return false;
    final first = bytes[0];
    if (bytes[0] == 0 &&
        bytes[1] == 0 &&
        bytes[2] == 0 &&
        bytes[3] == 0 &&
        bytes[4] == 0 &&
        bytes[5] == 0 &&
        bytes[6] == 0 &&
        bytes[7] == 0) {
      return false;
    }
    if (first >= 0xfc) return false;
    if (first == 0xfe && (bytes[1] & 0xc0) == 0x80) return false;
    return true;
  }
}
