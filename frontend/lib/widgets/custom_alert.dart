// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

enum AlertType { success, warning, info }

class CustomAlert {
  static OverlayEntry? _active;

  static void show(
    BuildContext context, {
    required String title,
    required String message,
    AlertType type = AlertType.info,
  }) {
    final overlay = Overlay.of(context);
    _hideActive();

    late final OverlayEntry overlayEntry;
    overlayEntry = OverlayEntry(
      builder: (context) => _AlertWidget(
        title: title,
        message: message,
        type: type,
        onDismiss: () {
          if (identical(_active, overlayEntry)) _active = null;
          try {
            overlayEntry.remove();
          } catch (_) {}
        },
      ),
    );

    _active = overlayEntry;
    overlay.insert(overlayEntry);
  }

  static void _hideActive() {
    final entry = _active;
    _active = null;
    if (entry == null) return;
    try {
      entry.remove();
    } catch (_) {}
  }
}

class _AlertWidget extends StatefulWidget {
  final String title;
  final String message;
  final AlertType type;
  final VoidCallback onDismiss;

  const _AlertWidget({
    required this.title,
    required this.message,
    required this.type,
    required this.onDismiss,
  });

  @override
  State<_AlertWidget> createState() => _AlertWidgetState();
}

class _AlertWidgetState extends State<_AlertWidget>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<Offset> _slide;
  late final Animation<double> _fade;
  Timer? _hideTimer;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 350),
    );
    final curve =
        CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic);
    _slide = Tween<Offset>(
      begin: const Offset(1.1, 0),
      end: Offset.zero,
    ).animate(curve);
    _fade = Tween<double>(begin: 0, end: 1).animate(curve);

    _controller.forward();
    _scheduleHide();
  }

  void _scheduleHide() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 4), () async {
      if (!mounted) return;
      await _controller.reverse();
      widget.onDismiss();
    });
  }

  Future<void> _dismiss() async {
    _hideTimer?.cancel();
    await _controller.reverse();
    widget.onDismiss();
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  IconData get _icon => switch (widget.type) {
        AlertType.success => Icons.check_circle_rounded,
        AlertType.warning => Icons.warning_amber_rounded,
        AlertType.info => Icons.info_rounded,
      };

  Color get _accentColor => switch (widget.type) {
        AlertType.success => AppColors.success,
        AlertType.warning => AppColors.warning,
        AlertType.info => AppColors.info,
      };

  @override
  Widget build(BuildContext context) {
    return Positioned(
      top: 48,
      right: 24,
      child: SlideTransition(
        position: _slide,
        child: FadeTransition(
          opacity: _fade,
          child: Material(
            color: Colors.transparent,
            child: Container(
              width: 320,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border:
                    Border.all(color: Colors.white.withValues(alpha: 0.1)),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.5),
                    blurRadius: 16,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 32,
                    height: 32,
                    decoration: BoxDecoration(
                      color: _accentColor.withValues(alpha: 0.15),
                      shape: BoxShape.circle,
                    ),
                    child:
                        Center(child: Icon(_icon, size: 18, color: _accentColor)),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          widget.title,
                          style: const TextStyle(
                            color: AppColors.textPrimary,
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          widget.message,
                          style: const TextStyle(
                            color: AppColors.textSecondary,
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                  GestureDetector(
                    onTap: _dismiss,
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: Icon(
                        Icons.close_rounded,
                        color: Colors.white.withValues(alpha: 0.4),
                        size: 16,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
