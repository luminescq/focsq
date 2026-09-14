// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/foundation.dart';

class AppVisibility {
  AppVisibility._();

  static final AppVisibility instance = AppVisibility._();

  final ValueNotifier<bool> visible = ValueNotifier<bool>(true);

  void setVisible(bool value) {
    if (visible.value == value) return;
    visible.value = value;
  }
}
