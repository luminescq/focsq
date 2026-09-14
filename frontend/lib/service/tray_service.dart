// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart' show rootBundle;
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import 'connect.dart';
import 'app_visibility.dart';
import 'logs.dart';

class TrayService with TrayListener {
  TrayService._();
  static final TrayService instance = TrayService._();

  bool _initialized = false;
  bool _enabled = false;
  bool _iconSet = false;

  static const String _menuOpen = 'open';
  static const String _menuToggle = 'toggle';
  static const String _menuExit = 'exit';

  bool? _appliedMenuConnected;
  String? _appliedToolTip;

  bool get isEnabled => _enabled;

  Future<void> setEnabled(bool enabled) async {
    if (_enabled == enabled) return;
    _enabled = enabled;
    if (enabled) {
      if (!_initialized) {
        _initialized = true;
        trayManager.addListener(this);
        ConnectService().addListener(_onConnectChanged);
      }
      _iconSet = false;
      _appliedMenuConnected = null;
      _appliedToolTip = null;
      await _initTrayIcon();
      await _updateMenuAndTooltip();
    } else {
      try {
        await trayManager.destroy();
      } catch (_) {}
      _iconSet = false;
    }
  }

  Future<void> _initTrayIcon() async {
    if (_iconSet) return;
    try {
      final linux = Platform.isLinux;
      final base = linux
          ? (Platform.environment['XDG_DATA_HOME'] ??
              '${Platform.environment['HOME']}/.local/share')
          : Platform.environment['LOCALAPPDATA'];
      final dir = Directory('${base ?? Directory.systemTemp.path}'
          '${Platform.pathSeparator}FOCSQ');
      await dir.create(recursive: true);
      final asset =
          linux ? 'assets/tray/tray_static_32.png' : 'assets/tray/tray_static.ico';
      final fileName =
          linux ? 'tray_static_32.png' : 'tray_static.ico';
      final file = File('${dir.path}${Platform.pathSeparator}$fileName');

      final data = await rootBundle.load(asset);
      await file.writeAsBytes(data.buffer.asUint8List(), flush: true);

      await trayManager.setIcon(file.path);
      _iconSet = true;
    } catch (error) {
      LogService().add('[ТРЕЙ] Ошибка установки иконки: $error');
    }
  }

  void _onConnectChanged() {
    if (!_enabled) return;
    unawaited(_updateMenuAndTooltip());
  }

  Future<void> _updateMenuAndTooltip() async {
    if (!_enabled) return;
    final state = ConnectService().state;
    final menuConnected = state == ConnectState.connected ||
        state == ConnectState.connecting;
    final tooltip = 'FOCSQ — ${_statusText()}';

    if (menuConnected == _appliedMenuConnected && tooltip == _appliedToolTip) {
      return;
    }

    try {
      if (menuConnected != _appliedMenuConnected) {
        await _setMenu(menuConnected);
        _appliedMenuConnected = menuConnected;
      }
      if (tooltip != _appliedToolTip) {
        if (!Platform.isLinux) {
          await trayManager.setToolTip(tooltip);
        }
        _appliedToolTip = tooltip;
      }
    } catch (_) {}
  }

  String _statusText() => switch (ConnectService().state) {
        ConnectState.connected => 'Подключено',
        ConnectState.connecting => 'Подключение...',
        ConnectState.disconnecting => 'Отключение...',
        ConnectState.disconnected => 'Отключено',
      };

  Future<void> _setMenu(bool connected) async {
    await trayManager.setContextMenu(Menu(items: [
      MenuItem(key: _menuOpen, label: 'Открыть FOCSQ'),
      MenuItem.separator(),
      MenuItem(
        key: _menuToggle,
        label: connected ? 'Отключиться' : 'Подключиться',
      ),
      MenuItem.separator(),
      MenuItem(key: _menuExit, label: 'Выход'),
    ]));
  }

  Future<void> showWindow() async {
    await windowManager.show();
    await windowManager.focus();
    AppVisibility.instance.setVisible(true);
  }

  Future<void> hideToTray() async {
    await windowManager.hide();
    AppVisibility.instance.setVisible(false);
    LogService().add('[СТАТУС] Свёрнуто в трей · туннель работает');
  }

  Future<void> exitApp() async {
    final sw = Stopwatch()..start();
    LogService().add('[КОННЕКТ] Выход: старт');
    try {
      await trayManager.destroy();
    } catch (_) {}
    try {
      await windowManager.hide();
    } catch (_) {}
    LogService().add('[КОННЕКТ] Выход: интерфейс скрыт (${sw.elapsedMilliseconds}мс)');
    if (ConnectService().state != ConnectState.disconnected) {
      await ConnectService().stop();
      LogService().add(
        '[КОННЕКТ] Выход: ядро остановлено (${sw.elapsedMilliseconds}мс)',
      );
    }
    await ConnectService().waitForExit();
    LogService().add(
      '[КОННЕКТ] Выход: teardown завершён (${sw.elapsedMilliseconds}мс)',
    );
    await windowManager.destroy();
  }


  @override
  void onTrayIconMouseDown() {
    showWindow();
  }

  @override
  void onTrayIconRightMouseDown() {
    trayManager.popUpContextMenu();
  }

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    switch (menuItem.key) {
      case _menuOpen:
        showWindow();
      case _menuToggle:
        ConnectService().toggle().then((error) {
          if (error != null) LogService().add('[КОННЕКТ] $error');
        });
      case _menuExit:
        exitApp();
    }
  }
}
