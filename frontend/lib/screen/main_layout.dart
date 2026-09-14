// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

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
    windowManager.addListener(this);
    windowManager.setPreventClose(true);
    unawaited(_checkForUpdates());
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
    LogService().add(
      '[КОННЕКТ] Закрытие окна: destroy (${sw.elapsedMilliseconds}мс)',
    );
    await windowManager.destroy();
  }

  static const List<Widget> _screens = [
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
                      onIndexChanged: (index) {
                        if (index == _selectedIndex) return;
                        setState(() => _selectedIndex = index);
                      },
                    ),
                    Expanded(
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 500),
                        switchInCurve: Curves.easeOutQuad,
                        switchOutCurve: Curves.easeInQuad,
                        transitionBuilder: (child, animation) {
                          return AnimatedBuilder(
                            animation: animation,
                            child: child,
                            builder: (context, child) {
                              final blur = (1 - animation.value) * 8;
                              return ImageFiltered(
                                imageFilter: ImageFilter.blur(
                                  sigmaX: blur,
                                  sigmaY: blur,
                                ),
                                child: FadeTransition(
                                  opacity: animation,
                                  child: child,
                                ),
                              );
                            },
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
