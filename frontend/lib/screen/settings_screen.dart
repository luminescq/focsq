// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import '../modals/profile_form_components.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';
import '../models/app_settings.dart';
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
  bool _hasToken = false;
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
                const Text(
                  'Настройки',
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
                            child: const Text(
                              'Настройки',
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
                          title: 'Аккаунт ВКонтакте',
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
                          title: 'Режим работы',
                          trailing: CustomDropdown(
                            value: _settings!.workMode,
                            items: const ['Капча', 'Звонки', 'Авто ВК'],
                            onChanged: (val) => _updateSettings((s) {
                              s.workMode = val;
                              if (val == 'Авто ВК') {
                                s.hashes = 'Авто ВК';
                                if (mounted) {
                                  CustomAlert.show(
                                    context,
                                    title: 'Внимание',
                                    message:
                                        'Режим работы "Авто ВК" работает только в паре с хешами "Авто ВК"',
                                    type: AlertType.info,
                                  );
                                }
                              }
                            }),
                          ),
                        ),
                        if (_settings!.workMode == 'Авто ВК' && !_hasToken)
                          const Padding(
                            padding: EdgeInsets.only(
                              left: 20,
                              top: 4,
                              bottom: 8,
                            ),
                            child: Text(
                              '⚠️ Для этого режима требуется авторизация ВКонтакте',
                              style: TextStyle(
                                color: AppColors.warning,
                                fontSize: 12,
                              ),
                            ),
                          )
                        else
                          const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Хеши',
                          trailing: CustomDropdown(
                            value: _settings!.hashes,
                            items: const ['Ручной', 'Авто API', 'Авто ВК'],
                            onChanged: (val) => _updateSettings((s) {
                              s.hashes = val;
                            }),
                          ),
                        ),
                        if (_settings!.hashes == 'Ручной') ...[
                          const SizedBox(height: 8),
                          CustomTextField(
                            controller: _manualHashesCtrl,
                            hint: 'Хеши через запятую',
                          ),
                        ],
                        if ((_settings!.hashes == 'Авто API' ||
                                _settings!.hashes == 'Авто ВК') &&
                            !_hasToken)
                          const Padding(
                            padding: EdgeInsets.only(
                              left: 20,
                              top: 4,
                              bottom: 8,
                            ),
                            child: Text(
                              '⚠️ Для получения хешей этим способом требуется авторизация ВКонтакте (вечный токен)',
                              style: TextStyle(
                                color: AppColors.warning,
                                fontSize: 12,
                              ),
                            ),
                          )
                        else
                          const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Маскировка',
                          trailing: CustomDropdown(
                            value: _settings!.masking,
                            items: const ['Простая', 'Средняя'],
                            onChanged: (val) =>
                                _updateSettings((s) => s.masking = val),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Отпечаток',
                          trailing: CustomDropdown(
                            value: _settings!.fingerprint,
                            items: const ['Firefox', 'Safari', 'Chrome'],
                            onChanged: (val) =>
                                _updateSettings((s) => s.fingerprint = val),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Экстра потоки',
                          trailing: _CustomSwitch(
                            value: _settings!.extraThreads,
                            onChanged: (val) =>
                                _updateSettings((s) => s.extraThreads = val),
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Трей',
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
                          title: 'Запуск при старте',
                          trailing: _CustomSwitch(
                            value: _startAtLaunch,
                            onChanged: (val) async {
                              if (val) {
                                await AutostartService.enable();
                              } else {
                                await AutostartService.disable();
                              }
                              setState(() => _startAtLaunch = val);
                            },
                          ),
                        ),
                        const SizedBox(height: 8),

                        _SettingsRow(
                          title: 'Режим разработчика',
                          trailing: _CustomSwitch(
                            value: _settings!.developerMode,
                            onChanged: (val) {
                              _updateSettings((s) => s.developerMode = val);
                              LogService.setDeveloperMode(val);
                            },
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
          title: const Text(
            'Сбросить авторизацию?',
            style: TextStyle(color: AppColors.textPrimary, fontSize: 16),
          ),
          content: const Text(
            'Режимы «Авто API» и «Авто ВК» перестанут работать до повторного входа.',
            style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text(
                'Отмена',
                style: TextStyle(color: AppColors.textSecondary),
              ),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text(
                'Сбросить',
                style: TextStyle(color: AppColors.error),
              ),
            ),
          ],
        ),
      );
      if (confirmed != true) return;

      final ok = await VkToken.delete();
      if (!ok) {
        LogService().add('[ВК] Не удалось удалить токен');
      }
      if (ok) widget.onTokenChanged(false);
      return;
    }

    final error = await VkToken.login();
    if (error != null) {
      LogService().add('[ВК] Вход не завершён: $error');
      if (mounted) {
        CustomAlert.show(
          context,
          title: 'Вход не завершён',
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
          title: 'Авторизация пройдена (с оговоркой)',
          message: notice,
          type: AlertType.warning,
        );
      } else {
        CustomAlert.show(
          context,
          title: 'Успех',
          message: 'Авторизация ВКонтакте пройдена',
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
            widget.hasToken ? 'Сброс ВК' : 'Логин',
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
