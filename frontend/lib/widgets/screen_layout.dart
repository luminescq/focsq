// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../theme/app_text_styles.dart';
import 'mac_dots.dart';
import 'panel_container.dart';

class ScreenLayout extends StatelessWidget {
  final String title;

  final bool showDots;

  final List<Widget> actions;

  final Widget child;

  const ScreenLayout({
    super.key,
    required this.title,
    this.showDots = false,
    this.actions = const [],
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 10, top: 8, right: 16, bottom: 16),
      child: Column(
        children: [
          PanelContainer(
            height: 56,
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                if (showDots) ...[
                  const MacDotsRow(),
                  const SizedBox(width: 20),
                ],
                Text(title, style: AppTextStyles.screenTitle),
                if (actions.isNotEmpty) ...[
                  const Spacer(),
                  ...actions,
                ],
              ],
            ),
          ),
          const SizedBox(height: 8),
          Expanded(child: child),
        ],
      ),
    );
  }
}
