// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import '../models/app_settings.dart';
import '../service/db.dart';
import '../theme/app_colors.dart';
import 'profile_form_components.dart';

class RoutingDomainsModal extends StatefulWidget {
  final AppSettings initialSettings;

  const RoutingDomainsModal({super.key, required this.initialSettings});

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
            child: RoutingDomainsModal(initialSettings: settings),
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
  State<RoutingDomainsModal> createState() => _RoutingDomainsModalState();
}

class _RoutingDomainsModalState extends State<RoutingDomainsModal> {
  late bool _excludeRu;
  late final TextEditingController _domainsCtrl;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _excludeRu =
        widget.initialSettings.zExcludeRuZones ||
        widget.initialSettings.zTemplateDomains;

    final raw = widget.initialSettings.zDirectDomains.trim();
    final formatted = raw.isEmpty
        ? ''
        : raw.split(RegExp(r'[\s,]+')).where((s) => s.isNotEmpty).join('\n');
    _domainsCtrl = TextEditingController(text: formatted);
  }

  @override
  void dispose() {
    _domainsCtrl.dispose();
    super.dispose();
  }

  int get _domainsCount {
    return _domainsCtrl.text
        .split(RegExp(r'[\s,\n\r]+'))
        .where((s) => s.isNotEmpty)
        .length;
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    final list = _domainsCtrl.text
        .split(RegExp(r'[\s,\n\r]+'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    final directDomainsStr = list.join(', ');

    final updated = await DbService.updateSettings((s) {
      s.zExcludeRuZones = _excludeRu;
      s.zTemplateDomains = _excludeRu;
      s.zDomainWhitelist = false;
      s.zDirectDomains = directDomainsStr;
      return s;
    });

    if (!mounted) return;
    Navigator.of(context).pop(updated);
  }

  @override
  Widget build(BuildContext context) {
    final count = _domainsCount;

    return Material(
      color: Colors.transparent,
      child: Container(
        width: 440,
        margin: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ModalTopBar(title: S.routingDomainsTitle),
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
                  // Готовый набор исключения русских доменов
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 14,
                    ),
                    decoration: BoxDecoration(
                      color: AppColors.cardHover,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                S.excludeRuPreset,
                                style: const TextStyle(
                                  color: AppColors.textPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                S.excludeRuPresetHint,
                                style: const TextStyle(
                                  color: AppColors.textSecondary,
                                  fontSize: 11,
                                  height: 1.3,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 14),
                        CustomSwitch(
                          value: _excludeRu,
                          onChanged: (val) => setState(() => _excludeRu = val),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),

                  // Заголовок поля и счетчик
                  Row(
                    children: [
                      Text(
                        S.customDomainsLabel,
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

                  // Текстовая область доменов
                  CustomTextArea(
                    hint: S.customDomainsHint,
                    controller: _domainsCtrl,
                    minLines: 5,
                    maxLines: 8,
                  ),
                  const SizedBox(height: 20),

                  // Кнопки Отмена / Сохранить в нашем фирменном стиле
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
