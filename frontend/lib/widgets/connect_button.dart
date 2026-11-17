// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math' as math;
import 'dart:ui' as ui show Canvas, Image, PictureRecorder;

import 'package:flutter/material.dart';
import '../service/connect.dart';
import '../theme/app_colors.dart';

class ConnectButton extends StatefulWidget {
  final ConnectState state;
  final VoidCallback onTap;

  const ConnectButton({super.key, required this.state, required this.onTap});

  @override
  State<ConnectButton> createState() => _ConnectButtonState();
}

class _ConnectButtonState extends State<ConnectButton>
    with TickerProviderStateMixin {
  late AnimationController _rotationController;
  late AnimationController _pulseController;
  late Animation<double> _scaleAnimation;
  late final Listenable _animations;

  // [FOCSQ] Путь бейджа запекается в текстуру один раз (как пятна фона):
  // пульс масштабирует Image-слой с билинейной выборкой. Векторный путь на
  // дробных масштабах каждый кадр проходил пере-rasterization с пересчётом
  // антиалиасинговых краёв — они «кипели», шейп дрожал при подключении.
  ui.Image? _badgeImage;
  int _badgePx = 0;

  @override
  void initState() {
    super.initState();
    _rotationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2500),
    );

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );

    _animations = Listenable.merge([_rotationController, _pulseController]);

    _scaleAnimation = TweenSequence<double>([
      TweenSequenceItem(
        tween: Tween(
          begin: 1.0,
          end: 1.05,
        ).chain(CurveTween(curve: Curves.easeInOut)),
        weight: 50,
      ),
      TweenSequenceItem(
        tween: Tween(
          begin: 1.05,
          end: 1.0,
        ).chain(CurveTween(curve: Curves.easeInOut)),
        weight: 50,
      ),
    ]).animate(_pulseController);

    _updateAnimations();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _ensureBadgeTexture(MediaQuery.devicePixelRatioOf(context));
  }

  void _ensureBadgeTexture(double dpr) {
    final px = (140 * dpr).ceil();
    if (_badgeImage != null && _badgePx == px) return;
    _badgeImage?.dispose();
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder, Offset.zero & const Size(140, 140));
    canvas.scale(dpr);
    BadgePainter(color: AppColors.card).paint(canvas, const Size(140, 140));
    final picture = recorder.endRecording();
    _badgeImage = picture.toImageSync(px, px);
    picture.dispose();
    _badgePx = px;
  }

  @override
  void didUpdateWidget(ConnectButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state != widget.state) {
      _updateAnimations();
    }
  }

  void _updateAnimations() {
    if (widget.state == ConnectState.connecting ||
        widget.state == ConnectState.disconnecting) {
      _rotationController.repeat();
      _pulseController.reset();
    } else if (widget.state == ConnectState.connected) {
      _rotationController.stop();
      _pulseController.repeat();
    } else {
      _rotationController.stop();
      _pulseController.stop();
      _pulseController.animateTo(
        0,
        duration: const Duration(milliseconds: 300),
      );
    }
  }

  @override
  void dispose() {
    _rotationController.dispose();
    _pulseController.dispose();
    _badgeImage?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final image = _badgeImage;
    return GestureDetector(
      onTap: widget.onTap,
      child: Stack(
        alignment: Alignment.center,
        children: [
          AnimatedBuilder(
            animation: _animations,
            child: RepaintBoundary(
              child: image != null
                  ? CustomPaint(
                      painter: _BadgeTexturePainter(image),
                      child: const SizedBox(width: 140, height: 140),
                    )
                  : CustomPaint(
                      painter: BadgePainter(color: AppColors.card),
                      child: const SizedBox(width: 140, height: 140),
                    ),
            ),
            builder: (context, child) {
              final scale = widget.state == ConnectState.connected
                  ? _scaleAnimation.value
                  : 1.0;
              final rotation = _rotationController.value * 2 * math.pi;

              return Transform.scale(
                scale: scale,
                child: Transform.rotate(angle: rotation, child: child),
              );
            },
          ),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 300),
            child: widget.state == ConnectState.connected
                ? const Icon(
                    Icons.stop_rounded,
                    key: ValueKey('stop'),
                    color: AppColors.textPrimary,
                    size: 56,
                  )
                : const Icon(
                    Icons.play_arrow_rounded,
                    key: ValueKey('play'),
                    color: AppColors.textPrimary,
                    size: 64,
                  ),
          ),
        ],
      ),
    );
  }
}

/// Бейдж, запечённый в текстуру: пульс масштабирует стабильный Image
/// (билинейная выборка), векторный путь не перерasterизуется на кадр.
class _BadgeTexturePainter extends CustomPainter {
  _BadgeTexturePainter(this._image);

  final ui.Image _image;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawImageRect(
      _image,
      Rect.fromLTWH(0, 0, _image.width.toDouble(), _image.height.toDouble()),
      Offset.zero & size,
      Paint()..filterQuality = FilterQuality.medium,
    );
  }

  @override
  bool shouldRepaint(covariant _BadgeTexturePainter oldDelegate) =>
      oldDelegate._image != _image;
}

class BadgePainter extends CustomPainter {
  final Color color;
  BadgePainter({required this.color});

  // Геометрия значка постоянна — строим Path один раз на процесс.
  static final Path _path = _buildPath();

  static Path _buildPath() {
    final path = Path();
    path.moveTo(83.68, 4.56);
    path.cubicTo(87.62, -1.52, 96.86, -1.52, 100.80, 4.56);
    path.lineTo(110.25, 19.12);
    path.cubicTo(112.84, 23.11, 118.07, 24.74, 122.62, 22.96);
    path.lineTo(139.21, 16.49);
    path.cubicTo(146.14, 13.79, 153.61, 18.99, 153.07, 26.12);
    path.lineTo(151.76, 43.21);
    path.cubicTo(151.40, 47.89, 154.63, 52.15, 159.40, 53.27);
    path.lineTo(176.81, 57.36);
    path.cubicTo(184.07, 59.06, 186.92, 67.47, 182.10, 72.93);
    path.lineTo(170.53, 86.02);
    path.cubicTo(167.36, 89.61, 167.36, 94.87, 170.53, 98.46);
    path.lineTo(182.10, 111.55);
    path.cubicTo(186.92, 117.01, 184.07, 125.42, 176.81, 127.13);
    path.lineTo(159.40, 131.22);
    path.cubicTo(154.63, 132.34, 151.40, 136.59, 151.76, 141.28);
    path.lineTo(153.07, 158.37);
    path.cubicTo(153.61, 165.50, 146.14, 170.69, 139.21, 167.99);
    path.lineTo(122.62, 161.52);
    path.cubicTo(118.07, 159.75, 112.84, 161.37, 110.25, 165.36);
    path.lineTo(100.81, 179.93);
    path.cubicTo(96.86, 186.00, 87.62, 186.00, 83.68, 179.93);
    path.lineTo(74.23, 165.36);
    path.cubicTo(71.64, 161.37, 66.42, 159.75, 61.86, 161.52);
    path.lineTo(45.27, 167.99);
    path.cubicTo(38.35, 170.69, 30.87, 165.50, 31.42, 158.37);
    path.lineTo(32.73, 141.28);
    path.cubicTo(33.08, 136.59, 29.85, 132.34, 25.08, 131.22);
    path.lineTo(7.68, 127.13);
    path.cubicTo(0.42, 125.42, -2.44, 117.01, 2.39, 111.55);
    path.lineTo(13.95, 98.46);
    path.cubicTo(17.12, 94.87, 17.12, 89.61, 13.95, 86.02);
    path.lineTo(2.39, 72.93);
    path.cubicTo(-2.44, 67.47, 0.42, 59.06, 7.68, 57.36);
    path.lineTo(25.08, 53.27);
    path.cubicTo(29.85, 52.15, 33.08, 47.89, 32.73, 43.21);
    path.lineTo(31.42, 26.12);
    path.cubicTo(30.87, 18.99, 38.35, 13.79, 45.27, 16.49);
    path.lineTo(61.86, 22.96);
    path.cubicTo(66.42, 24.74, 71.64, 23.11, 74.23, 19.12);
    path.close();
    return path;
  }

  @override
  void paint(Canvas canvas, Size size) {
    const double designWidth = 189.36;
    const double designHeight = 187.52;

    final double scaleX = size.width / designWidth;
    final double scaleY = size.height / designHeight;
    final double scale = scaleX < scaleY ? scaleX : scaleY;

    final double dx = (size.width - designWidth * scale) / 2;
    final double dy = (size.height - designHeight * scale) / 2;

    canvas.translate(dx, dy);
    canvas.scale(scale, scale);

    canvas.save();
    canvas.translate(2.44, 1.52);

    final paint = Paint()
      ..style = PaintingStyle.fill
      ..color = color
      ..isAntiAlias = true;
    canvas.drawPath(_path, paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant BadgePainter oldDelegate) {
    return oldDelegate.color != color;
  }
}
