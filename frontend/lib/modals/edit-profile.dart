// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'profile_form_components.dart';
import '../theme/app_colors.dart';
import '../models/server_profile.dart';
import '../service/alert.dart';
import '../service/db.dart';
import '../service/import.dart';

class EditProfileModal extends StatefulWidget {
  final ServerProfile profile;
  const EditProfileModal({super.key, required this.profile});

  static void show(BuildContext context, ServerProfile profile) {
    showGeneralDialog(
      context: context,
      barrierDismissible: true,
      barrierLabel: 'Dismiss',
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 400),
      pageBuilder: (context, animation, secondaryAnimation) => Padding(
        padding: EdgeInsets.only(left: 88.0),
        child: Center(
          child: SingleChildScrollView(
            child: EditProfileModal(profile: profile),
          ),
        ),
      ),
      transitionBuilder: (context, animation, secondaryAnimation, child) {
        final curve = CurvedAnimation(
          parent: animation,
          curve: Curves.easeOutQuart,
        );
        return SlideTransition(
          position: Tween<Offset>(
            begin: const Offset(0, 0.1),
            end: Offset.zero,
          ).animate(curve),
          child: FadeTransition(opacity: curve, child: child),
        );
      },
    );
  }

  @override
  State<EditProfileModal> createState() => _EditProfileModalState();
}

class _EditProfileModalState extends State<EditProfileModal> {
  late final TextEditingController _nameCtrl;
  late final TextEditingController _ipCtrl;
  late final TextEditingController _portCtrl;
  late final TextEditingController _passCtrl;
  double _power = 9;
  double _maxPower = 90;

  String? _ipError;
  String? _portError;
  String? _passError;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.profile.name);
    _ipCtrl = TextEditingController(text: widget.profile.ip);
    _portCtrl = TextEditingController(
      text: widget.profile.port?.toString() ?? '',
    );
    _passCtrl = TextEditingController(text: widget.profile.password);
    _power = widget.profile.power?.toDouble() ?? 9;
    _ipCtrl.addListener(() => _clearError(() => _ipError = null));
    _portCtrl.addListener(() => _clearError(() => _portError = null));
    _passCtrl.addListener(() => _clearError(() => _passError = null));
    _loadMaxPower();
  }

  void _clearError(VoidCallback clear) {
    if (!mounted) return;
    setState(clear);
  }

  Future<void> _loadMaxPower() async {
    final settings = await DbService.getSettings();
    if (!mounted) return;
    setState(() {
      _maxPower = resolveMaxPowerThreads(settings).toDouble();
      if (_power > _maxPower) _power = _maxPower;
    });
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _ipCtrl.dispose();
    _portCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _updateProfile() async {
    final ip = _ipCtrl.text.trim();
    final portText = _portCtrl.text.trim();
    final port = int.tryParse(portText);
    final password = _passCtrl.text.trim();

    setState(() {
      _ipError = ip.isEmpty ? 'Укажите IP' : null;
      _portError = ImportService.isValidPort(port) ? null : 'Порт: 1–65535';
      _passError = password.isEmpty ? 'Укажите пароль' : null;
    });
    if (_ipError != null || _portError != null || _passError != null) {
      return;
    }

    widget.profile.name = _nameCtrl.text.trim();
    widget.profile.ip = ip;
    widget.profile.port = int.parse(portText);
    widget.profile.password = password;
    widget.profile.power = _power.toInt();

    await DbService.saveProfile(widget.profile);
    if (!mounted) return;
    Navigator.pop(context);
    AlertService.showProfileUpdated(
      context,
      widget.profile.name ?? 'Без имени',
    );
  }

  Future<void> _deleteProfile() async {
    final name = widget.profile.name ?? 'Без имени';
    await DbService.deleteProfile(widget.profile.id);
    if (!mounted) return;
    Navigator.pop(context);
    AlertService.showProfileDeleted(context, name);
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        width: 380,
        margin: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const ModalTopBar(title: 'Редактировать профиль'),
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withValues(alpha: 0.04)),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SectionDivider(text: 'Данные профиля'),
                  const SizedBox(height: 16),
                  CustomTextField(hint: 'Имя профиля', controller: _nameCtrl),
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: CustomTextField(
                          hint: 'IP-адрес',
                          controller: _ipCtrl,
                          error: _ipError,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: CustomTextField(
                          hint: 'Порт',
                          controller: _portCtrl,
                          error: _portError,
                        ),
                      ),
                    ],
                  ),
                  SizedBox(
                    height: (_ipError != null || _portError != null) ? 4 : 12,
                  ),
                  CustomTextField(
                    hint: 'Пароль профиля',
                    controller: _passCtrl,
                    error: _passError,
                  ),
                  SizedBox(height: _passError != null ? 4 : 12),

                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        'Мощность',
                        style: TextStyle(
                          color: AppColors.textPrimary,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        '${_power.toInt()} потоков',
                        style: const TextStyle(
                          color: AppColors.textPrimary,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SliderTheme(
                    data: SliderThemeData(
                      trackHeight: 8,
                      activeTrackColor: AppColors.divider,
                      inactiveTrackColor: AppColors.buttonSecondary,
                      thumbColor: AppColors.buttonPrimary,
                      overlayColor: Colors.white.withValues(alpha: 0.1),
                      thumbShape: const RoundSliderThumbShape(
                        enabledThumbRadius: 8,
                      ),
                      overlayShape: const RoundSliderOverlayShape(
                        overlayRadius: 16,
                      ),
                    ),
                    child: Slider(
                      value: _power,
                      min: 9,
                      max: _maxPower,
                      divisions: ((_maxPower - 9) / 9).round(),
                      onChanged: (v) => setState(() => _power = v),
                    ),
                  ),
                  const SizedBox(height: 16),

                  Row(
                    children: [
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _updateProfile,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonPrimary,
                              foregroundColor: Colors.black,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: const Text(
                              'Сохранить',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _deleteProfile,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonSecondary,
                              foregroundColor: Colors.white,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: const Text(
                              'Удалить',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
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
    );
  }
}
