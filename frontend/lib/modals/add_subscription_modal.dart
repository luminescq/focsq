// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import 'subscription_import.dart';

/// Модальное окно добавления подписки (псевдоним для [SubscriptionImportModal]).
class AddSubscriptionModal {
  AddSubscriptionModal._();

  static void show(BuildContext context) {
    SubscriptionImportModal.show(context);
  }
}
