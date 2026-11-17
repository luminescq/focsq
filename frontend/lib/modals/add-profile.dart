// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math' as math;
import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import 'profile_form_components.dart';
import '../theme/app_colors.dart';
import '../models/server_profile.dart';
import '../service/alert.dart';
import '../service/db.dart';
import '../service/import.dart';

class AddProfileModal extends StatefulWidget {
  const AddProfileModal({super.key});

  static void show(BuildContext context) {
    showGeneralDialog(
      context: context,
      barrierDismissible: true,
      barrierLabel: 'Dismiss',
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 400),
      pageBuilder: (context, animation, secondaryAnimation) => const Padding(
        padding: EdgeInsets.only(left: 88.0),
        child: Center(child: SingleChildScrollView(child: AddProfileModal())),
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
  State<AddProfileModal> createState() => _AddProfileModalState();
}

class _AddProfileModalState extends State<AddProfileModal> {
  final _nameCtrl = TextEditingController();
  final _ipCtrl = TextEditingController();
  final _portCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  double _power = 10;
  double _maxPower = 100;

  String? _ipError;
  String? _portError;
  String? _passError;

  @override
  void initState() {
    super.initState();
    _nameCtrl.text = S.defaultNodeName(math.Random().nextInt(9000) + 1000);
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

  Future<void> _saveProfile() async {
    final ip = _ipCtrl.text.trim();
    final portText = _portCtrl.text.trim();
    final port = int.tryParse(portText);
    final password = _passCtrl.text.trim();

    setState(() {
      _ipError = ip.isEmpty ? S.errIpRequired : null;
      _portError = ImportService.isValidPort(port) ? null : S.errPortRange;
      _passError = password.isEmpty ? S.errPassRequired : null;
    });
    if (_ipError != null || _portError != null || _passError != null) {
      return;
    }

    final profile = ServerProfile(
      name: _nameCtrl.text.trim(),
      ip: ip,
      port: int.parse(portText),
      password: password,
      power: _power.toInt(),
    );
    await DbService.saveProfile(profile);
    if (mounted) {
      Navigator.pop(context);
      AlertService.showProfileAdded(context, profile.name ?? S.unnamedProfile);
    }
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
            ModalTopBar(title: S.addProfile),
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
                  SectionDivider(text: S.profileData),
                  const SizedBox(height: 16),
                  CustomTextField(
                    hint: S.profileNameHint,
                    controller: _nameCtrl,
                  ),
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: CustomTextField(
                          hint: S.ipHint,
                          controller: _ipCtrl,
                          error: _ipError,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: CustomTextField(
                          hint: S.port,
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
                    hint: S.passwordHint,
                    controller: _passCtrl,
                    error: _passError,
                  ),
                  SizedBox(height: _passError != null ? 4 : 12),

                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        S.power,
                        style: TextStyle(
                          color: AppColors.textPrimary,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        S.threadsCount(_power.toInt()),
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
                      min: 10,
                      max: _maxPower,
                      divisions: sliderDivisionsFor(_maxPower),
                      onChanged: (v) => setState(() => _power = v),
                    ),
                  ),
                  const SizedBox(height: 16),

                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: ElevatedButton(
                      onPressed: _saveProfile,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppColors.buttonPrimary,
                        foregroundColor: Colors.black,
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      child: Text(
                        S.save,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
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
