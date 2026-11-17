// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import '../models/app_settings.dart';
import '../service/db.dart';
import '../theme/app_colors.dart';
import 'profile_form_components.dart';

class RoutingIpsModal extends StatefulWidget {
  final AppSettings initialSettings;

  const RoutingIpsModal({super.key, required this.initialSettings});

  static Future<AppSettings?> show(BuildContext context, AppSettings settings) {
    final screenWidth = MediaQuery.of(context).size.width;
    return showGeneralDialog<AppSettings>(
      context: context,
      barrierDismissible: true,
      barrierLabel: S.dismiss,
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 300),
      pageBuilder: (context, animation, secondaryAnimation) => Padding(
        padding: EdgeInsets.only(left: screenWidth > 600 ? 88.0 : 0.0),
        child: Center(
          child: SingleChildScrollView(
            child: RoutingIpsModal(initialSettings: settings),
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
            begin: const Offset(0, 0.08),
            end: Offset.zero,
          ).animate(curve),
          child: FadeTransition(opacity: curve, child: child),
        );
      },
    );
  }

  @override
  State<RoutingIpsModal> createState() => _RoutingIpsModalState();
}

class _RoutingIpsModalState extends State<RoutingIpsModal> {
  late final TextEditingController _ipsCtrl;
  bool _saving = false;

  @override
  void initState() {
    super.initState();

    final raw = widget.initialSettings.zDirectIps.trim();
    final formatted = raw.isEmpty
        ? ''
        : raw.split(RegExp(r'[\s,]+')).where((s) => s.isNotEmpty).join('\n');
    _ipsCtrl = TextEditingController(text: formatted);
  }

  @override
  void dispose() {
    _ipsCtrl.dispose();
    super.dispose();
  }

  int get _ipsCount {
    return _ipsCtrl.text
        .split(RegExp(r'[\s,\n\r]+'))
        .where((s) => s.isNotEmpty)
        .length;
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    final list = _ipsCtrl.text
        .split(RegExp(r'[\s,\n\r]+'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    final directIpsStr = list.join(', ');

    final updated = await DbService.updateSettings((s) {
      s.zIpWhitelist = false;
      s.zDirectIps = directIpsStr;
      return s;
    });

    if (!mounted) return;
    Navigator.of(context).pop(updated);
  }

  @override
  Widget build(BuildContext context) {
    final count = _ipsCount;

    return Material(
      color: Colors.transparent,
      child: Container(
        width: 440,
        margin: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ModalTopBar(title: S.routingIpsTitle),
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
                  // Заголовок поля и счетчик
                  Row(
                    children: [
                      Text(
                        S.customIpsLabel,
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const Spacer(),
                      if (count > 0)
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
                            '$count',
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),

                  // Текстовая область IP
                  CustomTextArea(
                    hint: S.customIpsHint,
                    controller: _ipsCtrl,
                    minLines: 6,
                    maxLines: 9,
                  ),
                  const SizedBox(height: 20),

                  // Кнопки Отмена / Сохранить
                  Row(
                    children: [
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _saving
                                ? null
                                : () => Navigator.of(context).pop(),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonSecondary,
                              foregroundColor: Colors.white,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: Text(
                              S.cancelButton,
                              maxLines: 1,
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
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _saving ? null : _save,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonPrimary,
                              foregroundColor: Colors.black,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: _saving
                                ? const SizedBox(
                                    width: 20,
                                    height: 20,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: Colors.black,
                                    ),
                                  )
                                : Text(
                                    S.save,
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
    );
  }
}
