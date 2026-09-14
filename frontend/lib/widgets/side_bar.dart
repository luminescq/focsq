// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../service/update.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';

class SideBar extends StatefulWidget {
  final int selectedIndex;
  final ValueChanged<int> onIndexChanged;

  const SideBar({
    super.key,
    required this.selectedIndex,
    required this.onIndexChanged,
  });

  static const double _width = 72;
  static const double _buttonSize = 55;
  static const double _buttonRadius = 16;

  @override
  State<SideBar> createState() => _SideBarState();
}

class _SideBarState extends State<SideBar> {
  void Function()? _unsubscribeUpdate;

  @override
  void initState() {
    super.initState();
    _unsubscribeUpdate = UpdateService.addListener(() {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _unsubscribeUpdate?.call();
    super.dispose();
  }

  static const List<({int index, String asset, String label})> _navItems = [
    (index: 0, asset: AppIcons.connect, label: 'Подключение'),
    (index: 1, asset: AppIcons.logs, label: 'Логи'),
    (index: 2, asset: AppIcons.information, label: 'Информация'),
    (index: 3, asset: AppIcons.settings, label: 'Настройки'),
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      width: SideBar._width,
      margin: const EdgeInsets.only(left: 16, top: 8, bottom: 16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.35),
            blurRadius: 16,

            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        children: [
          const SizedBox(height: 24),
          ..._navItems.take(3).map(_buildNavIcon),
          const Spacer(),
          _buildNavIcon(_navItems.last),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _buildNavIcon(({int index, String asset, String label}) item) {
    return Padding(
      padding: EdgeInsets.only(bottom: item == _navItems.last ? 0 : 16),
      child: _NavIcon(
        asset: item.asset,
        label: item.label,
        selected: widget.selectedIndex == item.index,
        onTap: () => widget.onIndexChanged(item.index),
        showIndicator: item.index == 2 &&
            UpdateService.hasAvailableUpdate &&
            !UpdateService.isUpdateDismissed,
      ),
    );
  }
}

class _NavIcon extends StatefulWidget {
  final String asset;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  final bool showIndicator;

  const _NavIcon({
    required this.asset,
    required this.label,
    required this.selected,
    required this.onTap,
    this.showIndicator = false,
  });

  @override
  State<_NavIcon> createState() => _NavIconState();
}class _NavIconState extends State<_NavIcon> {
  bool _hovering = false;

  static final ColorFilter _activeFilter = const ColorFilter.mode(
    Colors.black,
    BlendMode.srcIn,
  );
  static final ColorFilter _hoverFilter = const ColorFilter.mode(
    Colors.white,
    BlendMode.srcIn,
  );
  static final ColorFilter _idleFilter = ColorFilter.mode(
    Colors.white.withValues(alpha: 0.7),
    BlendMode.srcIn,
  );

  @override
  Widget build(BuildContext context) {
    final active = widget.selected;
    final background = active ? AppColors.buttonPrimary : Colors.transparent;
    final filter = active
        ? _activeFilter
        : _hovering
        ? _hoverFilter
        : _idleFilter;

    return Semantics(
      button: true,
      label: widget.label,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hovering = true),
        onExit: (_) => setState(() => _hovering = false),
        child: GestureDetector(
          onTap: widget.onTap,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                curve: Curves.easeOut,
                width: SideBar._buttonSize,
                height: SideBar._buttonSize,
                decoration: BoxDecoration(
                  color: background,
                  borderRadius: BorderRadius.circular(SideBar._buttonRadius),
                ),
                child: Center(
                  child: AnimatedScale(
                    scale: active ? 1.0 : 0.94,
                    duration: const Duration(milliseconds: 150),
                    child: SvgPicture.asset(
                      widget.asset,
                      width: 24,
                      height: 24,
                      colorFilter: filter,
                    ),
                  ),
                ),
              ),
              if (widget.showIndicator)
                Positioned(
                  top: -2,
                  right: -2,
                  child: Container(
                    width: 12,
                    height: 12,
                    decoration: BoxDecoration(
                      color: AppColors.info,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: AppColors.card,
                        width: 2.5,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
