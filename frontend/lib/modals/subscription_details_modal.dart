// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_locale.dart';
import '../models/profile_group.dart';
import '../service/alert.dart';
import '../service/db.dart';
import '../service/subscription_manager.dart';
import '../theme/app_colors.dart';
import '../widgets/custom_alert.dart';
import 'profile_form_components.dart';
import 'subscription_delete.dart';

class SubscriptionDetailsModal extends StatefulWidget {
  final ProfileGroup group;

  const SubscriptionDetailsModal({super.key, required this.group});

  static Future<void> show(BuildContext context, ProfileGroup group) {
    return showGeneralDialog(
      context: context,
      barrierDismissible: true,
      barrierLabel: S.dismiss,
      barrierColor: Colors.black.withValues(alpha: 0.5),
      transitionDuration: const Duration(milliseconds: 350),
      pageBuilder: (context, animation, secondaryAnimation) => Padding(
        padding: const EdgeInsets.only(left: 88.0),
        child: Center(
          child: SingleChildScrollView(
            child: SubscriptionDetailsModal(group: group),
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
  State<SubscriptionDetailsModal> createState() =>
      _SubscriptionDetailsModalState();
}

class _SubscriptionDetailsModalState extends State<SubscriptionDetailsModal> {
  late final TextEditingController _titleCtrl;
  late final TextEditingController _urlCtrl;
  late final SubscriptionMeta _meta;
  late int _selectedInterval;
  bool _busy = false;
  bool _copied = false;
  String? _urlError;

  static const List<int> _intervals = [0, 2, 30, 60, 120, 360, 720, 1440];

  bool _allowHttp = false;

  String? _urlErrorFor(String value) {
    final uri = Uri.tryParse(value);
    if (uri == null || uri.host.isEmpty) return S.subscriptionUrlInvalid;
    if (uri.scheme == 'https') return null;
    if (uri.scheme == 'http') {
      return _allowHttp ? null : S.subscriptionHttpDisabled;
    }
    return S.subscriptionUrlInvalid;
  }

  int _intervalToIndex(int minutes) {
    final idx = _intervals.indexOf(minutes);
    if (idx != -1) return idx;
    var closestIdx = 0;
    var minDiff = (minutes - _intervals[0]).abs();
    for (var i = 1; i < _intervals.length; i++) {
      final diff = (minutes - _intervals[i]).abs();
      if (diff < minDiff) {
        minDiff = diff;
        closestIdx = i;
      }
    }
    return closestIdx;
  }

  @override
  void initState() {
    super.initState();
    final url = widget.group.subscriptionUrl ?? '';
    _meta = SubscriptionManager.instance.getMeta(
      url,
      defaultTitle: widget.group.title,
    );
    _titleCtrl = TextEditingController(
      text: _meta.title.isNotEmpty ? _meta.title : widget.group.title,
    );
    _urlCtrl = TextEditingController(text: url);
    _urlCtrl.addListener(() {
      if (_urlError == null || !mounted) return;
      setState(() => _urlError = null);
    });
    _selectedInterval = _meta.autoUpdateMinutes;
    _loadHttpPermission();
  }

  Future<void> _loadHttpPermission() async {
    try {
      final settings = await DbService.getSettings();
      if (mounted) setState(() => _allowHttp = settings.zAllowInsecureHttp);
    } catch (_) {}
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _urlCtrl.dispose();
    super.dispose();
  }

  String _formatLastUpdated(DateTime? date) {
    if (date == null) return S.neverUpdated;
    final now = DateTime.now();
    final diff = now.difference(date);
    if (diff.inSeconds < 60) return S.updatedJustNow;
    if (diff.inMinutes < 60) return S.updatedMinutesAgo(diff.inMinutes);
    if (diff.inHours < 24) return S.updatedHoursAgo(diff.inHours);
    final day = date.day.toString().padLeft(2, '0');
    final month = date.month.toString().padLeft(2, '0');
    final hour = date.hour.toString().padLeft(2, '0');
    final minute = date.minute.toString().padLeft(2, '0');
    return '$day.$month $hour:$minute';
  }

  Future<void> _refreshNow() async {
    final oldUrl = widget.group.subscriptionUrl ?? '';
    final url = _urlCtrl.text.trim();
    if (url.isEmpty) return;

    if (url != oldUrl) {
      final error = _urlErrorFor(url);
      if (error != null) {
        setState(() => _urlError = error);
        return;
      }
    }

    setState(() => _busy = true);
    try {
      if (url != oldUrl) {
        await SubscriptionManager.instance.changeUrl(
          oldUrl: oldUrl,
          newUrl: url,
          profiles: widget.group.profiles,
          meta: _meta,
        );
        widget.group.subscriptionUrl = url;
      }

      final result = await SubscriptionManager.instance.updateSubscription(url);
      if (!mounted) return;
      if (result.success) {
        setState(() {
          _meta.lastUpdated = DateTime.now();
        });
        AlertService.showSubscriptionResult(context, result);
      } else {
        CustomAlert.show(
          context,
          title: S.errorTitle,
          message: result.errorMessage ?? S.subscriptionFetchFailed,
          type: AlertType.warning,
        );
      }
    } catch (_) {
      if (!mounted) return;
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: S.subscriptionFetchFailed,
        type: AlertType.warning,
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save() async {
    final newTitle = _titleCtrl.text.trim();
    final oldTitle = widget.group.title;
    final oldUrl = widget.group.subscriptionUrl ?? '';
    final newUrl = _urlCtrl.text.trim();

    if (newUrl != oldUrl) {
      final error = _urlErrorFor(newUrl);
      if (error != null) {
        setState(() => _urlError = error);
        return;
      }
    }

    setState(() => _busy = true);
    try {
      _meta.autoUpdateMinutes = _selectedInterval;
      if (newUrl != oldUrl) {
        await SubscriptionManager.instance.changeUrl(
          oldUrl: oldUrl,
          newUrl: newUrl,
          profiles: widget.group.profiles,
          meta: _meta,
        );
        widget.group.subscriptionUrl = newUrl;
      } else {
        await SubscriptionManager.instance.saveMeta(_meta);
      }

      if (newTitle.isNotEmpty && newTitle != oldTitle) {
        await SubscriptionManager.instance.renameSubscription(
          url: newUrl,
          oldTitle: oldTitle,
          newTitle: newTitle,
          profiles: widget.group.profiles,
        );
      }

      if (mounted) {
        Navigator.of(context).pop();
      }
    } catch (_) {
      if (!mounted) return;
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: S.subscriptionSaveFailed,
        type: AlertType.warning,
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _deleteSubscription() async {
    final confirmed = await SubscriptionDeleteDialog.show(
      context,
      widget.group.title,
    );
    if (!confirmed || !mounted) return;

    setState(() => _busy = true);
    try {
      final url = widget.group.subscriptionUrl ?? '';
      await SubscriptionManager.instance.deleteSubscription(
        url: url,
        profiles: widget.group.profiles,
      );
      if (mounted) {
        Navigator.of(context).pop();
        AlertService.showSubscriptionDeleted(context, widget.group.title);
      }
    } catch (_) {
      if (!mounted) return;
      CustomAlert.show(
        context,
        title: S.errorTitle,
        message: S.subscriptionDeleteFailed,
        type: AlertType.warning,
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _copyUrl() {
    final url = _urlCtrl.text.trim();
    if (url.isEmpty) return;
    Clipboard.setData(ClipboardData(text: url));
    setState(() => _copied = true);
    Future.delayed(const Duration(seconds: 2), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final url = widget.group.subscriptionUrl ?? '';

    return Material(
      color: Colors.transparent,
      child: Container(
        width: 400,
        margin: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ModalTopBar(title: S.subscriptionDetailsTitle),
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withValues(alpha: 0.05)),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SectionDivider(text: S.subscriptionNameSection),
                  const SizedBox(height: 12),
                  CustomTextField(
                    hint: S.subscriptionNameLabel,
                    controller: _titleCtrl,
                  ),
                  const SizedBox(height: 18),

                  if (url.isNotEmpty) ...[
                    SectionDivider(text: S.subscriptionUrlSection),
                    const SizedBox(height: 12),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: CustomTextField(
                            hint: S.subscriptionUrlSection,
                            controller: _urlCtrl,
                            error: _urlError,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Container(
                          height: 48,
                          width: 48,
                          decoration: BoxDecoration(
                            color: AppColors.buttonSecondary,
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Tooltip(
                            message: _copied ? S.copied : S.copy,
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                borderRadius: BorderRadius.circular(10),
                                onTap: _copyUrl,
                                child: Center(
                                  child: Icon(
                                    _copied
                                        ? Icons.check_rounded
                                        : Icons.copy_rounded,
                                    size: 18,
                                    color: _copied
                                        ? AppColors.buttonPrimary
                                        : Colors.white.withValues(alpha: 0.8),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 18),
                  ],

                  SectionDivider(text: S.autoUpdateSection),
                  const SizedBox(height: 12),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        S.intervalLabel,
                        style: const TextStyle(
                          color: AppColors.textPrimary,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        S.subscriptionIntervalLabel(_selectedInterval),
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
                      value: _intervalToIndex(_selectedInterval).toDouble(),
                      min: 0,
                      max: (_intervals.length - 1).toDouble(),
                      divisions: _intervals.length - 1,
                      onChanged: (v) {
                        setState(() {
                          _selectedInterval = _intervals[v.round()];
                        });
                      },
                    ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Icon(
                        Icons.access_time_rounded,
                        size: 13,
                        color: Colors.white.withValues(alpha: 0.4),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          S.updatedAt(_formatLastUpdated(_meta.lastUpdated)),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.5),
                            fontSize: 11,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        S.serversCount(widget.group.profiles.length),
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.5),
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 24),

                  // Кнопки действий
                  Row(
                    children: [
                      // Кнопка быстрого обновления
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _busy || url.isEmpty
                                ? null
                                : _refreshNow,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonSecondary,
                              foregroundColor: Colors.white,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: _busy
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: Colors.white,
                                    ),
                                  )
                                : Text(
                                    S.refreshButton,
                                    style: const TextStyle(
                                      fontSize: 14,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      // Удалить подписку
                      Expanded(
                        child: SizedBox(
                          height: 48,
                          child: ElevatedButton(
                            onPressed: _busy ? null : _deleteSubscription,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppColors.buttonSecondary,
                              foregroundColor: Colors.white,
                              elevation: 0,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                            ),
                            child: Text(
                              S.delete,
                              style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  // Кнопка Сохранить (на всю ширину)
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: ElevatedButton(
                      onPressed: _busy ? null : _save,
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
                        style: const TextStyle(
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
