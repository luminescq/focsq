// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import '../theme/app_colors.dart';
import 'profile_form_components.dart';

class BanDisclaimerModal extends StatelessWidget {
  const BanDisclaimerModal({super.key});

  /// Возвращает true, если пользователь принял дисклеймер, false — если
  /// закрыл его (кнопкой «Отмена» или по клику вне окна).
  static Future<bool> show(BuildContext context) async {
    final result = await showGeneralDialog<bool>(
      context: context,
      barrierDismissible: true,
      barrierLabel: 'Dismiss',
      barrierColor: Colors.black.withValues(alpha: 0.6),
      transitionDuration: const Duration(milliseconds: 350),
      pageBuilder: (context, animation, secondaryAnimation) {
        final screenWidth = MediaQuery.sizeOf(context).width;
        // На десктопе компенсируем ширину бокового сайдбара (72px + 16px = 88px),
        // на узких экранах / эмуляторах центрируем точно по экрану.
        final leftPadding = screenWidth > 600 ? 88.0 : 0.0;
        return Padding(
          padding: EdgeInsets.only(left: leftPadding),
          child: const Center(
            child: SingleChildScrollView(child: BanDisclaimerModal()),
          ),
        );
      },
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
    return result ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.sizeOf(context).width;
    final modalWidth = math.min(440.0, screenWidth - 32);

    return Material(
      color: Colors.transparent,
      child: Container(
        width: modalWidth,
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ModalTopBar(title: S.banDisclaimerTitle),
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withValues(alpha: 0.04)),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.4),
                    blurRadius: 24,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    S.banDisclaimerHeading,
                    style: const TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 16),
                  _buildBullet(
                    title: S.banDisclaimerAsIsTitle,
                    description: S.banDisclaimerAsIsDesc,
                  ),
                  const SizedBox(height: 12),
                  _buildBullet(
                    title: S.banDisclaimerRisksTitle,
                    description: S.banDisclaimerRisksDesc,
                  ),
                  const SizedBox(height: 12),
                  _buildBullet(
                    title: S.banDisclaimerTrafficTitle,
                    description: S.banDisclaimerTrafficDesc,
                  ),
                  const SizedBox(height: 12),
                  _buildBullet(
                    title: S.banDisclaimerClaimsTitle,
                    description: S.banDisclaimerClaimsDesc,
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: ElevatedButton(
                      onPressed: () => Navigator.pop(context, true),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppColors.buttonPrimary,
                        foregroundColor: Colors.black,
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      child: Text(
                        S.banDisclaimerAccept,
                        style: const TextStyle(
                          fontSize: 15,
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

  Widget _buildBullet({required String title, required String description}) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.only(top: 6, right: 8),
          child: Icon(Icons.circle, size: 5, color: AppColors.textPrimary),
        ),
        Expanded(
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(
                  text: '$title: ',
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    height: 1.45,
                  ),
                ),
                TextSpan(
                  text: description,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w400,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
