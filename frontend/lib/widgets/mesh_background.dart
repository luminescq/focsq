// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:math' as math;
import 'dart:ui'
    as ui
    show Canvas, Gradient, Image, PictureRecorder, PointMode, TileMode;
import 'package:flutter/material.dart';
import '../service/app_visibility.dart';
import '../theme/app_colors.dart';

/// Фон приложения — три мягких цветных пятна + затемнение.
///
/// Профиль пятна (логистический, как в прежнем фрагментном шейдере)
/// запекается в текстуру-спрайт один раз при старте/смене размера окна.
/// В каждом кадре остаётся только сдвинуть три спрайта по тем же траекториям
/// и наложить затемнение — шейдер и offscreen-рендер в кадре не участвуют.
/// Поэтому анимация дешёвая, а вид остаётся прежним.
///
/// Кадры идут по таймеру 15 fps (пятна медленные). Пауза — через
/// AppVisibility.activity: таймер не тикает, когда окно скрыто в трее,
/// свёрнуто или не в фокусе.
///
/// Зерно — прежний painter-вид (_GrainPainter), НО отрендеренный один раз
/// в GPU-текстуру (toImageSync): ~100k точек point-примитивами каждый кадр
/// ели 30–50% GPU, а одна текстура — доли процента. Пересоздаётся только
/// при смене размера окна/dpr.

class MeshBackground extends StatefulWidget {
  const MeshBackground({super.key});

  @override
  State<MeshBackground> createState() => _MeshBackgroundState();
}

class _MeshBackgroundState extends State<MeshBackground> {
  static const int _loopMs = 25000;
  static const int _frameMs = 66; // 15 fps для фона — пятна медленные

  // Параметр прежнего вида: сигма BackdropFilter (логические пиксели).
  static const double _blurSigma = 120;

  // Спрайт генерируется в половинном разрешении и растягивается при выводе:
  // пятна всё равно мягкие, а памяти и растеризации вчетверо меньше.
  static const double _spriteScale = 0.5;

  // Хвост логистического профиля после radius + 3.5σ даёт alpha < 0.5%,
  // дальше обрезаем — визуально не отличить.
  static const double _tailSigmas = 3.5;
  static const int _spriteStops = 64;

  static const Color _blob1Color = Color.fromRGBO(250, 240, 228, 1);
  static const Color _blob2Color = Color.fromRGBO(255, 255, 255, 1);
  static const Color _blob3Color = Color.fromRGBO(247, 233, 210, 1);

  Timer? _timer;
  final Stopwatch _clock = Stopwatch()..start();
  final ValueNotifier<double> _phase = ValueNotifier<double>(0);
  List<_BlobSprite>? _blobs;
  double _spriteW = 0;
  double _spriteH = 0;
  ui.Image? _grainImage;
  int _grainW = 0;
  int _grainH = 0;

  @override
  void initState() {
    super.initState();
    _syncTimer();
    AppVisibility.instance.activity.addListener(_syncTimer);
  }

  void _syncTimer() {
    final active = AppVisibility.instance.active;
    if (active && _timer == null) {
      _timer = Timer.periodic(const Duration(milliseconds: _frameMs), _tick);
    } else if (!active && _timer != null) {
      _stopTimer();
    }
  }

  void _tick(Timer _) {
    if (!mounted || !AppVisibility.instance.active) return;
    // Фаза от часов — цикл не «дёргается» на паузе/восстановлении.
    _phase.value = (_clock.elapsedMilliseconds % _loopMs) / _loopMs;
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  // Спрайты зависят только от размера окна и dpr: пересоздаём при resize.
  void _ensureBlobs(double logicalW, double logicalH, double dpr) {
    final w = logicalW * dpr;
    final h = logicalH * dpr;
    if (w < 1 || h < 1) return;
    if (_blobs != null && _spriteW == w && _spriteH == h) return;

    _disposeBlobs();
    final maxDim = math.max(w, h);
    final sigma = _blurSigma * dpr;
    _blobs = [
      _makeBlobSprite(maxDim * 0.28, sigma, 0.75, _blob1Color),
      _makeBlobSprite(maxDim * 0.32, sigma, 0.65, _blob2Color),
      _makeBlobSprite(maxDim * 0.28, sigma, 0.35, _blob3Color),
    ];
    _spriteW = w;
    _spriteH = h;
  }

  _BlobSprite _makeBlobSprite(
    double diameter,
    double sigma,
    double alpha,
    Color color,
  ) {
    final radius = diameter * 0.5;
    final fullSize = diameter + 2 * _tailSigmas * sigma;
    final spritePx = math.max(2, (fullSize * _spriteScale).ceil());
    final gradientRadius = spritePx / 2.0;
    final radiusSprite = radius * _spriteScale;
    final sigmaSprite = sigma * _spriteScale;
    final center = Offset(gradientRadius, gradientRadius);

    // Логистический профиль прежнего шейдера, запечённый в радиальный
    // градиент. 64 отрезков достаточно: кривая гладкая, ошибка незаметна.
    final colors = <Color>[];
    final stops = <double>[];
    for (var i = 0; i <= _spriteStops; i++) {
      final t = i / _spriteStops;
      final dist = t * gradientRadius;
      final x = (dist - radiusSprite) / math.max(sigmaSprite, 0.001);
      final mask = (1.0 / (1.0 + math.exp(1.5 * x))) * alpha;
      colors.add(color.withValues(alpha: mask.clamp(0.0, 1.0).toDouble()));
      stops.add(t);
    }
    colors[_spriteStops] = color.withValues(alpha: 0.0);
    stops[_spriteStops] = 1.0;

    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(
      recorder,
      Rect.fromLTWH(0, 0, spritePx.toDouble(), spritePx.toDouble()),
    );
    canvas.drawCircle(
      center,
      gradientRadius,
      Paint()
        ..shader = ui.Gradient.radial(
          center,
          gradientRadius,
          colors,
          stops,
          ui.TileMode.clamp,
        ),
    );
    final picture = recorder.endRecording();
    final image = picture.toImageSync(spritePx, spritePx);
    picture.dispose();

    return _BlobSprite(
      image: image,
      diameter: diameter,
      fullSize: spritePx / _spriteScale,
    );
  }

  void _disposeBlobs() {
    for (final blob in _blobs ?? const <_BlobSprite>[]) {
      blob.image.dispose();
    }
    _blobs = null;
  }

  // Рендер зерна в GPU-текстуру (физическое разрешение), один раз.
  void _ensureGrainImage(double logicalW, double logicalH, double dpr) {
    final w = (logicalW * dpr).round();
    final h = (logicalH * dpr).round();
    if (_grainImage != null && _grainW == w && _grainH == h) return;

    _grainImage?.dispose();
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder, Offset.zero & Size(logicalW, logicalH));
    canvas.scale(dpr);
    _GrainPainter().paint(canvas, Size(logicalW, logicalH));
    final picture = recorder.endRecording();
    _grainImage = picture.toImageSync(w, h);
    picture.dispose();
    _grainW = w;
    _grainH = h;
  }

  @override
  void dispose() {
    AppVisibility.instance.activity.removeListener(_syncTimer);
    _stopTimer();
    _phase.dispose();
    _disposeBlobs();
    _grainImage?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    _ensureBlobs(size.width, size.height, dpr);
    _ensureGrainImage(size.width, size.height, dpr);

    final blobs = _blobs;
    final grain = _grainImage;
    if (blobs == null) {
      return ColoredBox(color: AppColors.meshBase);
    }

    return Stack(
      children: [
        Positioned.fill(
          child: RepaintBoundary(
            child: CustomPaint(
              painter: _BackgroundPainter(
                blobs: blobs,
                phase: _phase,
                dpr: dpr,
                base: AppColors.meshBase,
              ),
              willChange: true,
            ),
          ),
        ),
        if (grain != null)
          Positioned.fill(
            child: IgnorePointer(
              child: RepaintBoundary(
                child: CustomPaint(painter: _GrainImagePainter(grain)),
              ),
            ),
          ),
      ],
    );
  }
}

class _BlobSprite {
  const _BlobSprite({
    required this.image,
    required this.diameter,
    required this.fullSize,
  });

  final ui.Image image;
  final double diameter; // диаметр пятна в device px
  final double fullSize; // размер спрайта в device px (с хвостом профиля)
}

class _BackgroundPainter extends CustomPainter {
  _BackgroundPainter({
    required this.blobs,
    required this.phase,
    required this.dpr,
    required this.base,
  }) : super(repaint: phase);

  final List<_BlobSprite> blobs;
  final ValueNotifier<double> phase;
  final double dpr;
  final Color base;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width * dpr;
    final h = size.height * dpr;
    final t = phase.value * 2 * math.pi;

    final alignments = [
      Offset(-0.7 + math.cos(t) * 0.3, math.sin(t) * 0.9),
      Offset(0.7 + math.sin(t * 2 + 1.5) * 0.3, math.cos(t + 2) * 0.9),
      Offset(math.sin(t + 3.14) * 0.6, 0.7 + math.cos(t * 2 + 1.0) * 0.3),
    ];

    canvas.drawRect(Offset.zero & size, Paint()..color = base);

    for (var i = 0; i < blobs.length && i < alignments.length; i++) {
      final blob = blobs[i];
      final deviceCenter = _blobCenter(
        alignments[i].dx,
        alignments[i].dy,
        blob.diameter,
        w,
        h,
      );
      final center = Offset(deviceCenter.dx / dpr, deviceCenter.dy / dpr);
      final destination = blob.fullSize / dpr;

      canvas.drawImageRect(
        blob.image,
        Rect.fromLTWH(
          0,
          0,
          blob.image.width.toDouble(),
          blob.image.height.toDouble(),
        ),
        Rect.fromCenter(
          center: center,
          width: destination,
          height: destination,
        ),
        Paint()..filterQuality = FilterQuality.low,
      );
    }

    // Прежний mix(col, black, 0.48) поверх всех пятен. Зерно лежит выше,
    // в отдельном слое, и не затемняется.
    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = Colors.black.withValues(alpha: 0.48),
    );
  }

  /// Повторяет getBlobCenterPx из прежнего шейдера, но один раз на кадр.
  Offset _blobCenter(
    double alignX,
    double alignY,
    double diameter,
    double w,
    double h,
  ) {
    return Offset(
      w * 0.5 + alignX * (w - diameter) * 0.5,
      h * 0.5 + alignY * (h - diameter) * 0.5,
    );
  }

  @override
  bool shouldRepaint(covariant _BackgroundPainter oldDelegate) {
    return oldDelegate.blobs != blobs ||
        oldDelegate.dpr != dpr ||
        oldDelegate.base != base;
  }
}

// Рисует пре-рендеренную текстуру зерна (один текстурированный квад).
class _GrainImagePainter extends CustomPainter {
  _GrainImagePainter(this._image);

  final ui.Image _image;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawImageRect(
      _image,
      Rect.fromLTWH(0, 0, _image.width.toDouble(), _image.height.toDouble()),
      Offset.zero & size,
      Paint(),
    );
  }

  @override
  bool shouldRepaint(covariant _GrainImagePainter oldDelegate) =>
      oldDelegate._image != _image;
}

// Точечное зерно — без изменений с версии до шейдера (эталонный вид).
// Рисуется один раз в текстуру (см. _ensureGrainImage).
class _GrainPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final random = math.Random(42);
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: 0.055)
      ..strokeWidth = 1.0
      ..strokeCap = StrokeCap.square;

    List<Offset> points = [];
    for (double x = 0; x < size.width; x += 2) {
      for (double y = 0; y < size.height; y += 2) {
        if (random.nextDouble() > 0.5) {
          final dx = (random.nextDouble() - 0.5) * 2;
          final dy = (random.nextDouble() - 0.5) * 2;
          points.add(Offset(x + dx, y + dy));
        }
      }
    }

    canvas.drawPoints(ui.PointMode.points, points, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
