// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_locale.dart';
import '../modals/deep_link_import_modal.dart';
import '../models/server_profile.dart';
import '../widgets/custom_alert.dart';
import 'alert.dart';
import 'csqtt_link_crypto.dart';
import 'db.dart';
import 'import.dart';
import 'tray_service.dart';

class DeepLinkService {
  DeepLinkService._();

  static final DeepLinkService instance = DeepLinkService._();
  static const MethodChannel _channel = MethodChannel('focsq/deep_link');

  static bool isDuplicate(
    ServerProfile profile,
    Iterable<ServerProfile> existing,
  ) => existing.any(
    (item) => item.ip == profile.ip && item.port == profile.port,
  );

  GlobalKey<NavigatorState>? _navigatorKey;
  String? _pendingLink;
  bool _handling = false;

  Future<void> start(
    GlobalKey<NavigatorState> navigatorKey, {
    required List<String> initialArguments,
  }) async {
    _navigatorKey = navigatorKey;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onLink' && call.arguments is String) {
        await _handleLink(call.arguments as String);
      }
      return null;
    });

    for (final argument in initialArguments) {
      final candidate = argument.trim();
      if (candidate.startsWith(LinkParser.scheme)) {
        _pendingLink = candidate;
        break;
      }
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => _drainPendingLink());
  }

  void _drainPendingLink() {
    final link = _pendingLink;
    _pendingLink = null;
    if (link == null) return;
    if (_navigatorKey?.currentContext == null) {
      _pendingLink = link;
      WidgetsBinding.instance.addPostFrameCallback((_) => _drainPendingLink());
      return;
    }
    unawaited(_handleLink(link));
  }

  Future<void> _handleLink(String raw) async {
    if (_handling) return;
    _handling = true;
    try {
      unawaited(TrayService.instance.showWindow());
      final parsed = await parseLink(raw);
      final profile = parsed.$1;
      final link = parsed.$2;
      if (profile == null) {
        _showWarning(S.linkNotRecognized);
        return;
      }

      final context = _navigatorKey?.currentContext;
      if (context == null) {
        _pendingLink = link;
        WidgetsBinding.instance.addPostFrameCallback(
          (_) => _drainPendingLink(),
        );
        return;
      }

      final existing = await DbService.getProfiles();
      final duplicate = isDuplicate(profile, existing);
      final hasHashes = LinkParser().parseHashes(link) != null;
      if (!context.mounted) return;

      final confirmed = await DeepLinkImportModal.show(
        context,
        profile: profile,
        hasHashes: hasHashes,
        isDuplicate: duplicate,
      );
      if (!confirmed) return;

      final ImportResult result;
      try {
        result = await ImportService.importText(link);
      } catch (_) {
        if (context.mounted) {
          _showAlert(context, S.errorTitle, S.saveFailed, AlertType.warning);
        }
        return;
      }

      ServerProfile? saved;
      if (!result.hasError) saved = await _activateProfile(profile);
      if (!context.mounted) return;

      if (result.hasError) {
        AlertService.showImportResult(
          context,
          result,
          overlay: _navigatorKey?.currentState?.overlay,
        );
        return;
      }

      final name = saved?.name ?? profile.name ?? S.unnamedProfile;
      if (result.imported > 0) {
        final lines = <String>[S.profileAdded(name)];
        if (result.hashesApplied) lines.add(S.hashesUpdated);
        _showAlert(
          context,
          S.successTitle,
          lines.join('\n'),
          AlertType.success,
        );
      } else if (result.duplicates > 0) {
        final lines = <String>[S.duplicateWarning];
        if (result.hashesApplied) lines.add(S.hashesUpdated);
        _showAlert(context, S.noNewTitle, lines.join('\n'), AlertType.info);
      } else if (result.hashesApplied) {
        _showAlert(context, S.successTitle, S.hashesUpdated, AlertType.success);
      } else {
        AlertService.showImportResult(
          context,
          result,
          overlay: _navigatorKey?.currentState?.overlay,
        );
      }
    } finally {
      _handling = false;
    }
  }

  Future<ServerProfile?> _activateProfile(ServerProfile imported) async {
    try {
      final profiles = await DbService.getProfiles();
      for (final profile in profiles) {
        if (profile.ip == imported.ip && profile.port == imported.port) {
          await DbService.setActiveProfileId(profile.id);
          return profile;
        }
      }
    } catch (_) {}
    return null;
  }

  void _showAlert(
    BuildContext context,
    String title,
    String message,
    AlertType type,
  ) {
    final overlay = _navigatorKey?.currentState?.overlay;
    if (overlay == null) return;
    CustomAlert.show(
      context,
      title: title,
      message: message,
      type: type,
      overlay: overlay,
    );
  }

  /// Разбирает ссылку из браузера: открытые форматы, а также непрозрачные
  /// `csqtt://<base64>` с AES-256-GCM. Если не вышло — нормализует
  /// HTML-экранирование (&amp; → &) и пробует декодировать
  /// %-последовательности. Возвращает (профиль, эффективную ссылку).
  /// Значения не логируются.
  @visibleForTesting
  Future<(ServerProfile?, String)> parseLink(String raw) async {
    for (final candidate in _linkVariants(raw)) {
      final direct = ImportService.parseLink(candidate);
      if (direct != null) return (direct, candidate);

      if (!CsqttLinkCrypto.isOpaqueLink(candidate)) continue;
      final decrypted = await CsqttLinkCrypto.decrypt(candidate);
      if (decrypted == null) continue;
      final profile = ImportService.parseLink(decrypted);
      if (profile != null) return (profile, decrypted);
    }
    return (null, raw);
  }

  Iterable<String> _linkVariants(String raw) sync* {
    yield raw;

    final unescaped = raw.replaceAll('&amp;', '&').replaceAll('&#38;', '&');
    if (unescaped != raw) yield unescaped;

    try {
      final decoded = Uri.decodeComponent(raw);
      if (decoded != raw) yield decoded;
    } catch (_) {}
  }

  void _showWarning(String message) {
    final context = _navigatorKey?.currentContext;
    if (context == null || !context.mounted) return;
    _showAlert(context, S.linkNotOpened, message, AlertType.warning);
  }
}
