// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import 'dart:io';

import 'package:flutter/material.dart';

import '../service/split_tunnel.dart';
import '../theme/app_colors.dart';

// [FOCSQ] Редактор хранит черновик до явного сохранения; ядро получает правила при подключении.
class SplitTunnelCard extends StatefulWidget {
  final bool? supported;
  final Future<SplitTunnelSettings> Function() load;
  final Future<void> Function(SplitTunnelSettings) save;
  final Future<List<String>> Function()? loadApplications;

  const SplitTunnelCard({
    super.key,
    this.supported,
    this.load = SplitTunnelStore.load,
    this.save = SplitTunnelStore.save,
    this.loadApplications,
  });

  @override
  State<SplitTunnelCard> createState() => _SplitTunnelCardState();
}

class _SplitTunnelCardState extends State<SplitTunnelCard> {
  SplitTunnelSettings? _settings;
  final _controllers = {
    for (final kind in SplitTunnelKind.values) kind: TextEditingController(),
  };
  final _drafts = <SplitTunnelKind, Map<SplitTunnelMode, String>>{};
  String? _error;
  String? _notice;
  bool _saving = false;
  bool _loadingApps = false;
  bool _dirty = false;

  bool get _supported => widget.supported ?? Platform.isWindows;

  @override
  void initState() {
    super.initState();
    if (_supported) _load();
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  // [FOCSQ] Повреждённый файл открывает экран восстановления, а не молча включает полный туннель.
  Future<void> _load() async {
    try {
      final settings = await widget.load();
      if (mounted) setState(() => _setDraft(settings));
    } catch (error) {
      if (mounted) {
        setState(
          () => _error =
              'Не удалось прочитать split_tunnel.json. ${_message(error)}',
        );
      }
    }
  }

  void _setDraft(SplitTunnelSettings settings) {
    _settings = settings;
    _error = null;
    _notice = null;
    _dirty = false;
    for (final kind in SplitTunnelKind.values) {
      final rules = settings.rules(kind);
      _drafts[kind] = {
        SplitTunnelMode.include: rules.include.join('\n'),
        SplitTunnelMode.exclude: rules.exclude.join('\n'),
      };
      _controllers[kind]!.text = rules.active.join('\n');
    }
  }

  // [FOCSQ] Переключение редактирует независимые списки и сохраняет незавершённый текст в памяти.
  void _changeMode(SplitTunnelKind kind, SplitTunnelMode mode) {
    final previous = _settings!.rules(kind);
    if (previous.mode != SplitTunnelMode.off) {
      _drafts[kind]![previous.mode] = _controllers[kind]!.text;
    }
    setState(() {
      _settings = _settings!.withRules(kind, previous.copyWith(mode: mode));
      _controllers[kind]!.text =
          _drafts[kind]![mode == SplitTunnelMode.include
              ? SplitTunnelMode.include
              : SplitTunnelMode.exclude]!;
      _error = null;
      _notice = null;
      _dirty = true;
    });
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() {
      _saving = true;
      _error = null;
      _notice = null;
    });
    try {
      var settings = _settings!;
      for (final kind in SplitTunnelKind.values) {
        final rules = settings.rules(kind);
        if (rules.mode != SplitTunnelMode.off) {
          _drafts[kind]![rules.mode] = _controllers[kind]!.text;
        }
        settings = settings.withRules(
          kind,
          rules.copyWith(
            include: parseSplitEntries(
              kind,
              _drafts[kind]![SplitTunnelMode.include]!,
            ),
            exclude: parseSplitEntries(
              kind,
              _drafts[kind]![SplitTunnelMode.exclude]!,
            ),
          ),
        );
      }
      settings = settings.normalized();
      await widget.save(settings);
      if (!mounted) return;
      setState(() {
        _setDraft(settings);
        _notice = 'Сохранено. Правила применятся после переподключения.';
      });
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  // [FOCSQ] Выбор запущенных приложений дополняет ручные пути, сохраняя неработающие сейчас EXE.
  Future<void> _chooseApplications() async {
    if (_loadingApps || widget.loadApplications == null) return;
    setState(() => _loadingApps = true);
    try {
      final existing = parseSplitEntries(
        SplitTunnelKind.apps,
        _controllers[SplitTunnelKind.apps]!.text,
      );
      final running = normalizeSplitEntries(
        SplitTunnelKind.apps,
        await widget.loadApplications!(),
      );
      if (!mounted) return;
      if (running.isEmpty) {
        setState(
          () => _notice = 'Запущенные приложения не найдены. Путь к EXE можно добавить вручную.',
        );
        return;
      }
      final selected = existing.map((path) => path.toLowerCase()).toSet();
      final result = await showDialog<List<String>>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, update) {
            return AlertDialog(
              backgroundColor: AppColors.card,
              title: const Text('Запущенные приложения'),
              content: SizedBox(
                width: 600,
                height: 360,
                child: ListView.builder(
                  itemCount: running.length,
                  itemBuilder: (context, index) {
                    final path = running[index];
                    return CheckboxListTile(
                      value: selected.contains(path.toLowerCase()),
                      title: Text(path.split(r'\').last),
                      subtitle: Text(
                        path,
                        style: const TextStyle(fontSize: 11),
                      ),
                      controlAffinity: ListTileControlAffinity.leading,
                      onChanged: (value) => update(() {
                        if (value == true) {
                          selected.add(path.toLowerCase());
                        } else {
                          selected.remove(path.toLowerCase());
                        }
                      }),
                    );
                  },
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Отмена'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(
                    context,
                    running
                        .where((path) => selected.contains(path.toLowerCase()))
                        .toList(),
                  ),
                  child: const Text('Выбрать'),
                ),
              ],
            );
          },
        ),
      );
      if (result == null || !mounted) return;
      final visible = running.map((path) => path.toLowerCase()).toSet();
      setState(() {
        _controllers[SplitTunnelKind.apps]!.text = [
          ...existing.where((path) => !visible.contains(path.toLowerCase())),
          ...result,
        ].join('\n');
        _dirty = true;
        _error = null;
        _notice = null;
      });
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _loadingApps = false);
    }
  }

  String _message(Object error) => error is FormatException
      ? error.message
      : 'Не удалось выполнить операцию (${error.runtimeType}). Проверьте доступ к папке данных приложения.';

  @override
  Widget build(BuildContext context) {
    // [FOCSQ] ListTile внутри ExpansionTile требует ближайший Material для ink-эффектов.
    return Material(
      color: AppColors.cardHover,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        title: const Text(
          'Раздельное туннелирование',
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
        subtitle: Text(
          !_supported
              ? 'Доступно на Windows'
              : _settings == null
              ? 'Приложения, домены и IP'
              : _settings!.enabled
              ? 'Правила включены'
              : 'Выключено',
          style: const TextStyle(color: AppColors.textSecondary, fontSize: 12),
        ),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          if (!_supported)
            const Text(
              'Настройка split tunnelling доступна для Windows.',
              style: TextStyle(color: AppColors.textSecondary),
            )
          else if (_settings == null) ...[
            if (_error == null)
              const CircularProgressIndicator()
            else ...[
              _feedback(_error!, AppColors.error),
              Wrap(
                children: [
                  TextButton(
                    onPressed: _load,
                    child: const Text('Повторить чтение'),
                  ),
                  TextButton(
                    onPressed: () => setState(() {
                      _setDraft(const SplitTunnelSettings());
                      _dirty = true;
                    }),
                    child: const Text('Настроить заново'),
                  ),
                ],
              ),
            ],
          ] else ...[
            const Text(
              'Правила применяются после переподключения к серверу. Они действуют для IPv4 TCP/UDP. '
              'Маршруты локальных сетей и других VPN сохраняют свой приоритет.',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
            ),
            const SizedBox(height: 8),
            // [FOCSQ] Приложения ограничивают доступ к туннелю, затем применяются доменные/IP-правила.
            const Text(
              'Сначала учитывается приложение. Для его трафика исключения доменов/IP имеют приоритет; '
              'списки «Только выбранное» доменов и IP объединяются. Общий DNS Windows обрабатывается отдельно.',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 12),
            ),
            for (final kind in SplitTunnelKind.values) _ruleEditor(kind),
            if (_error != null) _feedback(_error!, AppColors.error),
            if (_notice != null) _feedback(_notice!, AppColors.success),
            if (_dirty && _notice == null)
              _feedback('Есть несохранённые изменения.', AppColors.warning),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                key: const Key('split-save'),
                onPressed: _saving || !_dirty ? null : _save,
                child: Text(_saving ? 'Сохранение…' : 'Сохранить правила'),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _ruleEditor(SplitTunnelKind kind) {
    final rules = _settings!.rules(kind);
    return Padding(
      padding: const EdgeInsets.only(top: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            splitTunnelKindTitle(kind),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          DropdownButton<SplitTunnelMode>(
            key: Key('split-${kind.name}-mode'),
            isExpanded: true,
            value: rules.mode,
            dropdownColor: AppColors.field,
            items: [
              for (final mode in SplitTunnelMode.values)
                DropdownMenuItem(
                  value: mode,
                  child: Text(
                    splitTunnelModeTitle(mode),
                    style: const TextStyle(fontSize: 13),
                  ),
                ),
            ],
            onChanged: _saving || _loadingApps
                ? null
                : (mode) {
                    if (mode != null) _changeMode(kind, mode);
                  },
          ),
          if (rules.mode != SplitTunnelMode.off) ...[
            TextField(
              key: Key('split-${kind.name}-entries'),
              controller: _controllers[kind],
              enabled: !_saving && !_loadingApps,
              minLines: 3,
              maxLines: 6,
              style: const TextStyle(
                color: AppColors.textPrimary,
                fontSize: 12,
              ),
              decoration: InputDecoration(
                hintText: switch (kind) {
                  SplitTunnelKind.apps =>
                    'C:\\Apps\\browser.exe\nC:\\Apps\\messenger.exe',
                  SplitTunnelKind.domains => 'example.com\nпример.рф',
                  SplitTunnelKind.ips => '10.0.0.0/8\n192.0.2.10-192.0.2.20',
                },
                hintStyle: const TextStyle(
                  color: AppColors.textTertiary,
                  fontSize: 12,
                ),
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {
                _dirty = true;
                _error = null;
                _notice = null;
              }),
            ),
            const SizedBox(height: 6),
            Text(
              switch (kind) {
                SplitTunnelKind.apps => 'Каждый полный путь к EXE — с новой строки. После переподключения перезапустите соединения приложений.',
                SplitTunnelKind.domains => 'Домен и его поддомены. Определение по системному DNS; защищённый DNS (DoH) в приложении может скрыть имя.',
                SplitTunnelKind.ips => 'IPv4, подсеть CIDR или диапазон. Разделители — новая строка, пробел, запятая и точка с запятой.',
              },
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 11,
              ),
            ),
            if (kind == SplitTunnelKind.apps && widget.loadApplications != null)
              TextButton.icon(
                onPressed: _saving || _loadingApps ? null : _chooseApplications,
                icon: const Icon(Icons.apps, size: 16),
                label: Text(
                  _loadingApps
                      ? 'Чтение приложений…'
                      : 'Выбрать запущенные приложения',
                ),
              ),
          ],
        ],
      ),
    );
  }

  Widget _feedback(String message, Color color) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: Text(message, style: TextStyle(color: color, fontSize: 12)),
  );
}
