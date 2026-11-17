// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../l10n/app_locale.dart';
import '../models/app_settings.dart';
import '../models/profile_group.dart';
import '../models/server_profile.dart';
import '../modals/add-profile.dart';
import '../modals/edit-profile.dart';
import '../modals/subscription_delete.dart';
import '../modals/subscription_details_modal.dart';
import '../modals/subscription_import.dart';
import '../service/alert.dart';
import '../service/connect.dart';
import '../service/db.dart';
import '../service/import.dart';
import '../service/subscription_manager.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';
import '../widgets/connect_button.dart';
import '../widgets/custom_alert.dart';
import '../widgets/subscription_card.dart';

class ConnectScreen extends StatefulWidget {
  const ConnectScreen({super.key});

  @override
  State<ConnectScreen> createState() => _ConnectScreenState();
}

class _ConnectScreenState extends State<ConnectScreen> {
  ConnectState _lastState = ConnectState.disconnected;

  @override
  void initState() {
    super.initState();
    _lastState = ConnectService().state;
    ConnectService().addListener(_onConnectStateChanged);
  }

  @override
  void dispose() {
    ConnectService().removeListener(_onConnectStateChanged);
    super.dispose();
  }

  void _onConnectStateChanged() {
    final state = ConnectService().state;
    if (state == _lastState) return;
    setState(() => _lastState = state);
  }

  Future<void> _handleConnectTap() async {
    final error = await ConnectService().toggle();
    if (error == null || !mounted) return;
    CustomAlert.show(
      context,
      title: S.errorTitle,
      message: error,
      type: AlertType.warning,
    );
  }

  String get _statusText {
    switch (ConnectService().state) {
      case ConnectState.disconnected:
        return S.statusConnect;
      case ConnectState.connecting:
        return S.statusConnecting;
      case ConnectState.disconnecting:
        return S.statusDisconnecting;
      case ConnectState.connected:
        return S.statusConnected;
    }
  }

  Color get _statusColor {
    switch (ConnectService().state) {
      case ConnectState.disconnected:
        return AppColors.textSecondary;
      case ConnectState.connecting:
        return Colors.white;
      case ConnectState.disconnecting:
        return Colors.white;
      case ConnectState.connected:
        return AppColors.textSecondary;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ConnectButton(
                state: ConnectService().state,
                onTap: _handleConnectTap,
              ),
              const SizedBox(height: 16),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 300),
                child: Text(
                  _statusText,
                  key: ValueKey(ConnectService().state),
                  style: TextStyle(
                    color: _statusColor,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.5,
                  ),
                ),
              ),
            ],
          ),
        ),

        const Positioned(top: 32, right: 32, child: _AddButton()),

        const Positioned(
          left: 0,
          right: 0,
          bottom: 16,
          child: Align(alignment: Alignment.center, child: _ProfilePicker()),
        ),
      ],
    );
  }
}

class _ProfilePicker extends StatefulWidget {
  const _ProfilePicker();

  @override
  State<_ProfilePicker> createState() => _ProfilePickerState();
}

class _ProfilePickerState extends State<_ProfilePicker> {
  bool _expanded = false;
  int? _selectedProfileId;
  bool _isLoading = true;
  StreamSubscription<AppSettings?>? _settingsSubscription;
  int? _persistedProfileId;
  String? _refreshingUrl;

  void _persistSelection(int? id) {
    if (id == null || id == _persistedProfileId) return;
    _persistedProfileId = id;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) DbService.setActiveProfileId(id);
    });
  }

  Future<void> _refreshSubscription(ProfileGroup group) async {
    final url = group.subscriptionUrl;
    if (url == null) return;
    setState(() => _refreshingUrl = url);
    try {
      final result = await SubscriptionManager.instance.updateSubscription(url);
      if (!mounted) return;
      AlertService.showSubscriptionResult(context, result);
    } catch (_) {
      if (!mounted) return;
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: S.subscriptionFetchFailed,
        type: AlertType.warning,
      );
    } finally {
      if (mounted) setState(() => _refreshingUrl = null);
    }
  }

  Future<void> _deleteSubscription(ProfileGroup group) async {
    final title = group.title;
    final confirmed = await SubscriptionDeleteDialog.show(context, title);
    if (!mounted || !confirmed) return;
    try {
      await SubscriptionManager.instance.deleteSubscription(
        url: group.subscriptionUrl ?? '',
        profiles: group.profiles,
      );
    } catch (_) {
      if (!mounted) return;
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: S.subscriptionDeleteFailed,
        type: AlertType.warning,
      );
      return;
    }
    if (!mounted) return;
    AlertService.showSubscriptionDeleted(context, title);
  }

  @override
  void initState() {
    super.initState();
    _loadActiveProfile();
    _settingsSubscription = DbService.watchSettings().listen((settings) {
      if (!mounted) return;
      final activeId = settings?.activeProfileId;
      if (activeId == _selectedProfileId && !_isLoading) return;
      setState(() {
        _selectedProfileId = activeId;
        _isLoading = false;
      });
    });
  }

  @override
  void dispose() {
    _settingsSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadActiveProfile() async {
    final settings = await DbService.getSettings();
    if (mounted && _isLoading) {
      setState(() {
        _selectedProfileId = settings.activeProfileId;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) return const SizedBox();

    return StreamBuilder<List<ServerProfile>>(
      stream: DbService.watchProfiles(),
      builder: (context, snapshot) {
        final profiles = snapshot.data ?? [];
        ServerProfile? currentProfile;

        if (profiles.isNotEmpty) {
          if (_selectedProfileId != null) {
            currentProfile =
                profiles.where((p) => p.id == _selectedProfileId).firstOrNull ??
                profiles.first;
            if (_selectedProfileId != currentProfile.id) {
              _selectedProfileId = currentProfile.id;
              _persistSelection(currentProfile.id);
            }
          } else {
            currentProfile = profiles.first;
            _selectedProfileId = currentProfile.id;
            _persistSelection(currentProfile.id);
          }
        }

        final otherProfiles = profiles;
        final profileGroups = buildProfileGroups(otherProfiles);

        final subscriptionCards = <Widget>[];
        final standaloneProfiles = <ServerProfile>[];

        for (final group in profileGroups) {
          if (group.isSubscription && group.showHeader) {
            subscriptionCards.add(
              SubscriptionCard(
                title: group.title,
                isRefreshing: _refreshingUrl == group.subscriptionUrl,
                onEdit: () => SubscriptionDetailsModal.show(context, group),
                onDelete: () => _deleteSubscription(group),
                onRefresh: group.subscriptionUrl != null
                    ? () => _refreshSubscription(group)
                    : null,
                items: group.profiles
                    .map(
                      (p) => SubscriptionItemData(
                        profile: p,
                        isSelected: p.id == _selectedProfileId,
                        onTap: () {
                          setState(() {
                            _selectedProfileId = p.id;
                            _expanded = false;
                          });
                          _persistSelection(p.id);
                        },
                        onLongPress: () => EditProfileModal.show(context, p),
                        onEditTap: () => EditProfileModal.show(context, p),
                      ),
                    )
                    .toList(),
              ),
            );
          } else {
            standaloneProfiles.addAll(group.profiles);
          }
        }

        final cards = <Widget>[];
        for (var i = 0; i < subscriptionCards.length; i++) {
          cards.add(subscriptionCards[i]);
          if (i < subscriptionCards.length - 1 ||
              standaloneProfiles.isNotEmpty) {
            cards.add(const SizedBox(height: 8));
          }
        }

        if (standaloneProfiles.isNotEmpty) {
          cards.add(
            Container(
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withValues(alpha: 0.05)),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: List.generate(standaloneProfiles.length, (index) {
                    final p = standaloneProfiles[index];
                    return Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _ProfileRow(
                          title: profileNodeName(p),
                          onTap: () {
                            setState(() {
                              _selectedProfileId = p.id;
                              _expanded = false;
                            });
                            _persistSelection(p.id);
                          },
                          onLongPress: () => EditProfileModal.show(context, p),
                          onEditTap: () => EditProfileModal.show(context, p),
                          showArrow: false,
                        ),
                        if (index < standaloneProfiles.length - 1)
                          Divider(
                            height: 1,
                            thickness: 1,
                            color: Colors.white.withValues(alpha: 0.05),
                          ),
                      ],
                    );
                  }),
                ),
              ),
            ),
          );
        }

        return SizedBox(
          width: 320,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 350),
                reverseDuration: const Duration(milliseconds: 150),
                switchInCurve: Curves.easeOutBack,
                switchOutCurve: Curves.easeIn,
                transitionBuilder: (child, animation) {
                  return ScaleTransition(
                    scale: Tween<double>(
                      begin: 0.8,
                      end: 1.0,
                    ).animate(animation),
                    alignment: Alignment.bottomCenter,
                    child: FadeTransition(opacity: animation, child: child),
                  );
                },
                layoutBuilder: (currentChild, previousChildren) {
                  return Stack(
                    alignment: Alignment.bottomCenter,
                    children: <Widget>[
                      ...previousChildren,
                      // ignore: use_null_aware_elements
                      if (currentChild != null) currentChild,
                    ],
                  );
                },
                child: !_expanded || otherProfiles.isEmpty
                    ? const SizedBox(width: double.infinity, height: 0)
                    : Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: ConstrainedBox(
                            key: const ValueKey('expanded_profiles'),
                            constraints: const BoxConstraints(maxHeight: 340),
                            child: ScrollConfiguration(
                              behavior: ScrollConfiguration.of(
                                context,
                              ).copyWith(scrollbars: false),
                              child: SingleChildScrollView(
                                physics: const ClampingScrollPhysics(),
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: cards,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
              ),

              if (currentProfile != null)
                Container(
                  decoration: BoxDecoration(
                    color: AppColors.card,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.05),
                    ),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: _ProfileRow(
                      title: profileDisplayTitle(currentProfile),
                      onTap: () {
                        if (otherProfiles.isNotEmpty) {
                          setState(() => _expanded = !_expanded);
                        }
                      },
                      onLongPress: () =>
                          EditProfileModal.show(context, currentProfile!),
                      onEditTap: null,
                      showArrow: otherProfiles.isNotEmpty,
                      isExpanded: _expanded,
                    ),
                  ),
                )
              else
                Container(
                  decoration: BoxDecoration(
                    color: AppColors.card,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.05),
                    ),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: _ProfileRow(
                      title: S.noProfiles,
                      onTap: () {},
                      showArrow: false,
                      isExpanded: false,
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _ProfileRow extends StatefulWidget {
  final String title;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final VoidCallback? onEditTap;
  final bool showArrow;
  final bool isExpanded;

  const _ProfileRow({
    required this.title,
    required this.onTap,
    this.onLongPress,
    this.onEditTap,
    this.showArrow = false,
    this.isExpanded = false,
  });

  @override
  State<_ProfileRow> createState() => _ProfileRowState();
}

class _ProfileRowState extends State<_ProfileRow> {
  bool _isHovering = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovering = true),
      onExit: (_) => setState(() => _isHovering = false),
      child: GestureDetector(
        onTap: widget.onTap,
        onLongPress: widget.onLongPress,
        onSecondaryTap: widget.onLongPress,
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
              if (widget.onEditTap != null)
                GestureDetector(
                  onTap: widget.onEditTap,
                  child: Icon(
                    Icons.edit_rounded,
                    color: Colors.white.withValues(alpha: 0.9),
                    size: 18,
                  ),
                )
              else if (widget.showArrow)
                AnimatedRotation(
                  turns: widget.isExpanded ? 0.5 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: Icon(
                    Icons.keyboard_arrow_up_rounded,
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

class _AddButton extends StatefulWidget {
  const _AddButton();

  @override
  State<_AddButton> createState() => _AddButtonState();
}

class _AddButtonState extends State<_AddButton> {
  final LayerLink _layerLink = LayerLink();
  OverlayEntry? _overlayEntry;
  bool _isOpen = false;

  void _toggleMenu() {
    if (_isOpen) {
      _closeMenu();
    } else {
      _showMenu();
    }
  }

  void _closeMenu() {
    _overlayEntry?.remove();
    _overlayEntry = null;
    if (mounted) setState(() => _isOpen = false);
  }

  void _showMenu() {
    _overlayEntry = OverlayEntry(
      builder: (_) => Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              onTap: _closeMenu,
              behavior: HitTestBehavior.opaque,
              child: Container(color: Colors.transparent),
            ),
          ),
          CompositedTransformFollower(
            link: _layerLink,
            offset: const Offset(-200, 40),
            showWhenUnlinked: false,
            child: Material(
              color: Colors.transparent,
              child: Container(
                width: 240,
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
                    children: [
                      _MenuItem(
                        text: S.addManually,
                        icon: Icons.edit_rounded,
                        onTap: () {
                          _closeMenu();
                          AddProfileModal.show(context);
                        },
                      ),
                      _MenuItem(
                        text: S.addFromClipboard,
                        icon: Icons.content_paste_rounded,
                        onTap: () async {
                          _closeMenu();
                          try {
                            final result =
                                await ImportService.importFromClipboard();
                            if (!mounted) return;
                            AlertService.showImportResult(context, result);
                          } catch (_) {
                            if (!mounted) return;
                            CustomAlert.show(
                              context,
                              title: S.errorTitle,
                              message: S.clipboardReadFailed,
                              type: AlertType.warning,
                            );
                          }
                        },
                      ),
                      _MenuItem(
                        text: S.addFromSubscription,
                        icon: Icons.cloud_download_rounded,
                        onTap: () async {
                          _closeMenu();
                          final result = await SubscriptionImportModal.show(
                            context,
                          );
                          if (!mounted || result == null) return;
                          AlertService.showSubscriptionResult(context, result);
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );

    Overlay.of(context).insert(_overlayEntry!);
    setState(() => _isOpen = true);
  }

  @override
  Widget build(BuildContext context) {
    return CompositedTransformTarget(
      link: _layerLink,
      child: IconButton(
        icon: AnimatedRotation(
          turns: _isOpen ? 0.125 : 0.0,
          duration: const Duration(milliseconds: 200),
          child: SvgPicture.asset(
            AppIcons.plus,
            colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
            width: 24,
            height: 24,
          ),
        ),
        onPressed: _toggleMenu,
      ),
    );
  }
}

class _MenuItem extends StatefulWidget {
  final String text;
  final IconData icon;
  final VoidCallback onTap;

  const _MenuItem({
    required this.text,
    required this.icon,
    required this.onTap,
  });

  @override
  State<_MenuItem> createState() => _MenuItemState();
}

class _MenuItemState extends State<_MenuItem> {
  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          color: Colors.transparent,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              Icon(widget.icon, size: 18, color: AppColors.textPrimary),
              const SizedBox(width: 12),
              Text(
                widget.text,
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
