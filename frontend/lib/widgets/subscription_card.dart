// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import '../models/profile_group.dart';
import '../models/server_profile.dart';
import '../theme/app_colors.dart';

/// Элемент сервера внутри карточки подписки.
class SubscriptionItemData {
  final ServerProfile profile;
  final bool isSelected;
  final VoidCallback onTap;
  final VoidCallback? onEditTap;
  final VoidCallback? onLongPress;

  const SubscriptionItemData({
    required this.profile,
    required this.onTap,
    this.isSelected = false,
    this.onEditTap,
    this.onLongPress,
  });
}

/// Виджет карточки подписки, группирующий профили под единым заголовком.
class SubscriptionCard extends StatefulWidget {
  final String title;
  final List<SubscriptionItemData> items;
  final VoidCallback? onEdit;
  final VoidCallback? onDelete;
  final VoidCallback? onRefresh;
  final bool isRefreshing;

  const SubscriptionCard({
    super.key,
    required this.title,
    required this.items,
    this.onEdit,
    this.onDelete,
    this.onRefresh,
    this.isRefreshing = false,
  });

  @override
  State<SubscriptionCard> createState() => _SubscriptionCardState();
}

class _SubscriptionCardState extends State<SubscriptionCard> {
  bool _expanded = true;

  void _toggleExpanded() {
    if (widget.items.isEmpty) return;
    setState(() => _expanded = !_expanded);
  }

  @override
  Widget build(BuildContext context) {
    final dividerColor = Colors.white.withValues(alpha: 0.05);
    final hasItems = widget.items.isNotEmpty;

    return Container(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: dividerColor),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Заголовок подписки (например, Demo demo)
            _SubscriptionHeader(
              title: widget.title,
              onTap: hasItems ? _toggleExpanded : null,
              onEdit: widget.onEdit,
              onDelete: widget.onDelete,
              onRefresh: widget.onRefresh,
              isRefreshing: widget.isRefreshing,
              isExpanded: _expanded,
              showArrow: hasItems,
            ),

            if (_expanded && hasItems) ...[
              Divider(height: 1, thickness: 1, color: dividerColor),

              // Список серверов в подписке (в стиле стандартных _ProfileRow)
              Column(
                mainAxisSize: MainAxisSize.min,
                children: List.generate(widget.items.length, (index) {
                  final item = widget.items[index];
                  return Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _SubscriptionItemRow(item: item),
                      if (index < widget.items.length - 1)
                        Divider(height: 1, thickness: 1, color: dividerColor),
                    ],
                  );
                }),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SubscriptionHeader extends StatefulWidget {
  final String title;
  final VoidCallback? onTap;
  final VoidCallback? onEdit;
  final VoidCallback? onDelete;
  final VoidCallback? onRefresh;
  final bool isRefreshing;
  final bool isExpanded;
  final bool showArrow;

  const _SubscriptionHeader({
    required this.title,
    this.onTap,
    this.onEdit,
    this.onDelete,
    this.onRefresh,
    this.isRefreshing = false,
    this.isExpanded = true,
    this.showArrow = false,
  });

  @override
  State<_SubscriptionHeader> createState() => _SubscriptionHeaderState();
}

class _SubscriptionHeaderState extends State<_SubscriptionHeader> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: widget.onTap != null
          ? SystemMouseCursors.click
          : SystemMouseCursors.basic,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          color: _hover && widget.onTap != null
              ? Colors.white.withValues(alpha: 0.05)
              : Colors.transparent,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          child: Row(
            children: [
              Icon(
                Icons.cloud_download_rounded,
                size: 16,
                color: Colors.white.withValues(alpha: 0.6),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  widget.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (widget.onEdit != null || widget.onDelete != null)
                IgnorePointer(
                  ignoring: !_hover,
                  child: AnimatedOpacity(
                    opacity: _hover ? 1 : 0,
                    duration: const Duration(milliseconds: 150),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (widget.onEdit != null)
                          _HeaderAction(
                            tooltip: S.editSubscription,
                            icon: Icons.edit_rounded,
                            onTap: widget.onEdit!,
                          ),
                        if (widget.onDelete != null)
                          _HeaderAction(
                            tooltip: S.deleteSubscription,
                            icon: Icons.delete_outline_rounded,
                            onTap: widget.onDelete!,
                          ),
                      ],
                    ),
                  ),
                ),
              if (widget.onRefresh != null) ...[
                const SizedBox(width: 8),
                Tooltip(
                  message: S.refreshSubscription,
                  child: GestureDetector(
                    onTap: widget.isRefreshing ? null : widget.onRefresh,
                    child: widget.isRefreshing
                        ? SizedBox(
                            width: 14,
                            height: 14,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white.withValues(alpha: 0.9),
                            ),
                          )
                        : Icon(
                            Icons.refresh_rounded,
                            color: Colors.white.withValues(alpha: 0.9),
                            size: 16,
                          ),
                  ),
                ),
              ],
              if (widget.showArrow) ...[
                const SizedBox(width: 8),
                AnimatedRotation(
                  turns: widget.isExpanded ? 0.0 : 0.5,
                  duration: const Duration(milliseconds: 200),
                  child: Icon(
                    Icons.keyboard_arrow_up_rounded,
                    color: Colors.white.withValues(alpha: 0.9),
                    size: 18,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _HeaderAction extends StatelessWidget {
  final String tooltip;
  final IconData icon;
  final VoidCallback onTap;

  const _HeaderAction({
    required this.tooltip,
    required this.icon,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.only(left: 10),
          child: Icon(
            icon,
            color: Colors.white.withValues(alpha: 0.75),
            size: 16,
          ),
        ),
      ),
    );
  }
}

/// Строка профиля внутри подписки — идентична стандартной строке _ProfileRow
class _SubscriptionItemRow extends StatefulWidget {
  final SubscriptionItemData item;

  const _SubscriptionItemRow({required this.item});

  @override
  State<_SubscriptionItemRow> createState() => _SubscriptionItemRowState();
}

class _SubscriptionItemRowState extends State<_SubscriptionItemRow> {
  bool _isHovering = false;

  @override
  Widget build(BuildContext context) {
    final item = widget.item;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovering = true),
      onExit: (_) => setState(() => _isHovering = false),
      child: GestureDetector(
        onTap: item.onTap,
        onLongPress: item.onLongPress,
        onSecondaryTap: item.onLongPress,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          height: 52,
          color: _isHovering
              ? Colors.white.withValues(alpha: 0.05)
              : Colors.transparent,
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            children: [
              Icon(
                Icons.speed_rounded,
                color: Colors.white.withValues(alpha: 0.9),
                size: 18,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  profileNodeName(item.profile),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (item.onEditTap != null)
                GestureDetector(
                  onTap: item.onEditTap,
                  child: Icon(
                    Icons.edit_rounded,
                    color: Colors.white.withValues(alpha: 0.9),
                    size: 18,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
