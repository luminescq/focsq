// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:io';

import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:window_manager/window_manager.dart';

import 'service/app_visibility.dart';
import 'service/autostart.dart';
import 'service/connect.dart';
import 'service/db.dart';
import 'service/diagnostic_file.dart';
import 'service/logs.dart';
import 'service/tray_service.dart';
import 'theme/app_colors.dart';
import 'screen/main_layout.dart';

Future<void> main(List<String> args) async {
  if (runWebViewTitleBarWidget(args)) return;

  WidgetsFlutterBinding.ensureInitialized();

  await _initDiagnosticLog();

  await DbService.init();
  final settings = await DbService.getSettings();
  LogService.setDeveloperMode(settings.developerMode);
  await AutostartService.init();

  await _initWindow(args.contains('--minimized'));
  AppVisibility.instance.setVisible(!args.contains('--minimized'));

  runApp(const FocsqApp());

  unawaited(TrayService.instance.setEnabled(settings.tray));

  unawaited(ConnectService().prewarm());
}

Future<void> _initDiagnosticLog() async {
  final logs = LogService();
  try {
    final support = await getApplicationSupportDirectory();
    final output = await DiagnosticFileLog.open(
      directory: Directory('${support.path}${Platform.pathSeparator}logs'),
      onError: (_) => logs.add(
        '[ЖУРНАЛ] Ошибка записи подробного лога на диск; проверьте свободное место и доступ к папке logs',
      ),
    );
    logs.configureDiagnosticOutput(
      filePath: output.filePath,
      writer: output.add,
      close: output.close,
    );
    logs.add(
      '[ДИАГНОСТИКА] Начало журнала: diagnostics-v1 | '
      '${Platform.operatingSystem} | pid=$pid | CSQTT-WIRE-3 / CSQTT 2.5.0',
    );
    logs.add('[ЖУРНАЛ] Подробный лог: ${output.filePath}');
  } catch (error) {
    logs.add(
      '[ЖУРНАЛ] Ошибка открытия подробного лога (${error.runtimeType}); '
      'проверьте доступ к каталогу данных приложения',
    );
  }
}

Future<void> _initWindow(bool minimized) async {
  try {
    await windowManager.ensureInitialized();
    const options = WindowOptions(
      title: 'FOCSQ',
      size: Size(1120, 720),
      minimumSize: Size(980, 640),
      center: true,
      titleBarStyle: TitleBarStyle.hidden,
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
    LogService().add('[ОКНО] Не удалось настроить окно: $error');
  }
}

class FocsqApp extends StatelessWidget {
  const FocsqApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FOCSQ',
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
            (states) => states.contains(WidgetState.hovered) ? 8.0 : 5.0,
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
      home: const MainLayout(),
    );
  }
}
