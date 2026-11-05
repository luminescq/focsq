// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/split_tunnel_settings.dart';

export '../models/split_tunnel_settings.dart';

// [FOCSQ] Единый экземпляр связывает редактор и подключение, сохраняя порядок записей.
class SplitTunnelStore {
  static final SplitTunnelFileStore _store = SplitTunnelFileStore(
    directory: getApplicationSupportDirectory,
  );

  static Future<SplitTunnelSettings> load() => _store.load();
  static Future<void> save(SplitTunnelSettings settings) =>
      _store.save(settings);
}

// [FOCSQ] Каталог внедряется в тестах; рабочий файл не зависит от пути к EXE или cwd.
class SplitTunnelFileStore {
  final Future<Directory> Function() directory;
  Future<void> _pending = Future.value();

  SplitTunnelFileStore({required this.directory});

  Future<SplitTunnelSettings> load() async {
    await _pending;
    final file = File(
      '${(await directory()).path}${Platform.pathSeparator}split_tunnel.json',
    );
    if (!await file.exists()) return const SplitTunnelSettings();
    if (await file.length() > SplitTunnelSettings.maxFileBytes) {
      throw const FormatException('Файл split_tunnel.json слишком большой.');
    }
    // [FOCSQ] Ошибка чтения/JSON остаётся видимой: полный туннель не подменяет повреждённые правила.
    return SplitTunnelSettings.fromJsonString(await file.readAsString());
  }

  Future<void> save(SplitTunnelSettings settings) {
    final text = '${settings.toJsonString()}\n';
    if (utf8.encode(text).length > SplitTunnelSettings.maxFileBytes) {
      throw const FormatException(
        'Настройки split tunnelling превышают допустимый размер.',
      );
    }
    // [FOCSQ] Цепочка выполняет записи последовательно и остаётся рабочей после отказа диска.
    final write = _pending.then((_) => _write(text));
    _pending = write.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return write;
  }

  Future<void> _write(String text) async {
    final folder = await directory();
    await folder.create(recursive: true);
    final file = File(
      '${folder.path}${Platform.pathSeparator}split_tunnel.json',
    );
    final temporary = File('${file.path}.$pid.tmp');
    try {
      // [FOCSQ] Сначала flush временного файла, затем атомарная замена: читатель видит целый JSON.
      await temporary.writeAsString(text, flush: true);
      await temporary.rename(file.path);
    } finally {
      if (await temporary.exists()) await temporary.delete();
    }
  }
}
