// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:smooth_scroll_multiplatform/smooth_scroll_multiplatform.dart';

class AppScrollArea extends StatelessWidget {
  final Widget child;

  final EdgeInsetsGeometry padding;

  const AppScrollArea({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.symmetric(vertical: 40),
  });

  @override
  Widget build(BuildContext context) {
    return ScrollConfiguration(
      behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
      child: DynMouseScroll(
        builder: (context, controller, physics) => SingleChildScrollView(
          controller: controller,
          physics: physics,
          padding: padding,
          child: child,
        ),
      ),
    );
  }
}
