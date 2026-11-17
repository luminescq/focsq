// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';

import '../l10n/app_locale.dart';
import '../service/subscription.dart';
import '../theme/app_colors.dart';
import 'profile_form_components.dart';

class SubscriptionImportModal extends StatefulWidget {
  const SubscriptionImportModal({super.key});

  static Future<SubscriptionResult?> show(BuildContext context) {
    return showGeneralDialog<SubscriptionResult>(
      context: context,
      barrierDismissible: true,
      barrierLabel: S.dismiss,
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 400),
      pageBuilder: (context, animation, secondaryAnimation) => const Padding(
        padding: EdgeInsets.only(left: 88.0),
        child: Center(
          child: SingleChildScrollView(child: SubscriptionImportModal()),
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
  State<SubscriptionImportModal> createState() =>
      _SubscriptionImportModalState();
}

class _SubscriptionImportModalState extends State<SubscriptionImportModal> {
  final _urlCtrl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _urlCtrl.addListener(() {
      if (_error == null || !mounted) return;
      setState(() => _error = null);
    });
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    super.dispose();
  }

  Future<void> _import() async {
    final url = _urlCtrl.text.trim();
    if (url.isEmpty) {
      setState(() => _error = S.subscriptionUrlRequired);
      return;
    }

    setState(() {
      _busy = true;
      _error = null;
    });

    SubscriptionResult result;
    try {
      result = await SubscriptionService.importSubscription(url);
    } catch (_) {
      result = SubscriptionResult(
        success: false,
        problem: SubscriptionProblem.networkError,
        errorMessage: S.subscriptionFetchFailed,
      );
    }

    if (!mounted) return;
    Navigator.of(context).pop(result);
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        width: 400,
        margin: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ModalTopBar(title: S.subscriptionImportTitle),
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
                  SectionDivider(text: S.addFromSubscription),
                  const SizedBox(height: 16),
                  CustomTextField(
                    hint: S.subscriptionUrlHint,
                    controller: _urlCtrl,
                    error: _error,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    S.subscriptionImportHint,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 11,
                      height: 1.4,
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _busy
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
                            onPressed: _busy ? null : _import,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonPrimary,
                              foregroundColor: Colors.black,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: _busy
                                ? const SizedBox(
                                    width: 20,
                                    height: 20,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: Colors.black,
                                    ),
                                  )
                                : Text(
                                    S.importButton,
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
