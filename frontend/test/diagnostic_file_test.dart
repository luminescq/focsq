import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/service/diagnostic_file.dart';

void main() {
  late Directory directory;
  DiagnosticFileLog? log;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('focsq-log-test-');
  });
  tearDown(() async {
    await log?.close();
    log = null;
    await directory.delete(recursive: true);
  });

  test(
    'flush persists all records in order, including final pending batch',
    () async {
      log = await DiagnosticFileLog.open(directory: directory);
      for (var i = 0; i < 100; i++) {
        log!.add('worker=$i peer_rx=$i');
      }
      await log!.flush();
      expect(
        await File(log!.filePath).readAsLines(),
        List.generate(100, (i) => 'worker=$i peer_rx=$i'),
      );
      log!.add('final result');
      await log!.close();
      expect((await File(log!.filePath).readAsLines()).last, 'final result');
    },
  );

  test('records reach disk without a manual flush', () async {
    log = await DiagnosticFileLog.open(
      directory: directory,
      flushInterval: const Duration(milliseconds: 10),
    );
    log!.add('handshake timeout');
    await Future<void>.delayed(const Duration(milliseconds: 150));
    expect(await File(log!.filePath).readAsString(), 'handshake timeout\n');
  });

  test('rotation bounds disk usage and preserves unrelated files', () async {
    final unrelated = File('${directory.path}/keep.log');
    await unrelated.writeAsString('user file');
    log = await DiagnosticFileLog.open(
      directory: directory,
      maxFileBytes: 128,
      maxFiles: 3,
    );
    for (var i = 0; i < 30; i++) {
      log!.add('record $i: ${'x' * 80}');
    }
    await log!.flush();
    final files = await directory
        .list()
        .where((f) => f is File && f.uri.pathSegments.last.startsWith('focsq-'))
        .cast<File>()
        .toList();
    expect(files, hasLength(3));
    for (final file in files) {
      expect(await file.length(), lessThanOrEqualTo(128));
    }
    expect(await unrelated.readAsString(), 'user file');
    expect(await File(log!.filePath).readAsString(), contains('record 29:'));
  });

  test('oversized UTF-8 records are bounded and explicitly marked', () async {
    log = await DiagnosticFileLog.open(directory: directory, maxFileBytes: 128);
    log!.add('Я' * 200);
    await log!.flush();
    expect(await File(log!.filePath).length(), lessThanOrEqualTo(128));
    expect(
      await File(log!.filePath).readAsString(),
      contains('[line truncated]'),
    );
  });

  test(
    'bounded backlog reports dropped records instead of growing forever',
    () async {
      log = await DiagnosticFileLog.open(directory: directory);
      for (var i = 0; i < 40; i++) {
        log!.add('x' * 60000);
      }
      await log!.flush();
      final text = await File(log!.filePath).readAsString();
      expect(text, contains('buffer overflow: dropped='));
      expect(text.length, lessThan(1100000));
    },
  );

  test(
    'a disk failure reports once and does not escape into the log callback',
    () async {
      final errors = <Object>[];
      log = await DiagnosticFileLog.open(
        directory: directory,
        maxFileBytes: 128,
        onError: errors.add,
      );
      await Directory(
        log!.filePath.replaceFirst('.log', '-part1.log'),
      ).create();
      log!.add('x' * 100);
      log!.add('y' * 100);
      await log!.flush();
      log!.add('after failure');
      await log!.flush();
      expect(errors, hasLength(1));
    },
  );
}
