// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import '../l10n/app_locale.dart';
import '../modals/profile_form_components.dart';
import '../modals/routing_domains_modal.dart';
import '../modals/routing_ips_modal.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';
import '../models/app_settings.dart';
import '../service/alert.dart';
import '../service/autostart.dart';
import '../service/db.dart';
import '../service/logs.dart';
import '../service/tray_service.dart';
import '../service/update.dart';
import '../service/vk/token.dart';
import '../widgets/custom_alert.dart';
import '../widgets/app_scrollbar.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _startAtLaunch = false;
  bool _autostartBusy = false;
  bool _insecureHttpPromptOpen = false;
  bool _hasToken = false;
  bool _routingExpanded = false;
  AppSettings? _settings;
  final TextEditingController _manualHashesCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadData();
    _manualHashesCtrl.addListener(() {
      if (_settings == null) return;
      _updateSettings((s) => s.customHashes = _manualHashesCtrl.text.trim());
    });
    unawaited(UpdateService.autoCheck());
  }

  @override
  void dispose() {
    _manualHashesCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final isEnabled = await AutostartService.isEnabled();
    final settings = await DbService.getSettings();
    final hasToken = await VkToken.has();
    if (mounted) {
      setState(() {
        _startAtLaunch = isEnabled;
        _settings = settings;
        _hasToken = hasToken;
        _manualHashesCtrl.text = settings.customHashes;
      });
    }
  }

  Future<void> _updateSettings(void Function(AppSettings s) updater) async {
    if (_settings == null) return;
    setState(() {
      updater(_settings!);
    });
    await DbService.updateSettings((s) {
      updater(s);
      return s;
    });
  }

  // Отображаемые подписи режимов; ключи в БД остаются русскими.
  static String _workModeLabel(String v) => switch (v) {
    'Капча' => S.optCaptcha,
    'Звонки' => S.optCalls,
    'Авто ВК' => S.optAutoVk,
    _ => v,
  };
  static String _workModeKey(String l) {
    if (l == S.optCaptcha) return 'Капча';
    if (l == S.optCalls) return 'Звонки';
    if (l == S.optAutoVk) return 'Авто ВК';
    return l;
  }

  static String _hashesLabel(String v) => switch (v) {
    'Ручной' => S.optManual,
    'Авто API' => S.optAutoApi,
    'Авто ВК' => S.optAutoVk,
    _ => v,
  };
  static String _hashesKey(String l) {
    if (l == S.optManual) return 'Ручной';
    if (l == S.optAutoApi) return 'Авто API';
    if (l == S.optAutoVk) return 'Авто ВК';
    return l;
  }

  static String _maskingLabel(String v) => switch (v) {
    'Простая' => S.optMaskSimple,
    'Средняя' => S.optMaskMedium,
    _ => v,
  };
  static String _maskingKey(String l) {
    if (l == S.optMaskSimple) return 'Простая';
    if (l == S.optMaskMedium) return 'Средняя';
    return l;
  }

  Future<void> _onInsecureHttpChanged(bool val) async {
    if (_insecureHttpPromptOpen) return;
    if (val) {
      _insecureHttpPromptOpen = true;
      try {
        final confirmed = await _showInsecureHttpWarning();
        if (confirmed != true || !mounted) return;
      } finally {
        _insecureHttpPromptOpen = false;
      }
    }
    if (!mounted) return;
    _updateSettings((s) => s.zAllowInsecureHttp = val);
  }

  Future<bool?> _showInsecureHttpWarning() {
    return showGeneralDialog<bool>(
      context: context,
      barrierDismissible: true,
      barrierLabel: S.dismiss,
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 300),
      pageBuilder: (context, anim, secAnim) {
        final screenWidth = MediaQuery.sizeOf(context).width;
        final leftPadding = screenWidth > 600 ? 88.0 : 0.0;
        return Padding(
          padding: EdgeInsets.only(left: leftPadding),
          child: Center(
            child: SingleChildScrollView(
              child: Material(
                color: Colors.transparent,
                child: Container(
                  width: 400,
                  margin: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ModalTopBar(title: S.allowInsecureHttpConfirmTitle),
                      const SizedBox(height: 8),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          color: AppColors.card,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: Colors.white.withValues(alpha: 0.04),
                          ),
                        ),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Icon(
                                  Icons.warning_amber_rounded,
                                  color: AppColors.warning,
                                  size: 24,
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Text(
                                    S.allowInsecureHttpWarn,
                                    style: const TextStyle(
                                      color: AppColors.textSecondary,
                                      fontSize: 13,
                                      height: 1.4,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 20),
                            Row(
                              children: [
                                Expanded(
                                  child: SizedBox(
                                    height: 48,
                                    child: ElevatedButton(
                                      onPressed: () =>
                                          Navigator.of(context).pop(false),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor:
                                            AppColors.buttonSecondary,
                                        foregroundColor: Colors.white,
                                        elevation: 0,
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(
                                            10,
                                          ),
                                        ),
                                      ),
                                      child: Text(
                                        S.cancelButton,
                                        style: const TextStyle(
                                          fontSize: 14,
                                          fontWeight: FontWeight.w700,
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  flex: 2,
                                  child: SizedBox(
                                    height: 48,
                                    child: ElevatedButton(
                                      onPressed: () =>
                                          Navigator.of(context).pop(true),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor:
                                            AppColors.buttonPrimary,
                                        foregroundColor: Colors.black,
                                        elevation: 0,
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(
                                            10,
                                          ),
                                        ),
                                      ),
                                      child: Text(
                                        S.allowInsecureHttpConfirmButton,
                                        maxLines: 1,
                                        softWrap: false,
                                        style: const TextStyle(
                                          fontSize: 14,
                                          fontWeight: FontWeight.w800,
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
      transitionBuilder: (context, anim, secAnim, child) {
        final curve = CurvedAnimation(parent: anim, curve: Curves.easeOutQuart);
        return SlideTransition(
          position: Tween<Offset>(
            begin: const Offset(0, 0.08),
            end: Offset.zero,
          ).animate(curve),
          child: FadeTransition(opacity: curve, child: child),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_settings == null) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.info),
      );
    }

    return Padding(
      padding: const EdgeInsets.only(
        left: 10.0,
        top: 8.0,
        right: 16.0,
        bottom: 16.0,
      ),
      child: Column(
        children: [
          Container(
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
                  S.settingsTitle,
                  style: TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),

          Expanded(
            child: Container(
              width: double.infinity,
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withValues(alpha: 0.04)),
              ),
              child: Center(
                child: AppScrollArea(
                  padding: const EdgeInsets.fromLTRB(0, 40, 12, 40),
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
                            child: Text(
                              S.settingsTitle,
                              style: TextStyle(
                                color: AppColors.textPrimary,
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),

                        _SettingsRow(
                          title: S.accountVk,
                          trailing: _LoginButton(
                            hasToken: _hasToken,
                            onTokenChanged: (hasToken) {
                              setState(() {
                                _hasToken = hasToken;
                              });
                            },
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.workMode,
                          trailing: CustomDropdown(
                            value: _workModeLabel(_settings!.workMode),
                            items: const [
                              'Капча',
                              'Звонки',
                              'Авто ВК',
                            ].map(_workModeLabel).toList(),
                            onChanged: (label) {
                              final val = _workModeKey(label);
                              _updateSettings((s) {
                                s.workMode = val;
                                if (val == 'Авто ВК') {
                                  s.hashes = 'Авто ВК';
                                  if (mounted) {
                                    CustomAlert.show(
                                      context,
                                      title: S.errorTitle,
                                      message: S.warnAutoVkPair,
                                      type: AlertType.info,
                                    );
                                  }
                                }
                              });
                            },
                          ),
                        ),
                        if (_settings!.workMode == 'Авто ВК' && !_hasToken)
                          Padding(
                            padding: const EdgeInsets.only(
                              left: 20,
                              top: 4,
                              bottom: 8,
                            ),
                            child: Text(
                              S.warnVkAuthNeeded,
                              style: const TextStyle(
                                color: AppColors.warning,
                                fontSize: 12,
                              ),
                            ),
                          )
                        else
                          const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.hashes,
                          trailing: CustomDropdown(
                            value: _hashesLabel(_settings!.hashes),
                            items: const [
                              'Ручной',
                              'Авто API',
                              'Авто ВК',
                            ].map(_hashesLabel).toList(),
                            onChanged: (label) => _updateSettings(
                              (s) => s.hashes = _hashesKey(label),
                            ),
                          ),
                        ),
                        if (_settings!.hashes == 'Ручной') ...[
                          const SizedBox(height: 8),
                          CustomTextField(
                            controller: _manualHashesCtrl,
                            hint: S.hashHint,
                          ),
                        ],
                        if ((_settings!.hashes == 'Авто API' ||
                                _settings!.hashes == 'Авто ВК') &&
                            !_hasToken)
                          Padding(
                            padding: const EdgeInsets.only(
                              left: 20,
                              top: 4,
                              bottom: 8,
                            ),
                            child: Text(
                              S.warnHashesVkNeeded,
                              style: const TextStyle(
                                color: AppColors.warning,
                                fontSize: 12,
                              ),
                            ),
                          )
                        else
                          const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.masking,
                          trailing: CustomDropdown(
                            value: _maskingLabel(_settings!.masking),
                            items: const [
                              'Простая',
                              'Средняя',
                            ].map(_maskingLabel).toList(),
                            onChanged: (label) => _updateSettings(
                              (s) => s.masking = _maskingKey(label),
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.fingerprint,
                          trailing: CustomDropdown(
                            value: _settings!.fingerprint,
                            items: const ['Firefox', 'Safari', 'Chrome'],
                            onChanged: (val) =>
                                _updateSettings((s) => s.fingerprint = val),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.extraThreads,
                          trailing: _CustomSwitch(
                            value: _settings!.extraThreads,
                            onChanged: (val) =>
                                _updateSettings((s) => s.extraThreads = val),
                          ),
                        ),
                        const SizedBox(height: 16),
                        SectionDivider(text: S.settingsSectionApp),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.language,
                          trailing: CustomDropdown(
                            value: LocaleService.isEnglish
                                ? S.languageEn
                                : S.languageRu,
                            items: [S.languageRu, S.languageEn],
                            onChanged: (label) => LocaleService.setLanguage(
                              label == S.languageEn
                                  ? AppLanguage.english
                                  : AppLanguage.russian,
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.launchAtStart,
                          trailing: _CustomSwitch(
                            value: _startAtLaunch,
                            onChanged: (val) async {
                              if (_autostartBusy || _settings == null) return;
                              _autostartBusy = true;
                              setState(() => _startAtLaunch = val);
                              final ok = val
                                  ? await AutostartService.enable()
                                  : await AutostartService.disable();
                              if (!mounted) return;
                              setState(() {
                                _startAtLaunch = ok ? val : !val;
                                _autostartBusy = false;
                              });
                            },
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.tray,
                          trailing: _CustomSwitch(
                            value: _settings!.tray,
                            onChanged: (val) {
                              _updateSettings((s) => s..tray = val);
                              TrayService.instance.setEnabled(val);
                            },
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.allowInsecureHttp,
                          trailing: _CustomSwitch(
                            value: _settings!.zAllowInsecureHttp,
                            onChanged: _onInsecureHttpChanged,
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: S.developerMode,
                          trailing: _CustomSwitch(
                            value: _settings!.developerMode,
                            onChanged: (val) {
                              _updateSettings((s) => s.developerMode = val);
                              LogService.setDeveloperMode(val);
                            },
                          ),
                        ),
                        const SizedBox(height: 16),
                        SectionDivider(text: S.routingSection),
                        const SizedBox(height: 8),

                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 20,
                            vertical: 16,
                          ),
                          decoration: BoxDecoration(
                            color: AppColors.cardHover,
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(
                              color: Colors.white.withValues(alpha: 0.04),
                            ),
                          ),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Text(
                                    S.routingSection,
                                    style: const TextStyle(
                                      color: AppColors.textPrimary,
                                      fontSize: 15,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  const Spacer(),
                                  _CustomSwitch(
                                    value:
                                        _routingExpanded ||
                                        _settings!.zDomainRouting ||
                                        _settings!.zIpRouting,
                                    onChanged: (val) {
                                      setState(() => _routingExpanded = val);
                                      if (!val) {
                                        _updateSettings((s) {
                                          s.zDomainRouting = false;
                                          s.zIpRouting = false;
                                        });
                                      }
                                    },
                                  ),
                                ],
                              ),
                              if (_routingExpanded ||
                                  _settings!.zDomainRouting ||
                                  _settings!.zIpRouting) ...[
                                const SizedBox(height: 16),
                                Divider(
                                  color: Colors.white.withValues(alpha: 0.08),
                                  height: 1,
                                  thickness: 1,
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    const Icon(
                                      Icons.language_rounded,
                                      color: AppColors.textPrimary,
                                      size: 20,
                                    ),
                                    const SizedBox(width: 12),
                                    Text(
                                      S.domainRouting,
                                      style: const TextStyle(
                                        color: AppColors.textPrimary,
                                        fontSize: 14,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                    const Spacer(),
                                    _CustomSwitch(
                                      value: _settings!.zDomainRouting,
                                      onChanged: (val) => _updateSettings(
                                        (s) => s.zDomainRouting = val,
                                      ),
                                    ),
                                  ],
                                ),
                                if (_settings!.zDomainRouting) ...[
                                  const SizedBox(height: 10),
                                  _RoutingListPlate(
                                    icon: Icons.format_list_bulleted_rounded,
                                    title: S.domainsListButton,
                                    badgeText: () {
                                      final count = _settings!.zDirectDomains
                                          .split(RegExp(r'[\s,]+'))
                                          .where((s) => s.isNotEmpty)
                                          .length;
                                      final parts = <String>[];
                                      if (_settings!.zExcludeRuZones ||
                                          _settings!.zTemplateDomains) {
                                        parts.add(S.ruPresetBadge);
                                      }
                                      if (count > 0) {
                                        parts.add('$count');
                                      }
                                      return parts.isEmpty
                                          ? null
                                          : parts.join(' · ');
                                    }(),
                                    onTap: () async {
                                      final updated =
                                          await RoutingDomainsModal.show(
                                            context,
                                            _settings!,
                                          );
                                      if (updated != null && mounted) {
                                        setState(() => _settings = updated);
                                      }
                                    },
                                  ),
                                ],
                                const SizedBox(height: 16),
                                Divider(
                                  color: Colors.white.withValues(alpha: 0.08),
                                  height: 1,
                                  thickness: 1,
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    const Icon(
                                      Icons.router_rounded,
                                      color: AppColors.textPrimary,
                                      size: 20,
                                    ),
                                    const SizedBox(width: 12),
                                    Text(
                                      S.ipRouting,
                                      style: const TextStyle(
                                        color: AppColors.textPrimary,
                                        fontSize: 14,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                    const Spacer(),
                                    _CustomSwitch(
                                      value: _settings!.zIpRouting,
                                      onChanged: (val) => _updateSettings(
                                        (s) => s.zIpRouting = val,
                                      ),
                                    ),
                                  ],
                                ),
                                if (_settings!.zIpRouting) ...[
                                  const SizedBox(height: 10),
                                  _RoutingListPlate(
                                    icon: Icons.format_list_bulleted_rounded,
                                    title: S.ipsListButton,
                                    badgeText: () {
                                      final count = _settings!.zDirectIps
                                          .split(RegExp(r'[\s,]+'))
                                          .where((s) => s.isNotEmpty)
                                          .length;
                                      return count > 0 ? '$count' : null;
                                    }(),
                                    onTap: () async {
                                      final updated =
                                          await RoutingIpsModal.show(
                                            context,
                                            _settings!,
                                          );
                                      if (updated != null && mounted) {
                                        setState(() => _settings = updated);
                                      }
                                    },
                                  ),
                                ],
                              ],
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
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

class _SettingsRow extends StatelessWidget {
  final String title;
  final Widget trailing;

  const _SettingsRow({required this.title, required this.trailing});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      decoration: BoxDecoration(
        color: AppColors.cardHover,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Text(
            title,
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 15,
              fontWeight: FontWeight.w700,
            ),
          ),
          const Spacer(),
          trailing,
        ],
      ),
    );
  }
}

class _CustomSwitch extends StatelessWidget {
  final bool value;
  final ValueChanged<bool> onChanged;

  const _CustomSwitch({required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: () => onChanged(!value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          width: 44,
          height: 24,
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(
            color: value ? AppColors.buttonPrimary : AppColors.buttonSecondary,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: value
                  ? Colors.transparent
                  : Colors.white.withValues(alpha: 0.1),
            ),
          ),
          child: AnimatedAlign(
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeInOut,
            alignment: value ? Alignment.centerRight : Alignment.centerLeft,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              width: 18,
              height: 18,
              decoration: BoxDecoration(
                color: value ? Colors.black : Colors.white,
                shape: BoxShape.circle,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _LoginButton extends StatefulWidget {
  final bool hasToken;
  final ValueChanged<bool> onTokenChanged;

  const _LoginButton({required this.hasToken, required this.onTokenChanged});

  @override
  State<_LoginButton> createState() => _LoginButtonState();
}

class _LoginButtonState extends State<_LoginButton> {
  bool _hovering = false;

  Future<void> _handleTap() async {
    if (widget.hasToken) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          backgroundColor: AppColors.card,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: Text(
            S.vkResetConfirmTitle,
            style: TextStyle(color: AppColors.textPrimary, fontSize: 16),
          ),
          content: Text(
            S.vkResetConfirmBody,
            style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(
                S.cancelButton2,
                style: TextStyle(color: AppColors.textSecondary),
              ),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(
                S.resetButton,
                style: TextStyle(color: AppColors.error),
              ),
            ),
          ],
        ),
      );
      if (confirmed != true) return;

      final ok = await VkToken.delete();
      if (!ok) {
        LogService().add(S.vkTokenDeleteFailedShort);
      }
      if (ok) widget.onTokenChanged(false);
      return;
    }

    final accepted = await AlertService.ensureBanDisclaimerAccepted(context);
    if (!accepted) return;

    final error = await VkToken.login();
    if (error != null) {
      LogService().add(S.vkLoginFailedLog(error));
      if (mounted) {
        CustomAlert.show(
          context,
          title: S.vkLoginFailedTitle,
          message: error,
          type: AlertType.warning,
        );
      }
      return;
    }
    widget.onTokenChanged(true);
    if (mounted) {
      final notice = VkToken.lastNotice;
      if (notice != null) {
        CustomAlert.show(
          context,
          title: S.vkLoginWithNoticeTitle,
          message: notice,
          type: AlertType.warning,
        );
      } else {
        CustomAlert.show(
          context,
          title: S.successTitle,
          message: S.vkLoginOkMessage,
          type: AlertType.success,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        onTap: _handleTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: _hovering ? AppColors.cardHover : AppColors.background,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: _hovering
                  ? (widget.hasToken
                        ? Colors.red.withValues(alpha: 0.3)
                        : Colors.white.withValues(alpha: 0.1))
                  : Colors.transparent,
            ),
          ),
          child: Text(
            widget.hasToken ? S.vkResetButton : S.vkLoginButton,
            style: TextStyle(
              color: widget.hasToken ? Colors.redAccent : AppColors.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

class _RoutingListPlate extends StatefulWidget {
  final IconData icon;
  final String title;
  final String? badgeText;
  final VoidCallback onTap;

  const _RoutingListPlate({
    required this.icon,
    required this.title,
    this.badgeText,
    required this.onTap,
  });

  @override
  State<_RoutingListPlate> createState() => _RoutingListPlateState();
}

class _RoutingListPlateState extends State<_RoutingListPlate> {
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
          height: 48,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          decoration: BoxDecoration(
            color: _hovering
                ? Color.lerp(AppColors.buttonSecondary, Colors.white, 0.05)
                : AppColors.buttonSecondary,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: Colors.white.withValues(alpha: _hovering ? 0.08 : 0.04),
            ),
          ),
          child: Row(
            children: [
              Icon(widget.icon, size: 18, color: AppColors.textSecondary),
              const SizedBox(width: 12),
              Text(
                widget.title,
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
              if (widget.badgeText != null) ...[
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    widget.badgeText!,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
              const Spacer(),
              const Icon(
                Icons.chevron_right_rounded,
                color: AppColors.textSecondary,
                size: 20,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
