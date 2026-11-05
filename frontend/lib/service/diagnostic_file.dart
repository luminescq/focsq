// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// Bounded, rotating output for lines already sanitized by LogService.
class DiagnosticFileLog {
  DiagnosticFileLog._(
    this.directory,
    this.filePath,
    this._file,
    this.maxFileBytes,
    this.maxFiles,
    this.flushInterval,
    this.onError,
  );

  static const _maxBufferedBytes = 1024 * 1024;
  static const _maxLineBytes = 64 * 1024;
  static final _ownedName = RegExp(r'^focsq-\d+-\d+(?:-part\d+)?\.log$');

  final Directory directory;
  final String filePath;
  final int maxFileBytes;
  final int maxFiles;
  final Duration flushInterval;
  final void Function(Object error)? onError;
  RandomAccessFile _file;
  final List<List<int>> _pending = [];
  Future<void> _tail = Future<void>.value();
  Timer? _timer;
  int _bufferedBytes = 0;
  int _fileBytes = 0;
  int _part = 0;
  int _dropped = 0;
  bool _closed = false;
  bool _failed = false;

  static Future<DiagnosticFileLog> open({
    required Directory directory,
    int maxFileBytes = 5 * 1024 * 1024,
    int maxFiles = 5,
    Duration flushInterval = const Duration(milliseconds: 250),
    void Function(Object error)? onError,
  }) async {
    if (maxFileBytes < 128 || maxFiles < 2) {
      throw ArgumentError('At least 128 bytes and two log files are required');
    }
    await directory.create(recursive: true);
    final name = 'focsq-${DateTime.now().microsecondsSinceEpoch}-$pid.log';
    final path = '${directory.path}${Platform.pathSeparator}$name';
    final file = await File(path).open(mode: FileMode.write);
    final output = DiagnosticFileLog._(
      directory,
      path,
      file,
      maxFileBytes,
      maxFiles,
      flushInterval,
      onError,
    );
    try {
      await output._prune();
    } catch (_) {
      await file.close();
      rethrow;
    }
    return output;
  }

  void add(String sanitizedLine) {
    if (_closed || _failed) return;
    var bytes = utf8.encode('$sanitizedLine\n');
    final limit = maxFileBytes < _maxLineBytes ? maxFileBytes : _maxLineBytes;
    if (bytes.length > limit) {
      final marker = utf8.encode(' [line truncated]\n');
      final prefix = utf8.decode(
        bytes.sublist(0, limit - marker.length - 3),
        allowMalformed: true,
      );
      bytes = utf8.encode('$prefix [line truncated]\n');
    }
    if (_bufferedBytes + bytes.length > _maxBufferedBytes) {
      _dropped++;
      return;
    }
    _pending.add(bytes);
    _bufferedBytes += bytes.length;
    _timer ??= Timer(flushInterval, () {
      _timer = null;
      _enqueue();
    });
  }

  void _enqueue() {
    if (_pending.isEmpty && _dropped == 0) return;
    final batch = List<List<int>>.of(_pending);
    _pending.clear();
    if (_dropped != 0) {
      final warning = utf8.encode(
        '${DateTime.now().toIso8601String()} [LOG] buffer overflow: '
        'dropped=$_dropped\n',
      );
      batch.add(warning);
      _bufferedBytes += warning.length;
      _dropped = 0;
    }
    final batchBytes = batch.fold<int>(0, (n, line) => n + line.length);
    _tail = _tail
        .then((_) async {
          if (_failed) return;
          for (final line in batch) {
            if (_fileBytes > 0 && _fileBytes + line.length > maxFileBytes) {
              await _rotate();
            }
            await _file.writeFrom(line);
            _fileBytes += line.length;
          }
          await _file.flush();
        })
        .catchError((Object error) {
          if (!_failed) {
            _failed = true;
            onError?.call(error);
          }
        })
        .whenComplete(() {
          _bufferedBytes -= batchBytes;
        });
  }

  Future<void> _rotate() async {
    await _file.close();
    final archived = filePath.replaceFirst(
      RegExp(r'\.log$'),
      '-part${++_part}.log',
    );
    await File(filePath).rename(archived);
    _file = await File(filePath).open(mode: FileMode.write);
    _fileBytes = 0;
    await _prune();
  }

  Future<void> _prune() async {
    final files = <File>[];
    await for (final entry in directory.list(followLinks: false)) {
      if (entry is! File || entry.path == filePath) continue;
      final name = entry.uri.pathSegments.last;
      if (_ownedName.hasMatch(name)) files.add(entry);
    }
    final dated = await Future.wait(
      files.map(
        (file) async => (file: file, modified: (await file.stat()).modified),
      ),
    );
    dated.sort((a, b) {
      final byTime = b.modified.compareTo(a.modified);
      return byTime != 0 ? byTime : b.file.path.compareTo(a.file.path);
    });
    for (final old in dated.skip(maxFiles - 1)) {
      await old.file.delete();
    }
  }

  Future<void> flush() {
    _timer?.cancel();
    _timer = null;
    _enqueue();
    return _tail;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    await flush();
    try {
      await _file.close();
    } catch (_) {
      // Rotation may already have closed the handle after a filesystem error.
    }
  }
}
