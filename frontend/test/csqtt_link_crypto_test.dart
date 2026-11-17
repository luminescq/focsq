// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/csqtt_link_crypto.dart';

void main() {
  group('CsqttLinkCrypto: определение формата', () {
    test('открытые ссылки не считаются зашифрованными', () {
      expect(
        CsqttLinkCrypto.isOpaqueLink(
          'csqtt://connect?v=2&host=1.2.3.4&peer=46000&password=pw',
        ),
        isFalse,
      );
      expect(CsqttLinkCrypto.isOpaqueLink('csqtt://secret@host:443'), isFalse);
      expect(
        CsqttLinkCrypto.isOpaqueLink(
          'csqtt://connect/?v=2&host=1.2.3.4&peer=46000&password=pw',
        ),
        isFalse,
      );
    });

    test('непрозрачная ссылка распознаётся', () {
      expect(CsqttLinkCrypto.isOpaqueLink('csqtt://SpG7q_PDjwAk'), isTrue);
      expect(CsqttLinkCrypto.isOpaqueLink('csqtt://'), isFalse);
      expect(CsqttLinkCrypto.isOpaqueLink('https://example.com'), isFalse);
    });
  });

  group('CsqttLinkCrypto: round-trip AES-256-GCM', () {
    test('лишний слэш после base64 не мешает расшифровке', () async {
      const open =
          'csqtt://connect?v=2&host=vpn.example.test&peer=46000&password=secret-pass-123';
      final encrypted = await CsqttLinkCrypto.encryptForTest(open);

      expect(CsqttLinkCrypto.isOpaqueLink('$encrypted/'), isTrue);
      expect(await CsqttLinkCrypto.decrypt('$encrypted/'), open);
    });

    test('шифрование и расшифровка v2-ссылки', () async {
      const open =
          'csqtt://connect?v=2&host=vpn.example.test&peer=46000&password=secret-pass-123';
      final encrypted = await CsqttLinkCrypto.encryptForTest(open);

      expect(encrypted.startsWith('csqtt://'), isTrue);
      expect(encrypted.contains('connect?'), isFalse);
      expect(await CsqttLinkCrypto.decrypt(encrypted), open);
    });

    test('шифрование детерминировано (как на сервере)', () async {
      const open = 'csqtt://connect?v=2&host=1.2.3.4&peer=443&password=pw';
      final first = await CsqttLinkCrypto.encryptForTest(open);
      final second = await CsqttLinkCrypto.encryptForTest(open);
      expect(first, second);
    });

    test(
      'plaintext без csqtt://-префикса расшифровывается в полную ссылку',
      () async {
        final encrypted = await CsqttLinkCrypto.encryptForTest(
          'connect?v=2&host=5.6.7.8&peer=443&password=pw',
        );
        expect(
          await CsqttLinkCrypto.decrypt(encrypted),
          'csqtt://connect?v=2&host=5.6.7.8&peer=443&password=pw',
        );
      },
    );

    test('расшифровывает ссылку, сгенерированную сервером', () async {
      const encrypted =
          'csqtt://wM5VZvhTsf5NZ5xrV1b6U4zJxv4PpjZ7YloYfp6X4TYa0-T0dwf8pgTsH23Nk3KDFgf3kQH1Rll8Dr4hYOA2OgV3vnjA1FK07dV2v31KrapBmMRlvZlQyM8';

      expect(
        await CsqttLinkCrypto.decrypt(encrypted),
        'csqtt://connect?v=2&host=203.0.113.17&peer=443&password=test-password',
      );
    });

    test('мусор и битые данные отклоняются', () async {
      expect(await CsqttLinkCrypto.decrypt('csqtt://broken'), isNull);
      expect(await CsqttLinkCrypto.decrypt('csqtt://AAAABBBBCCCC'), isNull);
      expect(await CsqttLinkCrypto.decrypt('not-a-link'), isNull);
    });
  });
}
