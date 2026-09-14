// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

class MacDot extends StatelessWidget {
  const MacDot({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 9,
      height: 9,
      decoration: BoxDecoration(
        color: AppColors.macOsDot.withValues(alpha: 0.7),
        shape: BoxShape.circle,
      ),
    );
  }
}

class MacDotsRow extends StatelessWidget {
  const MacDotsRow({super.key});

  @override
  Widget build(BuildContext context) {
    return const Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        MacDot(),
        SizedBox(width: 8),
        MacDot(),
        SizedBox(width: 8),
        MacDot(),
      ],
    );
  }
}
