// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/split_tunnel.dart';

void main() {
  // [FOCSQ] Формат и нормализация проверяются без ядра, сети и пользовательских файлов.
  group('split tunnel rules', () {
    test('old installation starts with all categories off', () {
      const settings = SplitTunnelSettings();
      expect(settings.enabled, isFalse);
      expect(
        SplitTunnelSettings.fromJsonString(settings.toJsonString()).enabled,
        isFalse,
      );
    });

    test('mode switch preserves both independent lists', () {
      const rules = SplitTunnelRules(
        mode: SplitTunnelMode.exclude,
        exclude: ['example.com'],
        include: ['work.example'],
      );
      final settings = SplitTunnelSettings(
        domains: rules.copyWith(mode: SplitTunnelMode.include),
      );
      final reloaded = SplitTunnelSettings.fromJsonString(
        settings.toJsonString(),
      );
      expect(reloaded.domains.include, ['work.example']);
      expect(reloaded.domains.exclude, ['example.com']);
      expect(reloaded.domains.mode, SplitTunnelMode.include);
    });

    test(
      'domain unicode, case, wildcard and trailing dot share canonical suffix',
      () {
        expect(
          parseSplitEntries(
            SplitTunnelKind.domains,
            'ПРИМЕР.РФ *.пример.рф. EXAMPLE.com bücher.de mañana.com',
          ),
          [
            'xn--e1afmkfd.xn--p1ai',
            'example.com',
            'xn--bcher-kva.de',
            'xn--maana-pta.com',
          ],
        );
      },
    );

    test('URLs, IPv4 and invalid DNS labels are rejected', () {
      for (final entry in [
        'https://example.com',
        'example.com:443',
        '1.2.3.4',
        'a..example',
        '-a.example',
        'a_.example',
        'user@example.com',
        'a/b',
      ]) {
        expect(
          () => parseSplitEntries(SplitTunnelKind.domains, entry),
          throwsFormatException,
          reason: entry,
        );
      }
    });

    test(
      'IPv4 CIDR canonicalizes host bits and permits ranges with spaces',
      () {
        expect(
          parseSplitEntries(
            SplitTunnelKind.ips,
            '10.2.3.4/8; 192.0.2.1 - 192.0.2.20\n1.2.3.4, 001.002.003.004 0.0.0.0/0',
          ),
          ['10.0.0.0/8', '192.0.2.1-192.0.2.20', '1.2.3.4', '0.0.0.0/0'],
        );
      },
    );

    test('invalid IPv4 and reversed ranges are rejected', () {
      for (final entry in [
        '10.0.0.256',
        '10.0.0.1/33',
        '10.0.0.1/-1',
        '10.0.0.1/xyz',
        '::1',
        '10.0.0.2-10.0.0.1',
        '10.0.0',
        '10.0.0.1/8/16',
      ]) {
        expect(
          () => parseSplitEntries(SplitTunnelKind.ips, entry),
          throwsFormatException,
          reason: entry,
        );
      }
    });

    test('application paths preserve spaces and deduplicate ignoring case', () {
      expect(
        parseSplitEntries(
          SplitTunnelKind.apps,
          '"C:\\Program Files\\Browser\\Browser.exe"\nc:/program files/browser/browser.EXE\n'
          '\\\\server\\share\\App.exe',
        ),
        [r'C:\Program Files\Browser\Browser.exe', r'\\server\share\App.exe'],
      );
    });

    test(
      'relative paths, parent traversal and command arguments are rejected',
      () {
        for (final entry in [
          'browser.exe',
          r'C:browser.exe',
          r'C:\Apps\..\browser.exe',
          '"C:\\Apps\\browser.exe" --private',
          r'C:\Apps\browser.txt',
        ]) {
          expect(
            () => parseSplitEntries(SplitTunnelKind.apps, entry),
            throwsFormatException,
            reason: entry,
          );
        }
      },
    );

    test(
      'empty allow list and malformed saved contract remain visible errors',
      () {
        expect(
          () => const SplitTunnelSettings(
            apps: SplitTunnelRules(mode: SplitTunnelMode.include),
          ).toJsonString(),
          throwsFormatException,
        );
        for (final text in [
          '{}',
          '{"version":2}',
          '{"version":1,"apps":{}}',
          '[]',
          'bad json',
        ]) {
          expect(
            () => SplitTunnelSettings.fromJsonString(text),
            throwsFormatException,
          );
        }
      },
    );

    test(
      'list length is bounded and normalization returns immutable entries',
      () {
        expect(
          () => normalizeSplitEntries(
            SplitTunnelKind.domains,
            List.generate(
              SplitTunnelSettings.maxEntries + 1,
              (i) => 'host$i.example',
            ),
          ),
          throwsFormatException,
        );
        final entries = parseSplitEntries(
          SplitTunnelKind.domains,
          'example.com',
        );
        expect(() => entries.add('other.example'), throwsUnsupportedError);
      },
    );
  });

  // [FOCSQ] Реальная файловая замена проверяет Windows rename и порядок нескольких сохранений.
  group('split tunnel file store', () {
    late Directory directory;
    late SplitTunnelFileStore store;
    setUp(() async {
      directory = await Directory.systemTemp.createTemp('focsq-split-test-');
      store = SplitTunnelFileStore(directory: () async => directory);
    });
    tearDown(() async => directory.delete(recursive: true));

    test(
      'missing file keeps full tunnel; malformed file does not reset it',
      () async {
        expect((await store.load()).enabled, isFalse);
        await File('${directory.path}/split_tunnel.json')
            .writeAsString('{broken');
        await expectLater(store.load(), throwsFormatException);
      },
    );

    test(
      'concurrent saves serialize and atomically replace existing file',
      () async {
        const first = SplitTunnelSettings(
          domains: SplitTunnelRules(
            mode: SplitTunnelMode.exclude,
            exclude: ['first.example'],
          ),
        );
        const last = SplitTunnelSettings(
          ips: SplitTunnelRules(
            mode: SplitTunnelMode.include,
            include: ['10.0.0.0/8'],
          ),
        );
        await store.save(const SplitTunnelSettings());
        await Future.wait([store.save(first), store.save(last)]);
        final reloaded = await store.load();
        expect(reloaded.ips.mode, SplitTunnelMode.include);
        expect(reloaded.ips.include, ['10.0.0.0/8']);
        expect(reloaded.domains.mode, SplitTunnelMode.off);
        expect(
          directory.listSync().whereType<File>().map(
            (file) => file.uri.pathSegments.last,
          ),
          ['split_tunnel.json'],
        );
      },
    );

    test('write queue recovers after failed save', () async {
      var fail = true;
      final recovering = SplitTunnelFileStore(
        directory: () async {
          if (fail) throw const FileSystemException('Synthetic write failure');
          return directory;
        },
      );
      await expectLater(
        recovering.save(const SplitTunnelSettings()),
        throwsA(isA<FileSystemException>()),
      );
      fail = false;
      await recovering.save(const SplitTunnelSettings());
      expect((await recovering.load()).enabled, isFalse);
    });

    test('oversize file is rejected before JSON parsing', () async {
      await File('${directory.path}/split_tunnel.json')
          .writeAsString(' ' * (SplitTunnelSettings.maxFileBytes + 1));
      await expectLater(store.load(), throwsFormatException);
    });
  });
}
