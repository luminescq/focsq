// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import '../models/app_settings.dart';
import '../theme/app_colors.dart';

int resolveMaxPowerThreads(AppSettings settings) {
  var max = settings.extraThreads ? 162 : 90;
  if (settings.hashes == 'Ручной') {
    final hashCount = settings.customHashes
        .split(',')
        .where((value) => value.trim().isNotEmpty)
        .length;
    final cappedByHashes = hashCount * 27;
    if (hashCount > 0 && cappedByHashes < max) {
      max = cappedByHashes;
    }
  }
  return max < 9 ? 9 : max;
}

class CustomTextField extends StatelessWidget {
  final String hint;
  final TextEditingController? controller;
  final String? error;

  const CustomTextField({
    super.key,
    required this.hint,
    this.controller,
    this.error,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          height: 48,
          decoration: BoxDecoration(
            color: AppColors.buttonSecondary,
            borderRadius: BorderRadius.circular(10),
          ),
          child: TextField(
            controller: controller,
            style: const TextStyle(color: AppColors.textPrimary, fontSize: 14),
            decoration: InputDecoration(
              hintText: hint,
              hintStyle: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 14,
              ),
              border: InputBorder.none,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 14,
              ),
            ),
          ),
        ),
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(left: 2, top: 2),
            child: Text(
              error!,
              style: const TextStyle(
                color: AppColors.warning,
                fontSize: 10,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
      ],
    );
  }
}

class SectionDivider extends StatelessWidget {
  final String text;

  const SectionDivider({super.key, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Divider(
            color: Colors.white.withValues(alpha: 0.1),
            thickness: 1,
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text(
            text,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 11,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        Expanded(
          child: Divider(
            color: Colors.white.withValues(alpha: 0.1),
            thickness: 1,
          ),
        ),
      ],
    );
  }
}

class ModalTopBar extends StatelessWidget {
  final String title;

  const ModalTopBar({super.key, required this.title});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      height: 56,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.04)),
      ),
      child: Row(
        children: [
          Row(
            children: [
              _buildDot(AppColors.macOsDot),
              const SizedBox(width: 8),
              _buildDot(AppColors.macOsDot),
              const SizedBox(width: 8),
              _buildDot(AppColors.macOsDot),
            ],
          ),
          const SizedBox(width: 20),
          Text(
            title,
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 15,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDot(Color color) {
    return Container(
      width: 9,
      height: 9,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.7),
        shape: BoxShape.circle,
      ),
    );
  }
}

class CustomDropdown extends StatefulWidget {
  final String value;
  final List<String> items;
  final ValueChanged<String> onChanged;

  const CustomDropdown({
    super.key,
    required this.value,
    required this.items,
    required this.onChanged,
  });

  @override
  State<CustomDropdown> createState() => _CustomDropdownState();
}

class _CustomDropdownState extends State<CustomDropdown>
    with SingleTickerProviderStateMixin {
  final LayerLink _layerLink = LayerLink();
  OverlayEntry? _overlayEntry;
  bool _isOpen = false;
  bool _hovering = false;
  late AnimationController _animationController;

  @override
  void initState() {
    super.initState();
    _animationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
    );
  }

  @override
  void dispose() {
    if (_overlayEntry?.mounted ?? false) _overlayEntry!.remove();
    _overlayEntry = null;
    _animationController.dispose();
    super.dispose();
  }

  void _toggleDropdown() {
    if (_isOpen) {
      _closeDropdown();
    } else {
      _showDropdown();
    }
  }

  Future<void> _closeDropdown() async {
    if (mounted) {
      setState(() => _isOpen = false);
    }
    await _animationController.reverse();
    _overlayEntry?.remove();
    _overlayEntry = null;
  }

  void _showDropdown() {
    if (mounted) {
      setState(() => _isOpen = true);
    }
    _animationController.forward(from: 0.0);
    _overlayEntry = OverlayEntry(
      builder: (context) => Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              onTap: _closeDropdown,
              behavior: HitTestBehavior.opaque,
              child: Container(color: Colors.transparent),
            ),
          ),
          CompositedTransformFollower(
            link: _layerLink,
            offset: const Offset(0, 44),
            showWhenUnlinked: false,
            child: Material(
              color: Colors.transparent,
              child: ScaleTransition(
                alignment: Alignment.topCenter,
                scale: Tween<double>(begin: 0.9, end: 1.0).animate(
                  CurvedAnimation(
                    parent: _animationController,
                    curve: Curves.easeOutQuad,
                  ),
                ),
                child: FadeTransition(
                  opacity: CurvedAnimation(
                    parent: _animationController,
                    curve: Curves.easeOut,
                  ),
          child: Container(
            width: 170,
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: Colors.white.withValues(alpha: 0.1),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.5),
                  blurRadius: 10,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: widget.items.map((item) {
                          return _DropdownItem(
                            text: item,
                            isSelected: item == widget.value,
                            onTap: () {
                              widget.onChanged(item);
                              _closeDropdown();
                            },
                          );
                        }).toList(),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );

    Overlay.of(context).insert(_overlayEntry!);
  }

  @override
  Widget build(BuildContext context) {
    return CompositedTransformTarget(
      link: _layerLink,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hovering = true),
        onExit: (_) => setState(() => _hovering = false),
        child: GestureDetector(
          onTap: _toggleDropdown,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 150),
            padding: const EdgeInsets.only(
              left: 14,
              right: 10,
              top: 9,
              bottom: 9,
            ),
            decoration: BoxDecoration(
              color: _hovering && !_isOpen
                  ? Color.lerp(AppColors.buttonPrimary, Colors.black, 0.08)
                  : AppColors.buttonPrimary,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  widget.value,
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(width: 4),
                AnimatedRotation(
                  turns: _isOpen ? 0.5 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: const Icon(
                    Icons.keyboard_arrow_down_rounded,
                    color: Colors.black,
                    size: 18,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DropdownItem extends StatefulWidget {
  final String text;
  final bool isSelected;
  final VoidCallback onTap;

  const _DropdownItem({
    required this.text,
    required this.isSelected,
    required this.onTap,
  });

  @override
  State<_DropdownItem> createState() => _DropdownItemState();
}

class _DropdownItemState extends State<_DropdownItem> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          color: _hovering
              ? Colors.white.withValues(alpha: 0.05)
              : Colors.transparent,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Text(
                widget.text,
                style: TextStyle(
                  color: widget.isSelected
                      ? Colors.white
                      : AppColors.textSecondary,
                  fontSize: 13,
                  fontWeight: widget.isSelected
                      ? FontWeight.w700
                      : FontWeight.w600,
                ),
              ),
              if (widget.isSelected) ...[
                const Spacer(),
                const Icon(Icons.check, color: AppColors.textPrimary, size: 16),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
