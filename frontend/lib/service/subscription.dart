// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import '../l10n/app_locale.dart';
import '../models/profile_group.dart';
import '../models/server_profile.dart';
import 'csqtt_link_crypto.dart';
import 'db.dart';
import 'import.dart';

/// Типизированные ошибки при работе с подпиской
enum SubscriptionProblem {
  invalidUrl,
  networkError,
  untrustedCertificate,
  serverError,
  insecureRedirect,
  emptyResponse,
  invalidFormat,
  noValidNodes,
  saveFailed,
  cancelled,
}

class _InsecureRedirectException implements Exception {
  const _InsecureRedirectException();
}

/// Элемент сервера внутри подписки
class SubscriptionItem {
  final String name;
  final String rawLink;
  final String decryptedLink;
  final ServerProfile? profile;
  final String? subUuid;

  const SubscriptionItem({
    required this.name,
    required this.rawLink,
    required this.decryptedLink,
    this.profile,
    this.subUuid,
  });
}

/// Результат загрузки и парсинга подписки
class SubscriptionResult {
  final bool success;
  final String title;
  final List<SubscriptionItem> items;
  final int importedCount;
  final int updatedCount;
  final int skippedCount;
  final SubscriptionProblem? problem;
  final String? errorMessage;

  /// Одиночная подписка (HTML или JSON без group): у неё может не быть
  /// sub_uuid, поэтому при повторном импорте узел ищется по адресу подписки.
  final bool isSingle;

  /// Настройки звонков были изменены содержимым подписки.
  final bool hashesApplied;

  const SubscriptionResult({
    required this.success,
    this.title = '',
    this.items = const [],
    this.importedCount = 0,
    this.updatedCount = 0,
    this.skippedCount = 0,
    this.problem,
    this.errorMessage,
    this.isSingle = false,
    this.hashesApplied = false,
  });

  bool get hasError => problem != null;

  bool get hasSkipped => skippedCount > 0;
}

class SubscriptionService {
  SubscriptionService._();

  // Лимит размера тела ответа подписки (2 МБ), предотвращающий DoS по памяти
  static const int maxResponseBodyBytes = 2 * 1024 * 1024;

  // Таймауты сети
  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration readTimeout = Duration(seconds: 10);

  /// Проверяет, является ли ссылка открытой (csqtt://connect?v=2... или legacy format)
  static bool isOpenLink(String link) =>
      !CsqttLinkCrypto.isOpaqueLink(link) && link.trim().startsWith('csqtt://');

  /// Расшифровка полезной нагрузки ссылки csqtt://... (AES-256-GCM)
  /// Возвращает расшифрованную ссылку или null при ошибке расшифровки.
  /// Открытые ссылки (connect?...) возвращаются как есть.
  static Future<String?> decryptLink(String rawLink) async {
    final trimmed = rawLink.trim();
    if (!trimmed.startsWith('csqtt://')) return null;
    if (isOpenLink(trimmed)) return trimmed;
    return CsqttLinkCrypto.decrypt(trimmed);
  }

  /// Загрузка данных подписки с защитой от MITM.
  ///
  /// - Работает строгая системная проверка сертификатов через системный CA trust store.
  /// - Переходы на незащищенный HTTP запрещены, в том числе через редиректы,
  ///   если HTTP не разрешён явно в настройках.
  static Future<SubscriptionResult> fetchSubscription(
    String rawUrl, {
    bool? allowHttp,
    HttpClient? customClient,
  }) async {
    var effectiveAllowHttp = allowHttp ?? false;
    if (allowHttp == null) {
      try {
        effectiveAllowHttp = (await DbService.getSettings()).zAllowInsecureHttp;
      } catch (_) {
        effectiveAllowHttp = false;
      }
    }
    var urlStr = rawUrl.trim();
    if (!urlStr.startsWith('http://') && !urlStr.startsWith('https://')) {
      urlStr = 'https://$urlStr';
    }

    final uri = Uri.tryParse(urlStr);
    if (uri == null) {
      return SubscriptionResult(
        success: false,
        problem: SubscriptionProblem.invalidUrl,
        errorMessage: S.subscriptionInvalidUrl,
      );
    }

    if (uri.scheme == 'http' && !effectiveAllowHttp) {
      return SubscriptionResult(
        success: false,
        problem: SubscriptionProblem.invalidUrl,
        errorMessage: S.subscriptionHttpDisabled,
      );
    }

    // Формируем список уникальных эндпоинтов (LinkedHashSet для сохранения порядка без дублей)
    final candidateUris = <Uri>{};
    final path = uri.path;

    if (path.endsWith('/sub')) {
      candidateUris.add(
        uri.replace(path: '${path.substring(0, path.length - 4)}/json'),
      );
      candidateUris.add(
        uri.replace(path: '${path.substring(0, path.length - 4)}/sub.json'),
      );
      candidateUris.add(uri);
    } else if (path.endsWith('/json') || path.endsWith('.json')) {
      candidateUris.add(uri);
    } else {
      candidateUris.add(uri.replace(path: '$path/json'));
      candidateUris.add(uri.replace(path: '$path/sub.json'));
      candidateUris.add(uri.replace(path: '$path/sub'));
      candidateUris.add(uri);
    }

    // HTTP-fallback выполняется только при явном включении в настройках.
    // Добавляем его в конец списка, чтобы HTTPS-варианты пробовались первыми.
    // Явный порт 443 при переходе на http бессмысленен — сбрасываем к дефолту.
    if (effectiveAllowHttp && uri.scheme == 'https') {
      candidateUris.addAll(candidateUris.map(_asHttpUri).toList());
    }

    final previousTimeout = customClient?.connectionTimeout;
    final client = customClient ?? HttpClient();
    client.connectionTimeout = connectTimeout;

    SubscriptionProblem? lastProblem;
    String? lastError;
    // Явный отказ сервера в JSON информативнее сетевой ошибки, но не должен
    // мешать попробовать остальные эндпоинты (например, /sub отдаёт HTML).
    SubscriptionProblem? rejectedProblem;
    String? rejectedError;

    try {
      for (final targetUri in candidateUris) {
        try {
          final response = await _openWithRedirects(
            client,
            targetUri,
            allowHttp: effectiveAllowHttp,
          );

          // Проверяем 2xx статус
          if (response.statusCode < 200 || response.statusCode >= 300) {
            await response.drain().catchError((_) {});
            lastProblem = SubscriptionProblem.serverError;
            lastError = S.subscriptionServerStatus(response.statusCode);
            continue;
          }

          // Читаем тело с ограничением по размеру и таймаутом
          final bodyBytes = await _readLimitedBody(
            response,
            maxResponseBodyBytes,
            readTimeout,
          );
          if (bodyBytes.isEmpty) {
            lastProblem = SubscriptionProblem.emptyResponse;
            lastError = S.subscriptionEmptyResponse;
            continue;
          }

          // Определяем кодировку из Content-Type заголовка (по умолчанию UTF-8)
          final contentType = response.headers.contentType;
          final encoding = Encoding.getByName(contentType?.charset) ?? utf8;
          final bodyText = encoding.decode(bodyBytes);

          // 1. Пробуем парсить как JSON
          try {
            final json = jsonDecode(bodyText);
            if (json is Map<String, dynamic>) {
              final result = await _parseSubscriptionJson(json);
              if (result != null) {
                if (result.success) return result;
                rejectedProblem = result.problem;
                rejectedError = result.errorMessage;
                continue;
              }
            }
          } on FormatException {
            // Не JSON, пробуем как HTML
          }

          // 2. Если JSON не подошел, пробуем HTML
          final htmlResult = await _parseSubscriptionHtml(bodyText);
          if (htmlResult != null) {
            return htmlResult;
          }
        } on _InsecureRedirectException {
          lastProblem = SubscriptionProblem.insecureRedirect;
          lastError = S.subscriptionInsecureRedirect;
          continue;
        } on HandshakeException catch (e) {
          return SubscriptionResult(
            success: false,
            problem: SubscriptionProblem.untrustedCertificate,
            errorMessage: S.subscriptionUntrustedCertificate(e.message),
          );
        } on CertificateException catch (e) {
          return SubscriptionResult(
            success: false,
            problem: SubscriptionProblem.untrustedCertificate,
            errorMessage: S.subscriptionCertificateError(e.message),
          );
        } on SocketException catch (e) {
          lastProblem = SubscriptionProblem.networkError;
          lastError = S.subscriptionNetworkError(e.message);
          continue;
        } on TimeoutException {
          lastProblem = SubscriptionProblem.networkError;
          lastError = S.subscriptionTimeout;
          continue;
        } on HttpException catch (e) {
          lastProblem = SubscriptionProblem.networkError;
          lastError = e.message;
          continue;
        } catch (e) {
          // Неожиданное исключение не должно утекать в UI сырым текстом.
          debugPrint('Subscription fetch failed: $e');
          lastProblem = SubscriptionProblem.networkError;
          lastError = S.subscriptionFetchFailed;
          continue;
        }
      }

      return SubscriptionResult(
        success: false,
        problem:
            rejectedProblem ?? lastProblem ?? SubscriptionProblem.networkError,
        errorMessage: rejectedError ?? lastError ?? S.subscriptionFetchFailed,
      );
    } finally {
      if (customClient == null) {
        client.close(force: true);
      } else {
        client.connectionTimeout = previousTimeout;
      }
    }
  }

  static const int _maxRedirects = 5;

  /// https → http для fallback-попытки. Явно указанный порт 443 сбрасываем:
  /// http-сервер на 443 почти наверняка отсутствует.
  static Uri _asHttpUri(Uri uri) {
    final port = uri.hasPort && uri.port != 443 ? uri.port : null;
    return Uri(
      scheme: 'http',
      userInfo: uri.userInfo,
      host: uri.host,
      port: port,
      path: uri.path,
      query: uri.query,
      fragment: uri.fragment,
    );
  }

  /// Открывает запрос, вручную обрабатывая редиректы: стандартный
  /// `followRedirects` может увести загрузку с HTTPS на HTTP в обход настройки.
  static Future<HttpClientResponse> _openWithRedirects(
    HttpClient client,
    Uri uri, {
    required bool allowHttp,
  }) async {
    var current = uri;
    for (var hop = 0; hop <= _maxRedirects; hop++) {
      if (current.scheme != 'http' && current.scheme != 'https') {
        throw HttpException(S.subscriptionUnsupportedScheme);
      }
      if (current.scheme == 'http' && !allowHttp) {
        throw const _InsecureRedirectException();
      }

      final request = await client.getUrl(current).timeout(connectTimeout);
      request.followRedirects = false;
      request.headers.set('User-Agent', 'focsq-client');
      request.headers.set(
        'Accept',
        'application/json, text/html;q=0.9, */*;q=0.8',
      );
      final response = await request.close().timeout(connectTimeout);
      if (!response.isRedirect) return response;

      final location = response.headers.value(HttpHeaders.locationHeader);
      await response.drain().timeout(readTimeout).catchError((_) {});
      if (location == null) {
        throw HttpException(S.subscriptionRedirectWithoutLocation);
      }
      current = current.resolve(location);
    }
    throw HttpException(S.subscriptionTooManyRedirects);
  }

  /// Потоковое чтение с ограничением по максимальному числу байт.
  ///
  /// Таймаут применяется к потоку, а не к Future: `.timeout()` снаружи не
  /// отменяет чтение, и тело продолжает качаться в фоне после ошибки.
  /// При превышении лимита достаточно бросить исключение — выход из
  /// `await for` сам отменяет подписку (повторный drain бросил бы StateError).
  static Future<List<int>> _readLimitedBody(
    HttpClientResponse response,
    int maxBytes,
    Duration timeout,
  ) async {
    final bytes = <int>[];
    await for (final chunk in response.timeout(timeout)) {
      if (bytes.length + chunk.length > maxBytes) {
        throw HttpException(S.subscriptionBodyTooLarge);
      }
      bytes.addAll(chunk);
    }
    return bytes;
  }

  /// Парсит JSON-ответ подписки от сервера CSQTT (одиночный узел или группа)
  static Future<SubscriptionResult?> _parseSubscriptionJson(
    Map<String, dynamic> json,
  ) async {
    final ok = json['ok'] == true;
    if (!ok) {
      final msg = json['error'] as String? ?? S.subscriptionRejected;
      return SubscriptionResult(
        success: false,
        problem: SubscriptionProblem.serverError,
        errorMessage: msg,
      );
    }

    // Вариант 1: Групповая подписка
    if (json['group'] is Map<String, dynamic>) {
      final group = json['group'] as Map<String, dynamic>;
      final groupName =
          (group['name'] as String?)?.trim() ?? S.subscriptionDefaultTitle;
      final rawItems = group['items'] as List<dynamic>? ?? [];

      if (rawItems.isEmpty) {
        return SubscriptionResult(
          success: false,
          title: groupName,
          problem: SubscriptionProblem.noValidNodes,
          errorMessage: S.subscriptionNoNodesInGroup(groupName),
        );
      }

      final items = <SubscriptionItem>[];
      var skipped = 0;
      for (final raw in rawItems) {
        if (raw is! Map<String, dynamic>) {
          skipped++;
          continue;
        }
        final name =
            (raw['name'] as String?)?.trim() ?? S.subscriptionDefaultNodeName;
        final rawLink = (raw['link'] as String?)?.trim() ?? '';
        final subUuid = (raw['sub_uuid'] as String?)?.trim();

        if (rawLink.isEmpty) {
          skipped++;
          continue;
        }
        final decrypted = await decryptLink(rawLink);
        if (decrypted == null) {
          skipped++;
          continue;
        }
        final profile = ImportService.parseLink(decrypted);
        if (profile == null) {
          skipped++;
          continue;
        }
        final rawPower =
            (raw['power'] as num?)?.toInt() ??
            (raw['workers'] as num?)?.toInt();
        final normalizedPower = ImportService.normalizePower(rawPower);
        if (normalizedPower != null) {
          profile.power = normalizedPower;
        }
        items.add(
          SubscriptionItem(
            name: name,
            rawLink: rawLink,
            decryptedLink: decrypted,
            profile: profile,
            subUuid: subUuid,
          ),
        );
      }

      if (items.isEmpty) {
        return SubscriptionResult(
          success: false,
          title: groupName,
          problem: SubscriptionProblem.noValidNodes,
          errorMessage: S.subscriptionNoDecryptableNodes,
          skippedCount: skipped,
        );
      }

      return SubscriptionResult(
        success: true,
        title: groupName,
        items: items,
        skippedCount: skipped,
      );
    }

    // Вариант 2: Одиночная подписка клиента
    final name =
        (json['name'] as String?)?.trim() ?? S.subscriptionDefaultServerName;
    final rawLink = (json['link'] as String?)?.trim() ?? '';
    final subUuid = (json['sub_uuid'] as String?)?.trim();

    if (rawLink.isNotEmpty) {
      final decrypted = await decryptLink(rawLink);
      if (decrypted != null) {
        final profile = ImportService.parseLink(decrypted);
        if (profile != null) {
          final rawPower =
              (json['power'] as num?)?.toInt() ??
              (json['workers'] as num?)?.toInt();
          final normalizedPower = ImportService.normalizePower(rawPower);
          if (normalizedPower != null) {
            profile.power = normalizedPower;
          }
          return SubscriptionResult(
            success: true,
            title: name,
            isSingle: true,
            items: [
              SubscriptionItem(
                name: name,
                rawLink: rawLink,
                decryptedLink: decrypted,
                profile: profile,
                subUuid: subUuid,
              ),
            ],
          );
        }
      }
      return SubscriptionResult(
        success: false,
        title: name,
        problem: SubscriptionProblem.noValidNodes,
        errorMessage: S.subscriptionNodeDecryptFailed,
      );
    }

    return SubscriptionResult(
      success: false,
      title: name,
      problem: SubscriptionProblem.noValidNodes,
      errorMessage: S.subscriptionNoLink,
    );
  }

  /// Извлекает заголовок и csqtt:// ссылку из HTML-страницы подписки (/sub)
  static Future<SubscriptionResult?> _parseSubscriptionHtml(String html) async {
    // Извлекаем заголовок из тега <title> или h2 (поддерживает русский и английский)
    var title = S.subscriptionDefaultTitle;
    final titleMatch =
        S.subscriptionTitlePattern.firstMatch(html) ??
        S.subscriptionHeadingPattern.firstMatch(html);
    if (titleMatch != null) {
      title = titleMatch.group(1)?.trim() ?? title;
    }

    // Ищем csqtt:// ссылку (как зашифрованную base64, так и открытую v2 с параметрами)
    final linkMatch = RegExp(
      r'csqtt://[^\s"<>]+',
      caseSensitive: false,
    ).firstMatch(html);
    if (linkMatch == null) return null;

    final rawLink = linkMatch.group(0)!;
    final decrypted = await decryptLink(rawLink);
    if (decrypted == null) {
      return SubscriptionResult(
        success: false,
        title: title,
        problem: SubscriptionProblem.noValidNodes,
        errorMessage: S.subscriptionHtmlDecryptFailed,
      );
    }

    final profile = ImportService.parseLink(decrypted);
    if (profile == null) {
      return SubscriptionResult(
        success: false,
        title: title,
        problem: SubscriptionProblem.invalidFormat,
        errorMessage: S.subscriptionBadNodeLink,
      );
    }

    return SubscriptionResult(
      success: true,
      title: title,
      isSingle: true,
      items: [
        SubscriptionItem(
          name: title,
          rawLink: rawLink,
          decryptedLink: decrypted,
          profile: profile,
        ),
      ],
    );
  }

  /// Импортирует или обновляет подписку в локальной базе данных.
  ///
  /// Выполняет:
  /// - Безопасную загрузку и валидацию
  /// - Дедупликацию по [SubscriptionItem.subUuid], а для узлов без него —
  ///   по ip+port в рамках той же подписки
  /// - Удаление узлов, исчезнувших из подписки (только если сервер отдаёт
  ///   subUuid для всех элементов)
  /// - Атомарную запись всех изменений одной транзакцией
  /// - Применение хешей звонков (VK hashes) при [applyHashes]
  static Future<SubscriptionResult> importSubscription(
    String url, {
    String? customTitle,
    bool? allowHttp,
    bool applyHashes = true,
    bool Function()? isCancelled,
  }) async {
    var effectiveAllowHttp = allowHttp ?? false;
    if (allowHttp == null) {
      try {
        effectiveAllowHttp = (await DbService.getSettings()).zAllowInsecureHttp;
      } catch (_) {
        effectiveAllowHttp = false;
      }
    }
    final result = await fetchSubscription(url, allowHttp: effectiveAllowHttp);

    if (!result.success || result.items.isEmpty) {
      return result;
    }
    if (isCancelled?.call() ?? false) {
      return SubscriptionResult(
        success: false,
        title: customTitle?.trim() ?? result.title,
        problem: SubscriptionProblem.cancelled,
      );
    }

    final groupTitle = (customTitle?.trim().isNotEmpty ?? false)
        ? customTitle!.trim()
        : result.title.isNotEmpty
        ? result.title
        : S.subscriptionDefaultTitle;

    List<ServerProfile> existing;
    try {
      existing = await DbService.getProfiles();
    } catch (_) {
      return SubscriptionResult(
        success: false,
        title: groupTitle,
        problem: SubscriptionProblem.saveFailed,
        errorMessage: S.subscriptionReadFailed,
      );
    }

    final byUuid = <String, ServerProfile>{};
    final byAddress = <String, ServerProfile>{};
    final existingForUrl = <ServerProfile>[];
    for (final profile in existing) {
      final uuid = profile.subUuid;
      if (uuid != null && uuid.isNotEmpty) byUuid[uuid] = profile;
      final ip = profile.ip;
      final port = profile.port;
      if (profile.subscriptionUrl == url) {
        existingForUrl.add(profile);
        if (ip != null && port != null) {
          byAddress[_addressKey(ip, port)] = profile;
        }
      }
    }

    final toWrite = <ServerProfile>[];
    final processed = <ServerProfile>{};
    final seenUuids = <String>{};
    var updatedCount = 0;
    var importedCount = 0;

    // Извлекаем хэши из ссылок для обновления настроек
    final linkParser = LinkParser();
    String? appliedHashes;

    for (final item in result.items) {
      final parsed =
          item.profile ?? ImportService.parseLink(item.decryptedLink);
      if (parsed == null) continue;

      final uuid = item.subUuid;
      final hasUuid = uuid != null && uuid.isNotEmpty;
      final addressKey = parsed.ip != null && parsed.port != null
          ? _addressKey(parsed.ip!, parsed.port!)
          : null;

      var profile = hasUuid ? byUuid[uuid] : null;
      // У одиночной подписки может не быть sub_uuid: при смене адреса
      // обновляем существующий профиль этой подписки, а не плодим дубли.
      if (profile == null && result.isSingle && existingForUrl.isNotEmpty) {
        profile = existingForUrl.first;
      }
      profile ??= addressKey == null ? null : byAddress[addressKey];
      final isNew = profile == null;
      profile ??= ServerProfile(ip: parsed.ip, port: parsed.port);

      final nodeName = result.items.length > 1
          ? 'Sub:$groupTitle - ${item.name}'
          : 'Sub:$groupTitle';
      // Пользовательское имя узла не трогаем; обновляем только служебные
      // имена, которые сами же и сгенерировали при импорте.
      if (isAutoSubscriptionName(profile.name)) {
        profile.name = nodeName;
      }
      profile.ip = parsed.ip;
      profile.port = parsed.port;
      profile.password = parsed.password;
      final newPower = parsed.power;
      if (newPower != null) {
        final oldLimit = profile.threadsLimit;
        // Если пользователь не выбирал мощность вручную (она совпадала с
        // прежним лимитом сервера), следуем за новым лимитом в обе стороны.
        // Иначе сохраняем его выбор и только не даём превысить лимит.
        final followsLimit =
            profile.power == null ||
            oldLimit == null ||
            profile.power == oldLimit;
        profile.threadsLimit = newPower;
        if (followsLimit || profile.power! > newPower) {
          profile.power = newPower;
        }
      }
      if (hasUuid) profile.subUuid = uuid;
      profile.subscriptionUrl = url;

      if (processed.add(profile)) {
        toWrite.add(profile);
        if (isNew) {
          importedCount++;
        } else {
          updatedCount++;
        }
      }

      if (hasUuid) {
        byUuid[uuid] = profile;
        seenUuids.add(uuid);
      }
      if (addressKey != null) {
        byAddress[addressKey] = profile;
      }

      if (applyHashes) {
        final hashes = linkParser.parseHashes(item.decryptedLink);
        if (hashes != null && hashes.isNotEmpty) {
          appliedHashes = hashes;
        }
      }
    }

    // Удаляем только «осиротевшие» узлы этой же подписки и только когда
    // сервер вернул subUuid для всех элементов — иначе можно снести лишнее.
    final deleteIds = <int>{};
    if (seenUuids.isNotEmpty &&
        result.items.every((item) => item.subUuid?.isNotEmpty ?? false)) {
      for (final profile in existing) {
        final uuid = profile.subUuid;
        if (profile.subscriptionUrl != url || uuid == null || uuid.isEmpty) {
          continue;
        }
        if (!seenUuids.contains(uuid)) deleteIds.add(profile.id);
      }
    }
    // Одиночная подписка по определению содержит один узел: лишние профили
    // этого URL — дубли от прежних импортов (например, при смене адреса).
    if (result.isSingle && processed.isNotEmpty) {
      for (final profile in existingForUrl) {
        if (!processed.contains(profile)) deleteIds.add(profile.id);
      }
    }

    if (isCancelled?.call() ?? false) {
      return SubscriptionResult(
        success: false,
        title: groupTitle,
        problem: SubscriptionProblem.cancelled,
      );
    }

    var hashesChanged = false;
    if (toWrite.isNotEmpty || deleteIds.isNotEmpty || appliedHashes != null) {
      try {
        await DbService.saveImportedProfiles(
          toWrite,
          deleteIds: deleteIds.toList(),
          updateSettings: appliedHashes == null
              ? null
              : (settings) {
                  hashesChanged =
                      settings.customHashes != appliedHashes ||
                      settings.hashes != 'Ручной' ||
                      settings.workMode != 'Звонки';
                  settings
                    ..customHashes = appliedHashes!
                    ..hashes = 'Ручной'
                    ..workMode = 'Звонки';
                },
        );
      } catch (_) {
        return SubscriptionResult(
          success: false,
          title: groupTitle,
          problem: SubscriptionProblem.saveFailed,
          errorMessage: S.subscriptionSaveFailed,
        );
      }
    }

    return SubscriptionResult(
      success: true,
      title: groupTitle,
      items: result.items,
      importedCount: importedCount,
      updatedCount: updatedCount,
      skippedCount: result.skippedCount,
      isSingle: result.isSingle,
      hashesApplied: hashesChanged,
    );
  }

  static String _addressKey(String ip, int port) => '$ip|$port';
}
