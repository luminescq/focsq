// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:ui';
import 'dart:math' as math;
import '../service/app_visibility.dart';
import '../theme/app_colors.dart';

class MeshBackground extends StatefulWidget {
  const MeshBackground({super.key});

  @override
  State<MeshBackground> createState() => _MeshBackgroundState();
}

class _MeshBackgroundState extends State<MeshBackground> {
  static const int _stepMs = 50;
  static const int _loopMs = 25000;

  Timer? _timer;
  double _phase = 0;

  @override
  void initState() {
    super.initState();
    _startTimer();
    AppVisibility.instance.visible.addListener(_onVisibilityChanged);
  }

  void _onVisibilityChanged() {
    if (!mounted) return;
    if (AppVisibility.instance.visible.value) {
      _startTimer();
    } else {
      _stopTimer();
    }
  }

  void _startTimer() {
    _timer ??= Timer.periodic(const Duration(milliseconds: _stepMs), (_) {
      if (!mounted) return;
      setState(() {
        _phase = (_phase + _stepMs / _loopMs) % 1.0;
      });
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  @override
  void dispose() {
    AppVisibility.instance.visible.removeListener(_onVisibilityChanged);
    _stopTimer();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final time = _phase * 2 * math.pi;

    final size = MediaQuery.of(context).size;
    final maxDimension = math.max(size.width, size.height);

    return Stack(
      children: [
        Container(color: AppColors.meshBase),

        Align(
          alignment: Alignment(
            -0.7 + math.cos(time) * 0.3,
            math.sin(time) * 0.9,
          ),
          child: _buildBlob(
            const Color(0xFFFFF4DF).withValues(alpha: 0.75),
            maxDimension * 0.35,
          ),
        ),

        Align(
          alignment: Alignment(
            0.7 + math.sin(time * 2 + 1.5) * 0.3,
            math.cos(time + 2.0) * 0.9,
          ),
          child: _buildBlob(
            const Color(0xFFFFFFFF).withValues(alpha: 0.65),
            maxDimension * 0.4,
          ),
        ),

        Align(
          alignment: Alignment(
            math.sin(time + 3.14) * 0.6,
            0.7 + math.cos(time * 2 + 1.0) * 0.3,
          ),
          child: _buildBlob(
            const Color(0xFFF7E9D2).withValues(alpha: 0.35),
            maxDimension * 0.3,
          ),
        ),

        Positioned.fill(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 120, sigmaY: 120),
            child: Container(
              color: Colors.black.withValues(alpha: 0.4),
            ),
          ),
        ),

        Positioned.fill(
          child: IgnorePointer(
            child: RepaintBoundary(
              child: CustomPaint(painter: _GrainPainter()),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBlob(Color color, double size) {
    return RepaintBoundary(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
    );
  }
}

class _GrainPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final random = math.Random(42);
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: 0.03)
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.square;

    List<Offset> points = [];
    for (double x = 0; x < size.width; x += 3) {
      for (double y = 0; y < size.height; y += 3) {
        if (random.nextDouble() > 0.6) {
          final dx = (random.nextDouble() - 0.5) * 2;
          final dy = (random.nextDouble() - 0.5) * 2;
          points.add(Offset(x + dx, y + dy));
        }
      }
    }

    canvas.drawPoints(PointMode.points, points, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
