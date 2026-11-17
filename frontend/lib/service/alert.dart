// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import '../l10n/app_locale.dart';
import '../modals/ban_disclaimer.dart';
import '../widgets/custom_alert.dart';
import 'db.dart';
import 'import.dart';
import 'subscription.dart';

class AlertService {
  /// Показывает дисклеймер об ответственности за блокировку аккаунта, если
  /// он ещё не принят. При принятии флаг сохраняется в БД и больше не
  /// спрашивается. Возвращает true, если можно продолжать (принят ранее или
  /// принят сейчас), и false, если пользователь отказался.
  static Future<bool> ensureBanDisclaimerAccepted(BuildContext context) async {
    if ((await DbService.getSettings()).banDisclaimerAccepted) return true;
    if (!context.mounted) return false;

    final accepted = await BanDisclaimerModal.show(context);
    if (!accepted) return false;

    await DbService.updateSettings((s) => s..banDisclaimerAccepted = true);
    return true;
  }

  static void showImportResult(
    BuildContext context,
    ImportResult result, {
    OverlayState? overlay,
  }) {
    if (result.hasError) {
      final String message = switch (result.problem!) {
        ImportProblem.emptyClipboard => S.emptyClipboard,
        ImportProblem.readError => S.readError,
        ImportProblem.invalidFormat => S.invalidFormat,
        ImportProblem.saveFailed => S.saveFailed,
      };

      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: message,
        type: AlertType.warning,
        overlay: overlay,
      );
    } else {
      final lines = <String>[S.importedCount(result.imported)];
      if (result.duplicates > 0 && result.hashesApplied) {
        lines.add(
          result.duplicates == 1
              ? S.duplicateHashesOne
              : S.duplicatesHashesMany(result.duplicates),
        );
      } else if (result.duplicates > 0) {
        lines.add(S.skippedDuplicates(result.duplicates));
      } else if (result.hashesApplied) {
        lines.add(S.hashesUpdated);
      }
      if (result.invalid > 0) {
        lines.add(S.unrecognizedCount(result.invalid));
      }

      final bool nothingNew = result.imported == 0 && !result.hashesApplied;
      CustomAlert.show(
        context,
        title: nothingNew ? S.noNewTitle : S.successTitle,
        message: lines.join('\n'),
        type: nothingNew ? AlertType.info : AlertType.success,
        overlay: overlay,
      );
    }
  }

  static void showSubscriptionResult(
    BuildContext context,
    SubscriptionResult result,
  ) {
    // Обновление отменили (подписку удалили или сменили адрес) — это не
    // сетевая ошибка, показывать алерт не нужно.
    if (result.problem == SubscriptionProblem.cancelled) return;
    if (!result.success || result.hasError) {
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: result.errorMessage ?? S.subscriptionFetchFailed,
        type: AlertType.warning,
      );
      return;
    }

    final lines = <String>[
      if (result.importedCount > 0)
        S.subscriptionImportedCount(result.importedCount),
      if (result.updatedCount > 0)
        S.subscriptionUpdatedCount(result.updatedCount),
      if (result.skippedCount > 0)
        S.subscriptionSkippedCount(result.skippedCount),
      if (result.hashesApplied) S.subscriptionHashesApplied,
    ];
    final nothingNew = lines.isEmpty;
    if (nothingNew) lines.add(S.subscriptionNothingNew);

    CustomAlert.show(
      context,
      title: nothingNew ? S.noNewTitle : S.successTitle,
      message: lines.join('\n'),
      type: nothingNew ? AlertType.info : AlertType.success,
    );
  }

  static void showProfileAdded(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: S.successTitle,
      message: S.profileAdded(profileName),
      type: AlertType.success,
    );
  }

  static void showProfileUpdated(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: S.successTitle,
      message: S.profileUpdated(profileName),
      type: AlertType.success,
    );
  }

  static void showProfileDeleted(BuildContext context, String profileName) {
    CustomAlert.show(
      context,
      title: S.deletedTitle,
      message: S.profileDeleted(profileName),
      type: AlertType.success,
    );
  }

  static void showSubscriptionDeleted(
    BuildContext context,
    String subscriptionName,
  ) {
    CustomAlert.show(
      context,
      title: S.deletedTitle,
      message: S.subscriptionDeleted(subscriptionName),
      type: AlertType.success,
    );
  }
}
