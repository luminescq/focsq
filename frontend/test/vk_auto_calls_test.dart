import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/vk/auto_calls.dart';

/// Математика режима «Авто API»: сколько звонков нужно под число воркеров.
/// Формула из оригинала (VkAutoCallsManager.kt): n звонков покрывают
/// n * 3 групп * 9 воркеров, n от 1 до 6.
void main() {
  group('VkAutoCallsManager.callCountForWorkers', () {
    test('ноль и один воркер — один звонок', () {
      expect(VkAutoCallsManager.callCountForWorkers(0), 1);
      expect(VkAutoCallsManager.callCountForWorkers(1), 1);
    });

    test('границы диапазонов: 27, 54, 81, 108, 135, 162', () {
      expect(VkAutoCallsManager.callCountForWorkers(27), 1);
      expect(VkAutoCallsManager.callCountForWorkers(54), 2);
      expect(VkAutoCallsManager.callCountForWorkers(81), 3);
      expect(VkAutoCallsManager.callCountForWorkers(108), 4);
      expect(VkAutoCallsManager.callCountForWorkers(135), 5);
      expect(VkAutoCallsManager.callCountForWorkers(162), 6);
    });

    test('внутри диапазона округляет вверх до нужного числа звонков', () {
      expect(VkAutoCallsManager.callCountForWorkers(28), 2);
      expect(VkAutoCallsManager.callCountForWorkers(55), 3);
      expect(VkAutoCallsManager.callCountForWorkers(100), 4);
    });

    test('сверх максимума — потолок в 6 звонков', () {
      expect(VkAutoCallsManager.callCountForWorkers(163), 6);
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
