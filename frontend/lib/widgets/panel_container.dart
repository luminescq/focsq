// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

class PanelContainer extends StatelessWidget {
  final Widget? child;

  final double? height;

  final EdgeInsetsGeometry? padding;

  const PanelContainer({super.key, this.child, this.height, this.padding});

  @override
  Widget build(BuildContext context) {
    Widget content = DecoratedBox(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.04)),
      ),
      child: padding != null ? Padding(padding: padding!, child: child) : child,
    );

    if (height != null) {
      content = SizedBox(height: height, child: content);
    }
    return content;
  }
}
