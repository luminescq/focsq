// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const int minWorkerCount = 9;
// CSQTT 2.5 accepts at most 100 workers; this client starts whole groups of 9.
const int maxWorkerCount = 99;

int normalizeWorkerCount(int? requested) =>
    (requested ?? minWorkerCount).clamp(minWorkerCount, maxWorkerCount) ~/
    minWorkerCount *
    minWorkerCount;

String? workerCountIssue(int? requested) {
  if (requested != null && requested > maxWorkerCount) {
    return 'В профиле указано $requested потоков. Для сервера CSQTT 2.5.0 '
        'в FOCSQ доступно не более $maxWorkerCount. Откройте профиль и '
        'уменьшите число потоков.';
  }
  return null;
}
