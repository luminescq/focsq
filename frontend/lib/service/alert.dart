// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import '../widgets/custom_alert.dart';
import 'import.dart';

class AlertService {
  static void showImportResult(BuildContext context, ImportResult result) {
    if (result.hasError) {
      final String message = switch (result.problem!) {
        ImportProblem.emptyClipboard => 'Буфер обмена пуст.',
        ImportProblem.readError => 'Не удалось прочитать буфер обмена.',
        ImportProblem.invalidFormat => 'Не найдено ни одной корректной ссылки.',
        ImportProblem.saveFailed =>
          'Ссылки распознаны, но произошла ошибка при сохранении.',
      };

      CustomAlert.show(
        context,
        title: 'Ошибка',
        message: message,
        type: AlertType.warning,
      );
    } else {
      final lines = <String>['Импортировано профилей: ${result.imported}'];
      if (result.duplicates > 0) {
        lines.add('Пропущено дубликатов: ${result.duplicates}');
      }
      if (result.invalid > 0) {
        lines.add('Нераспознано ссылок: ${result.invalid}');
      }

      final bool nothingNew = result.imported == 0;
      CustomAlert.show(
        context,
        title: nothingNew ? 'Новых профилей нет' : 'Успешно',
        message: lines.join('\n'),
        type: nothingNew ? AlertType.info : AlertType.success,
      );
    }
  }

  static void showProfileAdded(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: 'Успешно',
      message: 'Профиль «$profileName» добавлен',
      type: AlertType.success,
    );
  }

  static void showProfileUpdated(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: 'Успешно',
      message: 'Профиль «$profileName» сохранён',
      type: AlertType.success,
    );
  }

  static void showProfileDeleted(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: 'Удалено',
      message: 'Профиль «$profileName» удалён',
      type: AlertType.success,
    );
  }
}
