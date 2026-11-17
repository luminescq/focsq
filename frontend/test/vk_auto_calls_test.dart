import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/vk/auto_calls.dart';

/// Математика режима «Авто API»: сколько звонков нужно под число воркеров.
/// Схема 2.5.0: n звонков покрывают n * 1 группу * 10 воркеров, n от 1 до 6.
void main() {
  group('VkAutoCallsManager.callCountForWorkers', () {
    test('ноль и один воркер — один звонок', () {
      expect(VkAutoCallsManager.callCountForWorkers(0), 1);
      expect(VkAutoCallsManager.callCountForWorkers(1), 1);
    });

    test('границы диапазонов: 10, 20, 30, 40, 50, 60', () {
      expect(VkAutoCallsManager.callCountForWorkers(10), 1);
      expect(VkAutoCallsManager.callCountForWorkers(20), 2);
      expect(VkAutoCallsManager.callCountForWorkers(30), 3);
      expect(VkAutoCallsManager.callCountForWorkers(40), 4);
      expect(VkAutoCallsManager.callCountForWorkers(50), 5);
      expect(VkAutoCallsManager.callCountForWorkers(60), 6);
    });

    test('внутри диапазона округляет вверх до нужного числа звонков', () {
      expect(VkAutoCallsManager.callCountForWorkers(11), 2);
      expect(VkAutoCallsManager.callCountForWorkers(21), 3);
      expect(VkAutoCallsManager.callCountForWorkers(100), 6);
    });

    test('сверх максимума — потолок в 6 звонков', () {
      expect(VkAutoCallsManager.callCountForWorkers(61), 6);
      expect(VkAutoCallsManager.callCountForWorkers(1000), 6);
    });

    test('VkCallsBatch: недобор звонков разрешает перераспределение', () {
      const batch = VkCallsBatch(
        calls: [
          VkAutoCall(callId: 'a', hash: 'h1'),
          VkAutoCall(callId: 'b', hash: 'h2'),
        ],
        requestedCalls: 4,
      );
      expect(batch.hashes, ['h1', 'h2']);
      expect(batch.callIds, ['a', 'b']);
      expect(batch.needsWorkerRedistribution, isTrue);

      const full = VkCallsBatch(
        calls: [VkAutoCall(callId: 'a', hash: 'h1')],
        requestedCalls: 1,
      );
      expect(full.needsWorkerRedistribution, isFalse);
    });
  });

  group('VkApiClient.tokenInvalidCodes', () {
    test('коды невалидного токена', () {
      // 4/5/27/28: просрочен, неверный, privacy, токен приложения
      expect(VkApiClient.tokenInvalidCodes, containsAll(<int>[4, 5, 27, 28]));
    });
  });
}
