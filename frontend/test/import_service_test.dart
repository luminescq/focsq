import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/app_settings.dart';
import 'package:frontend/service/csqtt_link_crypto.dart';
import 'package:frontend/service/db.dart';
import 'package:frontend/service/import.dart';
import 'package:isar/isar.dart';

import 'support/test_db.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late TestDatabase testDb;
  late Isar database;

  setUpAll(() async {
    testDb = await TestDatabase.open();
    database = testDb.isar;
  });

  setUp(() async {
    await testDb.clear();
    await DbService.getSettings();
    await DbService.updateSettings(
      (settings) => settings
        ..customHashes = 'previous'
        ..hashes = 'Авто ВК'
        ..workMode = 'Авто ВК',
    );
  });

  tearDownAll(() async {
    await testDb.close();
  });

  String link(String hashes, {String host = 'example.test'}) =>
      'csqtt://connect?v=2&host=$host&peer=443&password=test-only&hashes=$hashes';

  test('сохраняет хеши и оба режима вместе с профилем', () async {
    final result = await ImportService.importText(link('first%2C%20second'));
    expect(result.imported, 1);
    expect(result.hashesApplied, isTrue);
    expect(result.hasError, isFalse);
    expect(await DbService.getProfiles(), hasLength(1));
    final stored = (await database.appSettings.get(AppSettings.singletonId))!;
    expect(stored.customHashes, 'first,second');
    expect(stored.hashes, 'Ручной');
    expect(stored.workMode, 'Звонки');
  });

  test('повторный импорт обновляет хеши без дублирования профиля', () async {
    await ImportService.importText(link('first'));
    final result = await ImportService.importText(link('replacement'));
    expect(result.imported, 0);
    expect(result.duplicates, 1);
    expect(result.hashesApplied, isTrue);
    expect(await DbService.getProfiles(), hasLength(1));
    expect((await DbService.getSettings()).customHashes, 'replacement');
  });

  test('пустые хеши и legacy не меняют настройки', () async {
    final result = await ImportService.importText(
      '${link('%20,,')} csqtt://p@other.test:443',
    );
    expect(result.hashesApplied, isFalse);
    final stored = await DbService.getSettings();
    expect(stored.customHashes, 'previous');
    expect(stored.hashes, 'Авто ВК');
    expect(stored.workMode, 'Авто ВК');
  });

  test('невалидная ссылка не меняет настройки', () async {
    final result = await ImportService.importText(
      'csqtt://connect?v=2&host=h&peer=0&password=p&hashes=invalid',
    );
    expect(result.problem, ImportProblem.invalidFormat);
    expect((await DbService.getSettings()).customHashes, 'previous');
  });

  test('браузерная ссылка connect/ импортируется и сохраняется', () async {
    final result = await ImportService.importText(
      'csqtt://connect/?v=2&host=slash.example.test&peer=443&password=test-only',
    );

    expect(result.problem, isNull);
    expect(result.imported, 1);
    final profiles = await DbService.getProfiles();
    expect(profiles, hasLength(1));
    expect(profiles.single.ip, 'slash.example.test');
  });

  test('зашифрованная csqtt://<base64> ссылка импортируется из буфера', () async {
    final encrypted = await CsqttLinkCrypto.encryptForTest(
      'csqtt://connect?v=2&host=vpn.example.test&peer=46000&password=secret-pass-123',
    );
    final result = await ImportService.importText(encrypted);

    expect(result.hasError, isFalse);
    expect(result.imported, 1);
    expect(result.duplicates, 0);
    final profile = (await DbService.getProfiles()).single;
    expect(profile.ip, 'vpn.example.test');
    expect(profile.port, 46000);
    expect(profile.password, 'secret-pass-123');
  });

  test('зашифрованная ссылка с hashes применяет ручной режим', () async {
    final encrypted = await CsqttLinkCrypto.encryptForTest(
      'csqtt://connect?v=2&host=h.test&peer=443&password=p&hashes=abc123',
    );
    final result = await ImportService.importText(encrypted);

    expect(result.imported, 1);
    expect(result.hashesApplied, isTrue);
    expect((await DbService.getSettings()).customHashes, 'abc123');
  });

  test('нерасшифровываемый мусор после csqtt:// даёт invalidFormat', () async {
    final result = await ImportService.importText('csqtt://broken-payload');
    expect(result.problem, ImportProblem.invalidFormat);
  });

  test(
    'в пачке применяется последний непустой список валидной ссылки',
    () async {
      await ImportService.importText(
        '${link('first')} ${link('last', host: 'other.test')} '
        '${link('', host: 'empty.test')} '
        'csqtt://connect?v=2&host=h&peer=0&password=p&hashes=invalid',
      );
      expect((await DbService.getSettings()).customHashes, 'last');
      expect(await DbService.getProfiles(), hasLength(3));
    },
  );
}
