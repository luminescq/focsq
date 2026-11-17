import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/app_visibility.dart';
import 'package:frontend/widgets/mesh_background.dart';

void main() {
  testWidgets('half-res background renders a non-uniform frame', (
    tester,
  ) async {
    final key = GlobalKey();
    await tester.binding.setSurfaceSize(const Size(400, 300));
    AppVisibility.instance.setVisible(true);

    await tester.pumpWidget(
      MaterialApp(
        home: RepaintBoundary(key: key, child: const MeshBackground()),
      ),
    );

    // Дать асинхронному FragmentProgram.fromAsset загрузиться и отрисоваться.
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }

    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = await tester.runAsync(() => boundary.toImage(pixelRatio: 1));
    expect(image, isNotNull);
    final data = await tester.runAsync(
      () => image!.toByteData(format: ImageByteFormat.rawRgba),
    );
    expect(data, isNotNull);

    final bytes = data!.buffer.asUint8List();
    final colors = <int>{};
    var opaque = 0;
    var total = 0;
    for (var i = 0; i + 3 < bytes.length; i += 4) {
      total++;
      if (bytes[i + 3] == 255) opaque++;
      colors.add((bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2]);
    }
    expect(
      colors.length,
      greaterThan(10),
      reason: 'фон должен быть не плоским',
    );
    expect(
      opaque / total,
      greaterThan(0.9),
      reason: 'offscreen-кадр шейдера должен быть непрозрачным и отрисованным',
    );

    image!.dispose();
    AppVisibility.instance.setVisible(false);
    await tester.pumpWidget(const SizedBox());
    await tester.binding.setSurfaceSize(null);
    AppVisibility.instance.setVisible(true);
  });
}
