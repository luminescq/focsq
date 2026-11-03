import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/modals/profile_form_components.dart';
import 'package:frontend/models/app_settings.dart';
import 'package:frontend/service/worker_limits.dart';

void main() {
  group('CSQTT 2.5 worker compatibility', () {
    test('legacy 162-worker profile is rejected with editing instructions', () {
      final issue = workerCountIssue(162);
      expect(issue, contains('162'));
      expect(issue, contains('99'));
      expect(issue, contains('Откройте профиль'));
    });

    test(
      'usable server maximum is a complete group below the 100-worker cap',
      () {
        expect(workerCountIssue(99), isNull);
        expect(workerCountIssue(100), isNotNull);
        expect(normalizeWorkerCount(162), 99);
        expect(normalizeWorkerCount(100), 99);
        expect(normalizeWorkerCount(99) % 9, 0);
      },
    );

    test('normal mode keeps 90 while extra mode allows 99', () {
      final settings = AppSettings();
      expect(resolveMaxPowerThreads(settings), 90);
      settings.extraThreads = true;
      expect(resolveMaxPowerThreads(settings), 99);
    });

    test('manual hashes limit complete worker groups under the server cap', () {
      final settings = AppSettings()
        ..extraThreads = true
        ..hashes = 'Ручной'
        ..customHashes = 'one,two,three';
      expect(resolveMaxPowerThreads(settings), 81);
      settings.customHashes = 'one,two,three,four';
      expect(resolveMaxPowerThreads(settings), 99);
    });

    test(
      'default and partial-group requests match core group normalization',
      () {
        expect(normalizeWorkerCount(null), 9);
        expect(normalizeWorkerCount(0), 9);
        expect(normalizeWorkerCount(28), 27);
        expect(normalizeWorkerCount(90), 90);
      },
    );
  });
}
