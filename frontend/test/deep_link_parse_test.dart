// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/csqtt_link_crypto.dart';
import 'package:frontend/service/deep_link.dart';

void main() {
  group('DeepLinkService.parseLink', () {
    test('расшифровывает base64-ссылку из браузера', () async {
      const open =
          'csqtt://connect?v=2&host=vpn.example.test&peer=46000&password=secret-pass-123';
      final encrypted = await CsqttLinkCrypto.encryptForTest(open);

      final (profile, link) = await DeepLinkService.instance.parseLink(
        encrypted,
      );

      expect(profile, isNotNull);
      expect(profile!.ip, 'vpn.example.test');
      expect(profile.port, 46000);
      expect(profile.password, 'secret-pass-123');
      expect(link, open);
    });

    test('терпит лишний слэш, который добавляет браузер', () async {
      const open = 'csqtt://connect?v=2&host=1.2.3.4&peer=443&password=pw';
      final encrypted = await CsqttLinkCrypto.encryptForTest(open);

      final (profile, _) = await DeepLinkService.instance.parseLink(
        '$encrypted/',
      );

      expect(profile?.ip, '1.2.3.4');
      expect(profile?.port, 443);
    });

    test('открытая ссылка не требует расшифровки', () async {
      const open = 'csqtt://connect?v=2&host=5.6.7.8&peer=8443&password=pass';

      final (profile, link) = await DeepLinkService.instance.parseLink(open);

      expect(profile?.ip, '5.6.7.8');
      expect(link, open);
    });

    test('битая base64-ссылка не принимается', () async {
      final (profile, _) = await DeepLinkService.instance.parseLink(
        'csqtt://broken-payload',
      );

      expect(profile, isNull);
    });
  });
}
