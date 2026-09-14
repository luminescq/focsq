import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
// Мок сбойного хранилища наследует интерфейс напрямую; это transitive-
// зависимость flutter_secure_storage — по этой же причине тут ignore.
// ignore: depend_on_referenced_packages
import 'package:flutter_secure_storage_platform_interface/flutter_secure_storage_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/logs.dart';
import 'package:frontend/service/vk/token.dart';

/// Токен ВК поверх защищённого хранилища: полный цикл save→has→get→delete
/// на моке платформы, файл-фолбэк без Secret Service (KDE без
/// gnome-keyring), миграция файла в keyring и классификация ошибок.

/// Мок-хранилище без Secret Service («Libsecret error» от нативного
/// слоя libsecret): write (и при [failRead] — read) падают, как на
/// KDE без gnome-keyring. Остальные методы работают по-настоящему,
/// чтобы падения не ломали соседние тесты группы.
class _FailingWriteStorage extends FlutterSecureStoragePlatform {
  _FailingWriteStorage({this.failRead = false});

  final bool failRead;
  final Map<String, String> _store = {};

  @override
  Future<bool> containsKey({
    required String key,
    required Map<String, String> options,
  }) async =>
      _store.containsKey(key);

  @override
  Future<String?> read({
    required String key,
    required Map<String, String> options,
  }) async {
    if (failRead) {
      throw PlatformException(code: 'Libsecret error', message: 'no service');
    }
    return _store[key];
  }

  @override
  Future<void> write({
    required String key,
    required String value,
    required Map<String, String> options,
  }) async {
    throw PlatformException(code: 'Libsecret error', message: 'no service');
  }

  @override
  Future<void> delete({
    required String key,
    required Map<String, String> options,
  }) async {
    _store.remove(key);
  }

  @override
  Future<Map<String, String>> readAll({
    required Map<String, String> options,
  }) async =>
      Map.of(_store);

  @override
  Future<void> deleteAll({required Map<String, String> options}) async {
    _store.clear();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('describeStorageError', () {
    test('KeyringLocked — подсказка про разблокировку keyring', () {
      final text = VkToken.describeStorageError(
        PlatformException(code: 'KeyringLocked'),
      );
      expect(text, contains('заблокировано'));
      expect(text, contains('keyring'));
    });

    test('Libsecret error — подсказка про gnome-keyring', () {
      final text = VkToken.describeStorageError(
        PlatformException(code: 'Libsecret error'),
      );
      expect(text, contains('gnome-keyring'));
    });

    test('неизвестный код — видны код и сообщение', () {
      final text = VkToken.describeStorageError(
        PlatformException(code: 'StorageError', message: 'dpapi fail'),
      );
      expect(text, contains('StorageError'));
      expect(text, contains('dpapi fail'));
    });

    test('не PlatformException — как есть', () {
      expect(VkToken.describeStorageError(Exception('boom')), contains('boom'));
    });
  });

  group('VkToken поверх мока хранилища', () {
    late Directory tmp;

    setUp(() {
      FlutterSecureStorage.setMockInitialValues({});
      LogService().clear();
      VkToken.lastNotice = null;
      tmp = Directory.systemTemp.createTempSync('focsq_vk_token_test');
      VkToken.fallbackDirForTest = tmp.path;
    });

    tearDown(() async {
      VkToken.fallbackDirForTest = null;
      VkToken.lastNotice = null;
      try {
        await tmp.delete(recursive: true);
      } catch (_) {}
    });

    test('полный цикл: save → has/get → delete', () async {
      expect(await VkToken.has(), isFalse);

      expect(await VkToken.save('tok_123'), isNull);
      expect(VkToken.lastNotice, isNull); // сохранено в защищённое
      expect(await VkToken.has(), isTrue);
      expect(await VkToken.get(), 'tok_123');

      expect(await VkToken.delete(), isTrue);
      expect(await VkToken.has(), isFalse);
      expect(await VkToken.get(), isNull);
    });

    test('save логирует успех, get не пишет мусор в журнал', () async {
      await VkToken.save('tok_123');
      expect(LogService().lines, isNotEmpty);
      expect(LogService().lines.last, contains('Токен сохранён'));

      final before = LogService().lines.length;
      await VkToken.get();
      expect(LogService().lines.length, before);
    });

    test('save при сбое хранилища: файл-фолбэк, не ошибка', () async {
      // Мок, у которого write всегда падает — как Linux без Secret
      // Service (KDE без gnome-keyring). Токен должен уйти в
      // файл-фолбэк: успех (null), честный лог и notice для UI.
      FlutterSecureStoragePlatform.instance = _FailingWriteStorage();
      LogService().clear();

      final reason = await VkToken.save('tok_123');
      expect(reason, isNull);
      expect(LogService().lines.last, contains('без шифрования'));
      expect(VkToken.lastNotice, isNotNull);
      expect(VkToken.lastNotice, contains('без шифрования'));

      // Фолбэк читается и удаляется как настоящий токен
      expect(await VkToken.has(), isTrue);
      expect(await VkToken.get(), 'tok_123');
      expect(await VkToken.delete(), isTrue);
      expect(await VkToken.has(), isFalse);
    });

    test('readWithStatus при недоступном хранилище без файла: причина', () async {
      // libsecret без Secret Service падает и на чтении тоже
      FlutterSecureStoragePlatform.instance =
          _FailingWriteStorage(failRead: true);
      final read = await VkToken.readWithStatus();
      expect(read.token, isNull);
      expect(read.issue, contains('нет Secret Service'));
    });

    test('миграция: keyring появился — токен переезжает из файла наверх',
        () async {
      // Токен остался в файл-фолбэке с прошлой жизни без keyring…
      final file = File(
          '${tmp.path}${Platform.pathSeparator}focsq${Platform.pathSeparator}vk_token');
      await file.parent.create(recursive: true);
      await file.writeAsString('tok_from_file');

      // …хранилище снова работает (setUp дал обычный мок)
      expect(await VkToken.get(), 'tok_from_file');
      expect(
        LogService().lines.where((l) => l.contains('перенесён из файла')),
        isNotEmpty,
      );
      // Файл удалён, токен живёт в защищённом хранилище
      expect(await file.exists(), isFalse);
      expect(await VkToken.get(), 'tok_from_file');
    });

    test('readWithStatus на пустом хранилище: токена нет, ошибок нет', () async {
      final read = await VkToken.readWithStatus();
      expect(read.token, isNull);
      expect(read.issue, isNull);
    });

    test('save тримит пробелы вокруг токена', () async {
      await VkToken.save('  tok_123  ');
      expect(await VkToken.get(), 'tok_123');
    });

    test('полный отказ: хранилище и файл недоступны — причина для алерта',
        () async {
      FlutterSecureStoragePlatform.instance = _FailingWriteStorage();
      // Каталог фолбэка — под обычным ФАЙЛОМ: создать нельзя.
      final blocker =
          File('${Directory.systemTemp.path}/focsq_vk_blocker.txt');
      await blocker.writeAsString('not a directory');
      addTearDown(() => blocker.deleteSync());
      VkToken.fallbackDirForTest = blocker.path;

      final reason = await VkToken.save('tok_123');
      expect(reason, isNotNull);
      expect(reason, contains('не удалось сохранить'));
      expect(LogService().lines.last, contains('Токен не сохранён'));
    });
  });
}
