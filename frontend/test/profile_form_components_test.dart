import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/modals/profile_form_components.dart';

void main() {
  group('sliderDivisionsFor', () {
    test('без делений, когда слайдеру нечего делить', () {
      expect(sliderDivisionsFor(10), isNull);
      expect(sliderDivisionsFor(0), isNull);
      expect(sliderDivisionsFor(-5), isNull);
    });

    test('иначе всегда положительное число делений', () {
      expect(sliderDivisionsFor(10.5), 1);
      expect(sliderDivisionsFor(20), 1);
      expect(sliderDivisionsFor(100), 9);
      expect(sliderDivisionsFor(160), 15);
    });
  });
}
