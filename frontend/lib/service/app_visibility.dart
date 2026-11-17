// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/foundation.dart';

class AppVisibility {
  AppVisibility._();

  static final AppVisibility instance = AppVisibility._();

  /// Окно не скрыто в трей и не свёрнуто.
  final ValueNotifier<bool> visible = ValueNotifier<bool>(true);

  /// Окно в фокусе: приложение не за другим окном.
  final ValueNotifier<bool> focused = ValueNotifier<bool>(true);

  /// Один сигнал на оба состояния — удобно для ListenableBuilder.
  late final Listenable activity = Listenable.merge([visible, focused]);

  /// Анимации имеют смысл только когда окно видно И в фокусе.
  bool get active => visible.value && focused.value;

  void setVisible(bool value) {
    if (visible.value == value) return;
    visible.value = value;
  }

  void setFocused(bool value) {
    if (focused.value == value) return;
    focused.value = value;
  }
}
