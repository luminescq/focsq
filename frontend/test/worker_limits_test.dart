import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/app_settings.dart';
import 'package:frontend/service/worker_limits.dart';

void main() {
  group('WorkerLimits.normalizeCount', () {
    test('null → 10, значения 0..5 → 5, дальше кратно 10', () {
      expect(WorkerLimits.normalizeCount(null), 10);
      expect(WorkerLimits.normalizeCount(0), 5);
      expect(WorkerLimits.normalizeCount(5), 5);
      expect(WorkerLimits.normalizeCount(6), 10);
      expect(WorkerLimits.normalizeCount(10), 10);
    });

    test('некратные 10 округляются вниз, как в ядре', () {
      expect(WorkerLimits.normalizeCount(11), 10);
      expect(WorkerLimits.normalizeCount(17), 10);
      expect(WorkerLimits.normalizeCount(18), 10);
      expect(WorkerLimits.normalizeCount(19), 10);
      expect(WorkerLimits.normalizeCount(20), 20);
      expect(WorkerLimits.normalizeCount(99), 90);
    });

    test('больше 100 обрезается до максимума', () {
      expect(WorkerLimits.normalizeCount(101), 100);
      expect(WorkerLimits.normalizeCount(9999), 100);
    });
  });

  group('WorkerLimits.maxForHashes', () {
    test('максимум не зависит от числа хешей', () {
      expect(WorkerLimits.maxForHashes(1), 100);
      expect(WorkerLimits.maxForHashes(2), 100);
      expect(WorkerLimits.maxForHashes(6), 100);
      expect(WorkerLimits.maxForHashes(100), 100);
    });
  });

  group('WorkerLimits.maxPower', () {
    test('без ручных хешей — 100 при любом extraThreads', () {
      final settings = AppSettings()..hashes = 'Авто ВК';
      expect(WorkerLimits.maxPower(settings), 100);
      settings.extraThreads = true;
      expect(WorkerLimits.maxPower(settings), 100);
    });

    test('ручной режим не ограничивает максимум числом хешей', () {
      final settings = AppSettings()
        ..hashes = 'Ручной'
        ..customHashes = 'a,b';
      expect(WorkerLimits.maxPower(settings), 100);

      settings.customHashes = 'a,b,c,d';
      expect(WorkerLimits.maxPower(settings), 100);

      settings.customHashes = 'a,b,c,d,e,f,g';
      expect(WorkerLimits.maxPower(settings), 100);

      settings.customHashes = 'a';
      expect(WorkerLimits.maxPower(settings), 100);
    });

    test('пустые ручные хеши не ограничивают максимум', () {
      final settings = AppSettings()
        ..hashes = 'Ручной'
        ..customHashes = ' , ';
      expect(WorkerLimits.maxPower(settings), 100);
    });
  });

  group('WorkerLimits.maxPowerForProfile', () {
    test('лимит подписки обрезает максимум и нормализуется', () {
      final settings = AppSettings()..extraThreads = true;
      expect(WorkerLimits.maxPowerForProfile(settings, threadsLimit: 150), 100);
      expect(WorkerLimits.maxPowerForProfile(settings, threadsLimit: 45), 40);
      expect(WorkerLimits.maxPowerForProfile(settings, threadsLimit: 5), 5);
      expect(WorkerLimits.maxPowerForProfile(settings), 100);
    });
  });
}
