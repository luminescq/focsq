// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../modals/update_modal.dart';
import '../service/browser.dart';
import '../service/db.dart';
import '../service/report.dart';
import '../service/update.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';
import '../widgets/custom_alert.dart';
import '../widgets/app_scrollbar.dart';
import '../widgets/panel_container.dart';
import '../widgets/screen_layout.dart';

class AppLinks {
  AppLinks._();

  static const String repo = 'https://github.com/luminescq/focsq';
  static const String issues = 'https://github.com/luminescq/focsq/issues/new';
}

class InfoScreen extends StatelessWidget {
  const InfoScreen({super.key});

  static Future<void> _open(BuildContext context, String url) async {
    final opened = await UrlService.openInBrowser(url);
    if (!opened && context.mounted) {
      CustomAlert.show(
        context,
        title: 'Не удалось открыть браузер',
        message: url,
        type: AlertType.warning,
      );
    }
  }

  static Future<void> _checkForUpdates(BuildContext context) async {
    final result = await UpdateService.manualCheck();
    if (!context.mounted) return;

    switch (result) {
      case UpdateAvailable():
        final openReleases = await UpdateModal.show(context, result);
        if (!openReleases) {
          await DbService.updateSettings(
            (s) => s..skipUpdateVersion = result.version,
          );
          if (context.mounted) {
            CustomAlert.show(
              context,
              title: 'Хорошо',
              message: 'Напоминание про ${result.version} скрыто',
              type: AlertType.info,
            );
          }
        } else if (context.mounted) {
          await _open(context, UpdateService.releasesPage);
        }
      case UpToDate():
        CustomAlert.show(
          context,
          title: 'Обновлений нет',
          message: 'Установлена актуальная версия (${result.currentVersion})',
          type: AlertType.success,
        );
      case NoReleases():
        CustomAlert.show(
          context,
          title: 'Обновлений нет',
          message:
              'Установлена ${result.currentVersion}. Публикованных релизов '
              'на GitHub ещё нет — загляни позже.',
          type: AlertType.info,
        );
      case UpdateCheckError():
        CustomAlert.show(
          context,
          title: 'Проверка не удалась',
          message: result.message,
          type: AlertType.warning,
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScreenLayout(
      title: 'Информация',
      showDots: true,
      child: PanelContainer(
        child: Center(
          child: AppScrollArea(
            padding: const EdgeInsets.only(top: 40, bottom: 40, right: 14),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    height: 120,
                    width: double.infinity,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(12),
                      image: const DecorationImage(
                        image: AssetImage(AppImages.banner),
                        fit: BoxFit.cover,
                      ),
                    ),
                    child: Container(
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(12),
                        gradient: LinearGradient(
                          begin: Alignment.bottomCenter,
                          end: Alignment.topCenter,
                          colors: [
                            Colors.black.withValues(alpha: 0.8),
                            Colors.transparent,
                          ],
                        ),
                      ),
                      padding: const EdgeInsets.all(20),
                      alignment: Alignment.bottomLeft,
                      child: const Text(
                        'Информация',
                        style: TextStyle(
                          color: AppColors.textPrimary,
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),

                  ...[
                    _WideActionCard(
                      iconAsset: AppIcons.github,
                      title: 'GITHUB',
                      subtitle: 'Исходный код приложения',
                      backgroundColor: AppColors.background,
                      onTap: () => _open(context, AppLinks.repo),
                    ),
                    IntrinsicHeight(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(
                            child: _SquareActionCard(
                              iconAsset: AppIcons.report,
                              title: 'Отчет',
                              subtitle: 'Отчет о багах',
                              backgroundColor: AppColors.cardHover,
                              onTap: () async {
                                await ReportService.copyToClipboard();
                                if (context.mounted) {
                                  CustomAlert.show(
                                    context,
                                    title: 'Успех',
                                    message: 'Отчет скопирован в буфер обмена!',
                                    type: AlertType.success,
                                  );
                                }
                              },
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _SquareActionCard(
                              iconAsset: AppIcons.bug,
                              title: 'Issue',
                              subtitle: 'Сообщить о проблеме',
                              backgroundColor: AppColors.cardHover,
                              onTap: () => _open(context, AppLinks.issues),
                            ),
                          ),
                        ],
                      ),
                    ),
                    _UpdateCard(
                      backgroundColor: AppColors.cardHover,
                      onTap: () => _checkForUpdates(context),
                    ),
                    const _DeveloperCard(
                      name: 'Luminescq',
                      role: 'UX × UI',
                      avatarAsset: AppImages.luminescq,
                      backgroundColor: AppColors.cardHover,
                    ),
                  ].separatedBy(const SizedBox(height: 12)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

extension SeparatedWidgets on List<Widget> {
  List<Widget> separatedBy(Widget separator) => [
    for (var i = 0; i < length; i++) ...[if (i > 0) separator, this[i]],
  ];
}

class _PressableCard extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsetsGeometry padding;
  final Color backgroundColor;

  const _PressableCard({
    required this.child,
    required this.onTap,
    required this.padding,
    required this.backgroundColor,
  });

  @override
  State<_PressableCard> createState() => _PressableCardState();
}

class _PressableCardState extends State<_PressableCard> {
  bool _hover = false;
  bool _pressed = false;
  late final Color _hoverColor = Color.lerp(
    widget.backgroundColor,
    Colors.white,
    0.06,
  )!;

  void _setPressed(bool value) {
    if (widget.onTap == null || _pressed == value) return;
    setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: widget.onTap != null
          ? SystemMouseCursors.click
          : MouseCursor.defer,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() {
        _hover = false;
        _pressed = false;
      }),
      child: GestureDetector(
        onTap: widget.onTap,
        onTapDown: (_) => _setPressed(true),
        onTapUp: (_) => _setPressed(false),
        onTapCancel: () => _setPressed(false),
        child: AnimatedScale(
          scale: _pressed ? 0.98 : 1.0,
          duration: const Duration(milliseconds: 100),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 150),
            padding: widget.padding,
            decoration: BoxDecoration(
              color: _hover ? _hoverColor : widget.backgroundColor,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: _hover
                    ? Colors.white.withValues(alpha: 0.1)
                    : Colors.white.withValues(alpha: 0.02),
              ),
            ),
            child: widget.child,
          ),
        ),
      ),
    );
  }
}

class _IconBadge extends StatelessWidget {
  final String? asset;
  final IconData? icon;
  final Color? iconColor;

  const _IconBadge({this.asset, this.icon, this.iconColor});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 44,
      height: 44,
      decoration: const BoxDecoration(
        color: AppColors.sidebar,
        shape: BoxShape.circle,
      ),
      child: Center(
        child: asset != null
            ? SvgPicture.asset(
                asset!,
                width: 22,
                height: 22,
                colorFilter: const ColorFilter.mode(
                  Colors.white,
                  BlendMode.srcIn,
                ),
              )
            : Icon(icon, size: 20, color: iconColor ?? AppColors.textPrimary),
      ),
    );
  }
}

class _WideActionCard extends StatelessWidget {
  final String iconAsset;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;
  final Color backgroundColor;

  const _WideActionCard({
    required this.iconAsset,
    required this.title,
    required this.subtitle,
    required this.backgroundColor,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return _PressableCard(
      onTap: onTap,
      backgroundColor: backgroundColor,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
      child: Row(
        children: [
          _IconBadge(asset: iconAsset),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
          const Icon(
            Icons.chevron_right,
            color: AppColors.textTertiary,
            size: 20,
          ),
        ],
      ),
    );
  }
}

class _SquareActionCard extends StatelessWidget {
  final String iconAsset;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;
  final Color backgroundColor;

  const _SquareActionCard({
    required this.iconAsset,
    required this.title,
    required this.subtitle,
    required this.backgroundColor,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return _PressableCard(
      onTap: onTap,
      backgroundColor: backgroundColor,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _IconBadge(asset: iconAsset),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            subtitle,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

class _UpdateCard extends StatefulWidget {
  final Color backgroundColor;
  final VoidCallback? onTap;

  const _UpdateCard({required this.backgroundColor, this.onTap});

  @override
  State<_UpdateCard> createState() => _UpdateCardState();
}

class _UpdateCardState extends State<_UpdateCard> {
  void Function()? _unsubscribe;

  @override
  void initState() {
    super.initState();

    _unsubscribe = UpdateService.addListener(() {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _unsubscribe?.call();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final hasUpdate = UpdateService.hasAvailableUpdate;
    return _PressableCard(
      onTap: widget.onTap,
      backgroundColor: widget.backgroundColor,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      child: Row(
        children: [
          hasUpdate
              ? Container(
                  width: 44,
                  height: 44,
                  decoration: const BoxDecoration(
                    color: AppColors.info,
                    shape: BoxShape.circle,
                  ),
                  child: Center(
                    child: SvgPicture.asset(
                      AppIcons.update,
                      width: 20,
                      height: 20,
                      colorFilter: const ColorFilter.mode(
                        Colors.white,
                        BlendMode.srcIn,
                      ),
                    ),
                  ),
                )
              : const _IconBadge(
                  icon: Icons.check_rounded,
                  iconColor: AppColors.success,
                ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Обновление',
                  style: TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                FutureBuilder<PackageInfo>(
                  future: PackageInfo.fromPlatform(),
                  builder: (context, snapshot) {
                    final installed = snapshot.hasData
                        ? snapshot.data!.version
                        : '—';
                    final available = UpdateService.cachedVersion;
                    final text = hasUpdate
                        ? (UpdateService.isUpdateDismissed
                              ? 'Установлена $installed · доступна $available '
                                    '(напоминание скрыто)'
                              : 'Установлена $installed · доступна $available')
                        : 'Установлена $installed';
                    return Text(
                      text,
                      style: TextStyle(
                        color: hasUpdate
                            ? AppColors.info
                            : AppColors.textSecondary,
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                      ),
                    );
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DeveloperCard extends StatelessWidget {
  final String name;
  final String role;
  final String avatarAsset;
  final Color backgroundColor;

  const _DeveloperCard({
    required this.name,
    required this.role,
    required this.avatarAsset,
    required this.backgroundColor,
  });

  @override
  Widget build(BuildContext context) {
    return _PressableCard(
      onTap: null,
      backgroundColor: backgroundColor,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white.withValues(alpha: 0.1)),
              image: DecorationImage(
                image: AssetImage(avatarAsset),
                fit: BoxFit.cover,
              ),
            ),
          ),
          const SizedBox(width: 16),
          Text(
            name,
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 15,
              fontWeight: FontWeight.w700,
            ),
          ),
          const Spacer(),
          Text(
            role,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
            ),
          ),
        ],
      ),
    );
  }
}