import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:frontend/service/db.dart';
import 'package:isar/isar.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

class _TestPaths extends PathProviderPlatform {
  _TestPaths(this.directory);

  final String directory;

  @override
  Future<String?> getApplicationSupportPath() async => directory;
}

/// Временная Isar-база для тестов, не трогающая реальные данные пользователя.
class TestDatabase {
  TestDatabase._(this.isar, this._directory, this._originalPaths);

  final Isar isar;
  final Directory _directory;
  final PathProviderPlatform _originalPaths;

  String get directoryPath => _directory.path;

  static bool _coreReady = false;

  static Future<TestDatabase> open() async {
    if (!_coreReady) {
      final configFile = File('.dart_tool/package_config.json');
      final config = jsonDecode(await configFile.readAsString()) as Map;
      final package = (config['packages'] as List).cast<Map>().singleWhere(
        (package) => package['name'] == 'isar_flutter_libs',
      );
      final rootUri = package['rootUri'] as String;
      final packageRoot = configFile.absolute.uri.resolve(
        rootUri.endsWith('/') ? rootUri : '$rootUri/',
      );
      final library = Platform.isWindows
          ? 'windows/isar.dll'
          : 'linux/libisar.so';
      await Isar.initializeIsarCore(
        libraries: {Abi.current(): packageRoot.resolve(library).toFilePath()},
        download: false,
      );
      _coreReady = true;
    }

    final directory = await Directory.systemTemp.createTemp('focsq-test-');
    final originalPaths = PathProviderPlatform.instance;
    PathProviderPlatform.instance = _TestPaths(directory.path);
    await DbService.init();
    return TestDatabase._(Isar.getInstance()!, directory, originalPaths);
  }

  Future<void> clear() => isar.writeTxn(() => isar.clear());

  Future<void> close() async {
    await isar.close(deleteFromDisk: true);
    PathProviderPlatform.instance = _originalPaths;
    if (await _directory.exists()) {
      await _directory.delete(recursive: true);
    }
  }
}
