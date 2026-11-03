import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/service/connect.dart';
import 'package:frontend/service/logs.dart';
import 'package:frontend/service/tray_service.dart';

void main() {
  late Completer<void> core;
  late ConnectService service;
  var stopCalls = 0;

  void createService() {
    core = Completer<void>();
    service = ConnectService.forTesting(
      coreRun: core.future,
      stopCore: () async {
        stopCalls++;
      },
    );
  }

  setUp(() {
    LogService().clear();
    stopCalls = 0;
  });

  tearDown(() async {
    if (!core.isCompleted) core.complete();
    await service.waitForExit();
    // This test owns the notifier and releases it after the fake core exits.
    // ignore: invalid_use_of_protected_member
    service.dispose();
    LogService().clear();
  });

  void send(String type, String payload) {
    service.onCoreLine('__CSQTT_EVENT__|$type|$payload');
  }

  void connect() {
    send('CONFIG', '{"config":"TUNCONF:10.66.67.4:1.1.1.1:0"}');
    send('STATS', '{"active":1,"bytes_up":1,"bytes_down":1}');
    expect(service.state, ConnectState.connected);
  }

  test('worker fatal error keeps retrying and can recover', () {
    createService();
    send(
      'ERROR',
      '{"code":"handshake_timeout","message":"No reply","fatal":true}',
    );
    expect(service.state, ConnectState.connecting);
    expect(service.lastError, 'No reply');
    connect();
    expect(stopCalls, 0);
  });

  test('both zero-worker events return a live core to connecting', () {
    createService();
    connect();
    send('STATS', '{"active":0}');
    expect(service.stats.activeWorkers, 0);
    expect(service.state, ConnectState.connecting);
    connect();
    send('ACTIVE_ZERO', '{}');
    expect(service.stats.activeWorkers, 0);
    expect(service.state, ConnectState.connecting);
  });

  test('stop cancels the pending core after an early STOPPED event', () async {
    createService();
    send('STOPPED', '{}');
    expect(service.state, ConnectState.disconnected);
    expect(await service.start(), contains('ещё завершает работу'));
    expect(await service.stop(), isNull);
    expect(stopCalls, 1);
    expect(service.state, ConnectState.disconnecting);
    send('ACTIVE_ZERO', '{}');
    send(
      'ERROR',
      '{"code":"handshake_timeout","message":"Late reply","fatal":true}',
    );
    expect(service.state, ConnectState.disconnecting);
    core.complete();
    await service.waitForExit();
    expect(service.state, ConnectState.disconnected);
  });

  test('core future completion owns final disconnection', () async {
    createService();
    connect();
    core.completeError(StateError('core exited'));
    await service.waitForExit();
    expect(service.state, ConnectState.disconnected);
    expect(service.stats.activeWorkers, 0);
    expect(service.lastError, contains('core exited'));
  });

  testWidgets('watchdog retains ownership until a stalled core exits', (
    tester,
  ) async {
    createService();
    await tester.pump(const Duration(seconds: 60));
    await tester.pump(const Duration(seconds: 7));
    expect(stopCalls, 1);
    expect(service.state, ConnectState.disconnecting);
    expect(LogService().lines.join('\n'), contains('ожидаю завершения ядра'));
    await service.stop();
    expect(stopCalls, 2);
    core.complete();
    await tester.pump();
    await service.waitForExit();
    expect(service.state, ConnectState.disconnected);
    LogService().clear();
  });

  test('tray only claims a working tunnel for connected state', () {
    createService();
    expect(
      TrayService.hiddenStatusFor(ConnectState.connected),
      'туннель работает',
    );
    for (final state in [
      ConnectState.connecting,
      ConnectState.disconnecting,
      ConnectState.disconnected,
    ]) {
      expect(TrayService.hiddenStatusFor(state), isNot(contains('работает')));
    }
  });
}
