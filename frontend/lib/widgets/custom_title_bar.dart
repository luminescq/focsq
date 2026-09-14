// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';
import '../theme/app_colors.dart';

class CustomTitleBar extends StatefulWidget {
  const CustomTitleBar({super.key});

  @override
  State<CustomTitleBar> createState() => _CustomTitleBarState();
}

class _CustomTitleBarState extends State<CustomTitleBar> {
  bool _isHoveringMinimize = false;
  bool _isHoveringClose = false;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 40,
      decoration: BoxDecoration(
        color: AppColors.background.withValues(alpha: 0.8),
        border: Border(
          bottom: BorderSide(
            color: Colors.white.withValues(alpha: 0.05),
            width: 1,
          ),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: DragToMoveArea(
              child: Container(
                padding: const EdgeInsets.only(left: 20),
                alignment: Alignment.centerLeft,
                child: Row(
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _buildDot(),
                        const SizedBox(width: 4),
                        _buildDot(),
                        const SizedBox(width: 4),
                        _buildDot(),
                      ],
                    ),
                    const SizedBox(width: 16),
                    const Text(
                      'FOCSQ',
                      style: TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              MouseRegion(
                onEnter: (_) => setState(() => _isHoveringMinimize = true),
                onExit: (_) => setState(() => _isHoveringMinimize = false),
                child: GestureDetector(
                  onTap: () async {
                    await windowManager.minimize();
                  },
                  child: Container(
                    width: 46,
                    height: 40,
                    color: _isHoveringMinimize
                        ? Colors.white.withValues(alpha: 0.1)
                        : Colors.transparent,
                    child: const Icon(
                      Icons.remove_rounded,
                      color: AppColors.textTertiary,
                      size: 20,
                    ),
                  ),
                ),
              ),

              MouseRegion(
                onEnter: (_) => setState(() => _isHoveringClose = true),
                onExit: (_) => setState(() => _isHoveringClose = false),
                child: GestureDetector(
                  onTap: () async {
                    await windowManager.close();
                  },
                  child: Container(
                    width: 46,
                    height: 40,
                    color: _isHoveringClose
                        ? Colors.red.withValues(alpha: 0.9)
                        : Colors.transparent,
                    child: Icon(
                      Icons.close_rounded,
                      color: _isHoveringClose
                          ? Colors.white
                          : AppColors.textTertiary,
                      size: 18,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildDot() {
    return Container(
      width: 6,
      height: 6,
      decoration: const BoxDecoration(
        color: AppColors.macOsDot,
        shape: BoxShape.circle,
      ),
    );
  }
}
