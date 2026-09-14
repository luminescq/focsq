import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/app_visibility.dart';

void main() {
  tearDown(() => AppVisibility.instance.setVisible(true));

  group('AppVisibility', () {
    test('по умолчанию окно видимо', () {
      expect(AppVisibility.instance.visible.value, isTrue);
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

    test('повторная установка того же значения молчит (нет лишних пульсов)',
        () {
      var notifications = 0;
      AppVisibility.instance.visible.addListener(() => notifications++);

      AppVisibility.instance.setVisible(true); // уже true
      AppVisibility.instance.setVisible(true);

      expect(notifications, isZero);
    });
  });
}
