// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:window_manager/window_manager.dart';

import '../l10n/app_locale.dart';
import '../service/alert.dart';
import '../service/app_visibility.dart';
import '../service/browser.dart';
import '../service/connect.dart';
import '../service/db.dart';
import '../service/logs.dart';
import '../service/tray_service.dart';
import '../service/update.dart';
import '../widgets/custom_title_bar.dart';
import '../modals/update_modal.dart';
import '../widgets/mesh_background.dart';
import '../widgets/side_bar.dart';
import 'connect_screen.dart';
import 'info_screen.dart';
import 'logs_screen.dart';
import 'settings_screen.dart';

class MainLayout extends StatefulWidget {
  const MainLayout({super.key});

  @override
  State<MainLayout> createState() => _MainLayoutState();
}

class _MainLayoutState extends State<MainLayout> with WindowListener {
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_handleKey);
    windowManager.addListener(this);
    windowManager.setPreventClose(true);
    unawaited(_checkForUpdates());
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        unawaited(AlertService.ensureBanDisclaimerAccepted(context));
      }
    });
  }

  Future<void> _checkForUpdates() async {
    await Future<void>.delayed(const Duration(seconds: 2));
    final result = await UpdateService.autoCheck();
    if (!mounted || result is! UpdateAvailable) return;
    if (UpdateService.isUpdateDismissed) return;
    if (UpdateService.updateModalShown) return;
    UpdateService.updateModalShown = true;
    final openReleases = await UpdateModal.show(context, result);
    if (!mounted) return;
    if (!openReleases) {
      await DbService.updateSettings(
        (s) => s..skipUpdateVersion = result.version,
      );
    } else {
      await UrlService.openInBrowser(UpdateService.releasesPage);
    }
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_handleKey);
    windowManager.removeListener(this);
    if (ConnectService().state != ConnectState.disconnected) {
      ConnectService().stop();
    }
    super.dispose();
  }

  @override
  Future<void> onWindowClose() async {
    final sw = Stopwatch()..start();
    var trayEnabled = false;
    try {
      trayEnabled = (await DbService.getSettings()).tray;
    } catch (_) {}
    if (trayEnabled && TrayService.instance.isEnabled) {
      await TrayService.instance.hideToTray();
      return;
    }
    await windowManager.hide();
    if (ConnectService().state != ConnectState.disconnected) {
      await ConnectService().stop();
    }
    await ConnectService().waitForExit();
    LogService().add(S.windowCloseLog(sw.elapsedMilliseconds));
    await windowManager.destroy();
  }

  // Не const: при смене языка экземпляры пересоздаются и экраны перерисовываются.
  List<Widget> get _screens => [
    ConnectScreen(),
    LogsScreen(),
    InfoScreen(),
    SettingsScreen(),
  ];

  @override
  void onWindowMinimize() {
    AppVisibility.instance.setVisible(false);
  }

  @override
  void onWindowRestore() {
    AppVisibility.instance.setVisible(true);
  }

  @override
  void onWindowFocus() {
    // Окно могли показать нативно (второй экземпляр/заглушка шлют
    // именованное событие) — при активации видимость восстанавливаем,
    // иначе анимация фона остаётся на паузе, как в трее.
    AppVisibility.instance.setVisible(true);
    AppVisibility.instance.setFocused(true);
  }

  @override
  void onWindowBlur() {
    // Окно видно, но пользователь работает в другом приложении:
    // фон и повторяющиеся анимации не тратят GPU.
    AppVisibility.instance.setFocused(false);
  }

  void _selectScreen(int index) {
    if (index == _selectedIndex || index < 0 || index >= _screens.length) {
      return;
    }
    setState(() => _selectedIndex = index);
  }

  void _cycleScreen(int delta) {
    final count = _screens.length;
    _selectScreen((_selectedIndex + delta + count) % count);
  }

  // Ctrl+1..4 — прямой выбор раздела, Ctrl+Tab / Ctrl+Shift+Tab — по кругу.
  // Слушаем HardwareKeyboard, а не Shortcuts: так шорткаты работают даже если
  // фокус сейчас в текстовом поле или его только что сняли.
  static final Map<LogicalKeyboardKey, int> _screenKeys = {
    LogicalKeyboardKey.digit1: 0,
    LogicalKeyboardKey.numpad1: 0,
    LogicalKeyboardKey.digit2: 1,
    LogicalKeyboardKey.numpad2: 1,
    LogicalKeyboardKey.digit3: 2,
    LogicalKeyboardKey.numpad3: 2,
    LogicalKeyboardKey.digit4: 3,
    LogicalKeyboardKey.numpad4: 3,
  };

  bool _handleKey(KeyEvent event) {
    if (!mounted || event is KeyUpEvent) return false;

    final keyboard = HardwareKeyboard.instance;
    if (!keyboard.isControlPressed) return false;

    // Поверх диалога/шита шорткаты не должны менять экран под ним.
    if (Navigator.of(context).canPop()) return false;

    final screen = _screenKeys[event.logicalKey];
    if (screen != null) {
      _selectScreen(screen);
      return true;
    }

    if (event.logicalKey == LogicalKeyboardKey.tab) {
      _cycleScreen(keyboard.isShiftPressed ? -1 : 1);
      return true;
    }

    return false;
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        const MeshBackground(),
        Scaffold(
          backgroundColor: Colors.transparent,
          body: Column(
            children: [
              const CustomTitleBar(),
              Expanded(
                child: Row(
                  children: [
                    SideBar(
                      selectedIndex: _selectedIndex,
                      onIndexChanged: _selectScreen,
                    ),
                    Expanded(
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 500),
                        switchInCurve: Curves.easeOutQuad,
                        switchOutCurve: Curves.easeInQuad,
                        transitionBuilder: (child, animation) {
                          return FadeTransition(
                            opacity: animation,
                            child: child,
                          );
                        },
                        child: KeyedSubtree(
                          key: ValueKey<int>(_selectedIndex),
                          child: _screens[_selectedIndex],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
