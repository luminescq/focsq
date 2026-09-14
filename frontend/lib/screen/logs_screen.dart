// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../service/logs.dart';
import '../theme/app_colors.dart';
import '../theme/app_icons.dart';
import '../widgets/custom_alert.dart';
import '../widgets/panel_container.dart';
import '../widgets/screen_layout.dart';

class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key});

  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _Persisted {
  static String search = '';
  static double scrollOffset = 0;
  static bool pendingRestore = false;
}

class _LogsScreenState extends State<LogsScreen> {
  final ScrollController _scrollController = ScrollController();
  final TextEditingController _searchController = TextEditingController(
    text: _Persisted.search,
  );

  final FocusNode _searchFocus = FocusNode();

  Timer? _scrollDebounce;
  bool _searchFocused = false;
  late String _query = _searchController.text.trim().toLowerCase();

  @override
  void initState() {
    super.initState();
    LogService().addListener(_scheduleScroll);
    _searchFocus.addListener(_onFocusChanged);
    _searchController.addListener(_onSearchTextChanged);
    _scrollController.addListener(_saveScrollOffset);
    _Persisted.pendingRestore = true;
    WidgetsBinding.instance.addPostFrameCallback((_) => _restoreScroll());
  }

  void _restoreScroll() {
    if (!mounted ||
        !_Persisted.pendingRestore ||
        !_scrollController.hasClients) {
      return;
    }
    _Persisted.pendingRestore = false;
    final target = _Persisted.scrollOffset.clamp(
      0.0,
      _scrollController.position.maxScrollExtent,
    );
    _scrollController.jumpTo(target);
  }

  void _saveScrollOffset() {
    if (_scrollController.hasClients) {
      _Persisted.scrollOffset = _scrollController.offset;
    }
  }

  @override
  void dispose() {
    LogService().removeListener(_scheduleScroll);
    _scrollDebounce?.cancel();
    _searchFocus.removeListener(_onFocusChanged);
    _searchController.removeListener(_onSearchTextChanged);
    _scrollController.removeListener(_saveScrollOffset);
    _searchFocus.dispose();
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scheduleScroll() {
    if (!mounted) return;
    _scrollDebounce?.cancel();
    _scrollDebounce = Timer(const Duration(milliseconds: 50), () {
      if (!mounted || !_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 150),
        curve: Curves.easeOut,
      );
    });
  }

  void _onFocusChanged() {
    final focused = _searchFocus.hasFocus;
    if (focused == _searchFocused) return;
    setState(() => _searchFocused = focused);
  }

  void _onSearchTextChanged() {
    _Persisted.search = _searchController.text;
    _onQueryChanged(_searchController.text);
    setState(() {});
  }

  void _onQueryChanged(String value) {
    final normalized = value.trim().toLowerCase();
    if (normalized == _query) return;
    setState(() => _query = normalized);
  }

  Future<void> _copyLogs() async {
    final lines = LogService().lines;
    if (lines.isEmpty) {
      CustomAlert.show(
        context,
        title: 'Логи пусты',
        message: 'Пока нечего копировать — журнал пуст',
        type: AlertType.info,
      );
      return;
    }
    await Clipboard.setData(ClipboardData(text: lines.join('\n')));
    if (!mounted) return;
    CustomAlert.show(
      context,
      title: 'Успех',
      message: 'Логи скопированы!',
      type: AlertType.success,
    );
  }

  @override
  Widget build(BuildContext context) {
    return ScreenLayout(
      title: 'Логи',
      showDots: true,
      actions: [
        _SearchField(
          controller: _searchController,
          focusNode: _searchFocus,
          focused: _searchFocused,
          query: _query,
        ),
        const Spacer(),
        _ActionIcon(
          asset: AppIcons.copy,
          tooltip: 'Копировать',
          onTap: _copyLogs,
        ),
        const SizedBox(width: 12),
        _ActionIcon(
          asset: AppIcons.clear,
          tooltip: 'Очистить',
          onTap: LogService().clear,
        ),
      ],
      child: _LogsPanel(controller: _scrollController, query: _query),
    );
  }
}

class _SearchField extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool focused;
  final String query;

  const _SearchField({
    required this.controller,
    required this.focusNode,
    required this.focused,
    required this.query,
  });

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      width: 340,
      height: 38,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.03),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: focused
              ? Colors.white.withValues(alpha: 0.35)
              : Colors.white.withValues(alpha: 0.12),
          width: 1,
        ),
      ),
      child: Row(
        children: [
          const Icon(Icons.search, color: AppColors.textSecondary, size: 16),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: controller,
              focusNode: focusNode,
              style: const TextStyle(
                color: AppColors.textPrimary,
                fontSize: 13,
              ),
              cursorColor: Colors.white,
              decoration: const InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: 'Поиск по логам',
                hintStyle: TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 13,
                ),
              ),
            ),
          ),
          if (query.isNotEmpty)
            GestureDetector(
              onTap: controller.clear,
              child: const Icon(
                Icons.close,
                color: AppColors.textSecondary,
                size: 15,
              ),
            ),
        ],
      ),
    );
  }
}

enum _LogLevel { ok, error, warning, info }

class _ParsedLogLine {
  final String? timestamp;
  final String? tag;
  final String body;
  final int count;
  final _LogLevel level;

  const _ParsedLogLine({
    this.timestamp,
    this.tag,
    required this.body,
    this.count = 1,
    required this.level,
  });

  static final _timestampRegex = RegExp(r'^\[(\d{2}:\d{2}:\d{2})\]\s*(.*)$');
  static final _tagRegex = RegExp(r'^\[(.*?)\]\s*(.*)$');
  static final _countRegex = RegExp(r'^(.*?)\s*[\(\[]x(\d+)[\)\]]\s*$');
  static final Map<String, _ParsedLogLine> _cache = {};
  static const int _cacheLimit = 1024;

  factory _ParsedLogLine.parse(String rawLine) {
    final cached = _cache[rawLine];
    if (cached != null) return cached;
    final parsed = _parse(rawLine);
    if (_cache.length >= _cacheLimit) _cache.clear();
    _cache[rawLine] = parsed;
    return parsed;
  }

  static _ParsedLogLine _parse(String rawLine) {
    String? timestamp;
    String content = rawLine.trim();

    final tsMatch = _timestampRegex.firstMatch(content);
    if (tsMatch != null) {
      timestamp = tsMatch.group(1);
      content = tsMatch.group(2)!.trim();
    }

    String? tag;
    String body = content;

    final tagMatch = _tagRegex.firstMatch(content);
    if (tagMatch != null) {
      tag = tagMatch.group(1)!.trim();
      body = tagMatch.group(2)!.trim();
    }

    int count = 1;
    final countMatch = _countRegex.firstMatch(body);
    if (countMatch != null) {
      body = countMatch.group(1)!.trim();
      count = int.tryParse(countMatch.group(2)!) ?? 1;
    }

    final level = _resolveLevel(tag, body, rawLine);

    return _ParsedLogLine(
      timestamp: timestamp,
      tag: tag,
      body: body.isEmpty ? (tag ?? rawLine) : body,
      count: count,
      level: level,
    );
  }

  static _LogLevel _resolveLevel(String? tag, String body, String rawLine) {
    final lower = rawLine.toLowerCase();
    final lowerTag = (tag ?? '').toLowerCase();
    final lowerBody = body.toLowerCase();

    if (lowerTag.contains('err') ||
        lowerTag.contains('ошибк') ||
        lowerTag.contains('фатал') ||
        lower.contains('fatal_auth') ||
        lower.contains('невосстановим') ||
        lowerBody.contains('ошибка') ||
        lowerBody.contains('error') ||
        lowerBody.contains('failed') ||
        lowerBody.contains('refused') ||
        lowerBody.contains('timeout') ||
        lowerBody.contains('✗')) {
      return _LogLevel.error;
    }

    if (lowerTag.contains('warn') ||
        lowerTag.contains('предупрежд') ||
        lowerBody.contains('предупреждение') ||
        lowerBody.contains('warning')) {
      return _LogLevel.warning;
    }

    if (lowerTag == 'ok' ||
        lowerTag == 'net' ||
        lowerTag.contains('wrap') ||
        lowerTag.contains('сеть') ||
        lowerTag.contains('воркер') ||
        lowerBody.contains('готов') ||
        lowerBody.contains('успешно') ||
        lowerBody.contains('success') ||
        lowerBody.contains('✓')) {
      return _LogLevel.ok;
    }

    return _LogLevel.info;
  }
}

class _LogsPanel extends StatefulWidget {
  final ScrollController controller;
  final String query;

  const _LogsPanel({required this.controller, required this.query});

  @override
  State<_LogsPanel> createState() => _LogsPanelState();
}

class _LogsPanelState extends State<_LogsPanel> {
  LogService get _logs => LogService();
  List<String>? _cachedResult;
  List<String>? _cachedSource;
  int? _cachedVersion;
  String? _cachedQuery;

  List<String> get _filteredLogs {
    final all = _logs.lines;
    final version = _logs.version;
    if (!identical(all, _cachedSource) ||
        version != _cachedVersion ||
        widget.query != _cachedQuery) {
      _cachedSource = all;
      _cachedVersion = version;
      _cachedQuery = widget.query;
      _cachedResult = widget.query.isEmpty
          ? all
          : all
                .where((line) => line.toLowerCase().contains(widget.query))
                .toList(growable: false);
    }
    return _cachedResult!;
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _logs,
      builder: (context, _) {
        final allEmpty = _logs.lines.isEmpty;
        final lines = _filteredLogs;

        final Widget content;
        if (allEmpty) {
          content = const Center(
            child: Text(
              'Логи пусты',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 14),
            ),
          );
        } else if (lines.isEmpty) {
          content = Center(
            child: Text(
              'Ничего не найдено по запросу «${widget.query}»',
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 14,
              ),
            ),
          );
        } else {
          content = RawScrollbar(
            controller: widget.controller,
            thumbColor: Colors.white.withValues(alpha: 0.2),
            radius: const Radius.circular(4),
            thickness: 6,
            interactive: true,
            child: ScrollConfiguration(
              behavior: ScrollConfiguration.of(
                context,
              ).copyWith(scrollbars: false),
              child: ListView.separated(
                controller: widget.controller,
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.only(
                  left: 14,
                  top: 12,
                  bottom: 12,
                  right: 18,
                ),
                itemCount: lines.length,
                separatorBuilder: (_, _) => const SizedBox(height: 5),
                itemBuilder: (context, index) {
                  final parsed = _ParsedLogLine.parse(lines[index]);
                  return _LogLineItem(entry: parsed);
                },
              ),
            ),
          );
        }

        return PanelContainer(child: content);
      },
    );
  }
}

class _LogLineItem extends StatelessWidget {
  final _ParsedLogLine entry;

  const _LogLineItem({required this.entry});

  @override
  Widget build(BuildContext context) {
    final isNetworkTag = entry.tag == 'СЕТЬ';
    final (tagBg, tagText, tagBorder) = isNetworkTag
        ? (
            const Color(0xFF0C243B),
            const Color(0xFF38BDF8),
            const Color(0xFF0284C7).withValues(alpha: 0.5),
          )
        : switch (entry.level) {
            _LogLevel.ok => (
              AppColors.success.withValues(alpha: 0.12),
              AppColors.success,
              AppColors.success.withValues(alpha: 0.28),
            ),
            _LogLevel.error => (
              AppColors.error.withValues(alpha: 0.14),
              AppColors.error,
              AppColors.error.withValues(alpha: 0.32),
            ),
            _LogLevel.warning => (
              AppColors.warning.withValues(alpha: 0.12),
              AppColors.warning,
              AppColors.warning.withValues(alpha: 0.28),
            ),
            _LogLevel.info => (
              AppColors.info.withValues(alpha: 0.12),
              AppColors.info,
              AppColors.info.withValues(alpha: 0.28),
            ),
          };

    final textColor = switch (entry.level) {
      _LogLevel.error => AppColors.error.withValues(alpha: 0.95),
      _ => AppColors.textPrimary,
    };

    final isSpecialDivider =
        entry.tag == null &&
        (entry.body.startsWith('──') || entry.body.startsWith('══'));

    if (isSpecialDivider) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Center(
          child: Text(
            entry.body,
            style: const TextStyle(
              color: AppColors.textTertiary,
              fontSize: 12.5,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      );
    }

    final isSpecialHighlight = isNetworkTag;

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2.0),
      padding: isSpecialHighlight
          ? const EdgeInsets.symmetric(horizontal: 8, vertical: 4)
          : const EdgeInsets.symmetric(vertical: 2.0),
      decoration: isSpecialHighlight
          ? BoxDecoration(
              color: const Color(0xFF38BDF8).withValues(alpha: 0.05),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: const Color(0xFF38BDF8).withValues(alpha: 0.18),
                width: 1,
              ),
            )
          : null,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          if (entry.timestamp != null) ...[
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Text(
                entry.timestamp!,
                style: const TextStyle(
                  color: AppColors.textTertiary,
                  fontSize: 11.5,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ],
          if (entry.tag != null) ...[
            Container(
              constraints: const BoxConstraints(minHeight: 22),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: tagBg,
                borderRadius: BorderRadius.circular(100),
                border: Border.all(color: tagBorder, width: 1),
              ),
              child: Text(
                entry.tag!,
                style: TextStyle(
                  color: tagText,
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.3,
                ),
              ),
            ),
            const SizedBox(width: 8),
          ],
          Expanded(child: _buildBodyText(textColor)),
          if (entry.count > 1) ...[
            const SizedBox(width: 8),
            Container(
              constraints: const BoxConstraints(minHeight: 18),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: AppColors.info.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(100),
                border: Border.all(
                  color: AppColors.info.withValues(alpha: 0.28),
                  width: 1,
                ),
              ),
              child: Text(
                'x${entry.count}',
                style: const TextStyle(
                  color: AppColors.info,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildBodyText(Color defaultTextColor) {
    if (entry.tag == 'СЕТЬ') {
      final match = RegExp(
        r'^(Активных потоков:\s*)(\d+)(\s*\|\s*Трафик:\s*)([\d\.]+\s*МБ)(.*)$',
      ).firstMatch(entry.body);
      if (match != null) {
        return RichText(
          text: TextSpan(
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 13.5,
              height: 1.35,
            ),
            children: [
              TextSpan(text: match.group(1)!),
              TextSpan(
                text: match.group(2)!,
                style: const TextStyle(
                  color: Color(0xFF38BDF8),
                  fontWeight: FontWeight.w700,
                ),
              ),
              TextSpan(text: match.group(3)!),
              TextSpan(
                text: match.group(4)!,
                style: const TextStyle(
                  color: AppColors.success,
                  fontWeight: FontWeight.w700,
                ),
              ),
              if (match.group(5)!.isNotEmpty) TextSpan(text: match.group(5)!),
            ],
          ),
        );
      }
    }

    if (entry.tag == 'СТАТУС') {
      final match = RegExp(
        r'^(Подключено\s*·\s*потоков\s*)(\d+)(.*)$',
      ).firstMatch(entry.body);
      if (match != null) {
        return RichText(
          text: TextSpan(
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 13.5,
              height: 1.35,
              fontWeight: FontWeight.w500,
            ),
            children: [
              TextSpan(text: match.group(1)!),
              TextSpan(
                text: match.group(2)!,
                style: const TextStyle(
                  color: AppColors.success,
                  fontWeight: FontWeight.w700,
                ),
              ),
              if (match.group(3)!.isNotEmpty) TextSpan(text: match.group(3)!),
            ],
          ),
        );
      }
    }

    return Text(
      entry.body,
      style: TextStyle(
        color: defaultTextColor,
        fontSize: 13.5,
        fontWeight: entry.level == _LogLevel.error
            ? FontWeight.w600
            : FontWeight.w400,
        height: 1.35,
      ),
    );
  }
}


class _ActionIcon extends StatefulWidget {
  final String asset;
  final String tooltip;
  final VoidCallback onTap;

  const _ActionIcon({
    required this.asset,
    required this.tooltip,
    required this.onTap,
  });

  @override
  State<_ActionIcon> createState() => _ActionIconState();
}

class _ActionIconState extends State<_ActionIcon> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: widget.tooltip,
      textStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 12),
      decoration: BoxDecoration(
        color: AppColors.border,
        borderRadius: BorderRadius.circular(6),
      ),
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hovering = true),
        onExit: (_) => setState(() => _hovering = false),
        child: GestureDetector(
          onTap: widget.onTap,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: _hovering
                  ? Colors.white.withValues(alpha: 0.06)
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(6),
            ),
            child: SvgPicture.asset(
              widget.asset,
              width: 18,
              height: 18,
              colorFilter: ColorFilter.mode(
                _hovering ? Colors.white : Colors.white.withValues(alpha: 0.75),
                BlendMode.srcIn,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
