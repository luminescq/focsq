// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:convert';

import 'package:cryptography/cryptography.dart' as crypt;

/// AES-256-GCM codec for opaque `csqtt://<base64url>` access links.
///
/// Upstream server (web_panel.rs) derives the key as
/// SHA-256("csqtt-chiper-key") and stores the 12-byte IV in front of the
/// ciphertext+tag before base64url-encoding the blob.
class CsqttLinkCrypto {
  CsqttLinkCrypto._();

  static const String scheme = 'csqtt://';
  static const String _upstreamCipherSeed = 'csqtt-chiper-key';
  static const int _ivLength = 12;
  static const int _tagLength = 16;
  static const int _minPayloadLength = _ivLength + _tagLength;

  static crypt.SecretKey? _cachedKey;

  /// Formats that carry their parameters in clear text. Browsers may add a
  /// slash after the host (`csqtt://connect/?...`), so both forms are open.
  static bool _isOpenPayload(String payload) =>
      payload.startsWith('connect?') ||
      payload.startsWith('connect/') ||
      payload.contains('@');

  static String? _payload(String link) {
    final trimmed = link.trim();
    if (!trimmed.startsWith(scheme)) return null;
    var payload = trimmed
        .substring(scheme.length)
        .trim()
        .replaceAll(RegExp(r'[\r\n\s]'), '');
    // Some browsers serialize custom URLs as `csqtt://host/path/`, appending
    // a slash that is not part of the base64 payload.
    if (payload.endsWith('/')) {
      payload = payload.substring(0, payload.length - 1);
    }
    return payload.isEmpty ? null : payload;
  }

  static bool isOpaqueLink(String link) {
    final payload = _payload(link);
    if (payload == null) return false;
    return !_isOpenPayload(payload);
  }

  static Future<crypt.SecretKey> _getSecretKey() async {
    final cached = _cachedKey;
    if (cached != null) return cached;
    final hash = await crypt.Sha256().hash(utf8.encode(_upstreamCipherSeed));
    final key = crypt.SecretKey(hash.bytes);
    _cachedKey = key;
    return key;
  }

  /// Decrypts `csqtt://<base64url>` into an open link
  /// (`csqtt://connect?v=2...` or legacy `csqtt://password@host:port`).
  /// Returns null when the link is not an opaque link or cannot be decrypted.
  static Future<String?> decrypt(String rawLink) async {
    final payload = _payload(rawLink);
    if (payload == null || _isOpenPayload(payload)) return null;

    try {
      final bytes = _decodeBase64(payload);
      if (bytes.length < _minPayloadLength) return null;

      final iv = bytes.sublist(0, _ivLength);
      final cipherAndTag = bytes.sublist(_ivLength);
      final cipherText = cipherAndTag.sublist(
        0,
        cipherAndTag.length - _tagLength,
      );
      final mac = crypt.Mac(
        cipherAndTag.sublist(cipherAndTag.length - _tagLength),
      );

      final clear = await crypt.AesGcm.with256bits().decrypt(
        crypt.SecretBox(cipherText, nonce: iv, mac: mac),
        secretKey: await _getSecretKey(),
      );

      final decoded = utf8.decode(clear);
      if (decoded.startsWith('csqtt://')) return decoded;
      if (decoded.startsWith('connect?')) return 'csqtt://$decoded';
      return null;
    } on FormatException {
      return null;
    } on ArgumentError {
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Test helper: encrypts an open link exactly like the upstream server
  /// (deterministic IV from HMAC-SHA256(key, payload) truncated to 12 bytes).
  static Future<String> encryptForTest(String openLink) async {
    final clear = openLink.trim();
    final payload = clear.startsWith(scheme)
        ? clear.substring(scheme.length)
        : clear;
    final keyBytes = await (await _getSecretKey()).extractBytes();

    final hmac = crypt.Hmac.sha256();
    final mac = await hmac.calculateMac(
      utf8.encode(payload),
      secretKey: crypt.SecretKey(keyBytes),
    );
    final iv = mac.bytes.sublist(0, _ivLength);

    final box = await crypt.AesGcm.with256bits().encrypt(
      utf8.encode(payload),
      secretKey: crypt.SecretKey(keyBytes),
      nonce: iv,
    );

    final combined = <int>[...box.nonce, ...box.cipherText, ...box.mac.bytes];
    final b64 = base64Url.encode(combined).replaceAll('=', '');
    return '$scheme$b64';
  }

  static List<int> _decodeBase64(String input) {
    final normalized = base64Url.normalize(input);
    return base64Url.decode(normalized);
  }
}
