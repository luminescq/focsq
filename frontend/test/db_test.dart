import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/server_profile.dart';
import 'package:frontend/service/db.dart';

import 'support/test_db.dart';

void main() {
  late TestDatabase testDb;

  setUpAll(() async {
    testDb = await TestDatabase.open();
  });

  setUp(() async {
    await testDb.clear();
    await DbService.getSettings();
  });

  tearDownAll(() async {
    await testDb.close();
  });

  test('одиночное удаление профиля сбрасывает activeProfileId', () async {
    final profile = ServerProfile(name: 'Сервер', ip: '192.0.2.10', port: 443);
    await DbService.saveProfile(profile);
    await DbService.setActiveProfileId(profile.id);

    await DbService.deleteProfile(profile.id);

    expect(await DbService.getProfiles(), isEmpty);
    expect((await DbService.getSettings()).activeProfileId, isNull);
  });

  test('routing settings are persisted', () async {
    await DbService.updateSettings(
      (s) => s
        ..zDomainRouting = true
        ..zExcludeRuZones = true
        ..zTemplateDomains = true
        ..zDomainWhitelist = true
        ..zDirectDomains = 'example.com'
        ..zIpRouting = true
        ..zIpWhitelist = true
        ..zDirectIps = '10.0.0.0/8',
    );

    final settings = await DbService.getSettings();
    expect(settings.zDomainRouting, isTrue);
    expect(settings.zExcludeRuZones, isTrue);
    expect(settings.zTemplateDomains, isTrue);
    expect(settings.zDomainWhitelist, isTrue);
    expect(settings.zDirectDomains, 'example.com');
    expect(settings.zIpRouting, isTrue);
    expect(settings.zIpWhitelist, isTrue);
    expect(settings.zDirectIps, '10.0.0.0/8');
  });
}
