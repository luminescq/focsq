// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io' show Platform;

import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import '../l10n/app_locale.dart';
import 'service/app_visibility.dart';
import 'service/autostart.dart';
import 'service/connect.dart';
import 'service/db.dart';
import 'service/deep_link.dart';
import 'service/logs.dart';
import 'service/subscription_manager.dart';
import 'service/tray_service.dart';
import 'theme/app_colors.dart';
import 'screen/main_layout.dart';

Future<void> main(List<String> args) async {
  if (runWebViewTitleBarWidget(args)) return;

  WidgetsFlutterBinding.ensureInitialized();

  await LocaleService.init();
  await DbService.init();
  await SubscriptionManager.instance.init();
  final settings = await DbService.getSettings();
  LogService.setDeveloperMode(settings.developerMode);
  await AutostartService.init();

  await _initWindow(args.contains('--minimized'));
  AppVisibility.instance.setVisible(!args.contains('--minimized'));

  final navigatorKey = GlobalKey<NavigatorState>();
  runApp(FocsqApp(navigatorKey: navigatorKey));

  unawaited(
    DeepLinkService.instance.start(navigatorKey, initialArguments: args),
  );

  unawaited(TrayService.instance.setEnabled(settings.tray));

  unawaited(ConnectService().prewarm());
}

Future<void> _initWindow(bool minimized) async {
  try {
    await windowManager.ensureInitialized();
    final options = WindowOptions(
      title: 'FOCSQ',
      size: Size(1120, 720),
      minimumSize: Size(980, 640),
      center: true,
      // Windows: свою рамку режет runner (WM_NCCALCSIZE), native-заголовок
      // должен остаться. Linux/GTK: скрыть декорации — шапку рисует Flutter.
      titleBarStyle: Platform.isWindows
          ? TitleBarStyle.normal
          : TitleBarStyle.hidden,
    );
    await windowManager.waitUntilReadyToShow(options, () async {
      if (minimized) {
        await windowManager.hide();
      } else {
        await windowManager.show();
        await windowManager.focus();
      }
      await windowManager.setPreventClose(true);
    });
  } catch (error) {
    LogService().add(S.windowSetupFailed(error));
  }
}

class FocsqApp extends StatelessWidget {
  const FocsqApp({super.key, required this.navigatorKey});

  final GlobalKey<NavigatorState> navigatorKey;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: LocaleService.instance,
      builder: (context, _) {
        // TickerMode гасит AnimationController.repeat() (спиннер/пульс
        // кнопки подключения) в свёрнутом окне и в трее: без него тикер
        // держит frame pipeline живым на 60 fps.
        return ListenableBuilder(
          listenable: AppVisibility.instance.visible,
          builder: (context, _) => TickerMode(
            enabled: AppVisibility.instance.visible.value,
            child: MaterialApp(
              title: 'FOCSQ',
              navigatorKey: navigatorKey,
              debugShowCheckedModeBanner: false,
              theme: ThemeData(
                useMaterial3: true,
                brightness: Brightness.dark,
                fontFamily: 'Nunito',
                scaffoldBackgroundColor: AppColors.meshBase,
                colorScheme: ColorScheme.fromSeed(
                  seedColor: Colors.grey,
                  brightness: Brightness.dark,
                  surface: AppColors.card,
                ),
                tooltipTheme: TooltipThemeData(
                  decoration: BoxDecoration(
                    color: AppColors.border,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  textStyle: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 12,
                  ),
                ),
                scrollbarTheme: ScrollbarThemeData(
                  thickness: WidgetStateProperty.resolveWith(
                    (states) =>
                        states.contains(WidgetState.hovered) ? 8.0 : 5.0,
                  ),
                  radius: const Radius.circular(8),
                  crossAxisMargin: 2,
                  mainAxisMargin: 4,
                  thumbColor: WidgetStateProperty.resolveWith(
                    (states) => states.contains(WidgetState.hovered)
                        ? Colors.white.withValues(alpha: 0.35)
                        : Colors.white.withValues(alpha: 0.16),
                  ),
                  trackColor: WidgetStateProperty.all(Colors.transparent),
                  trackBorderColor: WidgetStateProperty.all(Colors.transparent),
                ),
              ),
              home: MainLayout(),
            ),
          ),
        );
      },
    );
  }
}
