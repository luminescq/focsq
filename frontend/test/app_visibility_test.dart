import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/app_visibility.dart';

void main() {
  tearDown(() {
    AppVisibility.instance.setVisible(true);
    AppVisibility.instance.setFocused(true);
  });

  group('AppVisibility', () {
    test('по умолчанию окно видимо и в фокусе', () {
      expect(AppVisibility.instance.visible.value, isTrue);
      expect(AppVisibility.instance.focused.value, isTrue);
      expect(AppVisibility.instance.active, isTrue);
    });

    test('смена значения уведомляет слушателей один раз', () {
      var notifications = 0;
      AppVisibility.instance.visible.addListener(() => notifications++);

      AppVisibility.instance.setVisible(false);
      expect(AppVisibility.instance.visible.value, isFalse);
      expect(notifications, 1);

      AppVisibility.instance.setVisible(true);
      expect(AppVisibility.instance.visible.value, isTrue);
      expect(notifications, 2);
    });

    test(
      'повторная установка того же значения молчит (нет лишних пульсов)',
      () {
        var notifications = 0;
        AppVisibility.instance.visible.addListener(() => notifications++);

        AppVisibility.instance.setVisible(true); // уже true
        AppVisibility.instance.setVisible(true);

        expect(notifications, isZero);
      },
    );

    test('active выключается без фокуса и снова включается', () {
      AppVisibility.instance.setFocused(false);
      expect(AppVisibility.instance.active, isFalse);

      AppVisibility.instance.setFocused(true);
      expect(AppVisibility.instance.active, isTrue);
    });

    test('active выключается, когда окно скрыто, даже в фокусе', () {
      AppVisibility.instance.setVisible(false);
      expect(AppVisibility.instance.active, isFalse);
    });

    test('activity уведомляет при смене фокуса', () {
      var notifications = 0;
      void listener() => notifications++;
      AppVisibility.instance.activity.addListener(listener);

      AppVisibility.instance.setFocused(false);
      expect(notifications, 1);

      AppVisibility.instance.setFocused(true);
      expect(notifications, 2);

      AppVisibility.instance.activity.removeListener(listener);
    });
  });
}
