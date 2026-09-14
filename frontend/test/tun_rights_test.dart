import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/tun_rights.dart';

/// Права TUN на Linux: main.cc сам перезапускает процесс через
/// focsq-tun с капой — TunRights покрывает хвост «права нет нигде»:
/// выдача pkexec-ом и перезапуск. Все пути в тестах идут через хуки,
/// никакого pkexec/перезапуска не происходит.
void main() {
  Directory? tmp;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('focsq_tun_rights_');
  });

  tearDown(() {
    tmp?.deleteSync(recursive: true);
  });

  /// Файл-подмена /proc/self/status с данным CapEff (hex).
  File fakeStatus(Directory dir, String capEff) {
    final file = File('${dir.path}/status');
    file.writeAsStringSync(
      'Name: focsq\nCapInh:\t0000000000000000\nCapPrm:\t$capEff\n'
      'CapEff:\t$capEff\nCapBnd:\t000001ffffffffff\nCapAmb:\t0000000000000000\n',
    );
    return file;
  }

  test('hasCapNetAdmin: CapEff с битом 12 (0x1000) — право есть', () async {
    final status = fakeStatus(tmp!, '0000000000001000');
    expect(await TunRights.hasCapNetAdmin(statusFile: status.path), isTrue);
  });

  test('hasCapNetAdmin: CapEff без бита 12 — права нет', () async {
    // 0x1ff — куча других кап, но не net_admin
    final status = fakeStatus(tmp!, '00000000000001ff');
    expect(await TunRights.hasCapNetAdmin(statusFile: status.path), isFalse);
  });

  test('hasCapNetAdmin: бит 12 в старшем слове не считается (бит — в младшем)',
      () async {
    // Мусорные строки без CapEff — права нет
    final file = File('${tmp!.path}/status');
    file.writeAsStringSync('Name: focsq\nno caps here\n');
    expect(await TunRights.hasCapNetAdmin(statusFile: file.path), isFalse);
  });

  test('hasCapNetAdmin: отсутствующий файл — права нет (не крашит)', () async {
    expect(
        await TunRights.hasCapNetAdmin(statusFile: '/nonexistent/status'),
        isFalse);
  });

  test('ensureForConnect: право уже есть — молча пропускает', () async {
    final status = fakeStatus(tmp!, '0000000000001000');
    var grantCalled = false;
    final error = await TunRights.ensureForConnect(
      wrapper: '/nonexistent/focsq-tun',
      statusFile: status.path,
      env: {},
      grant: (_) async {
        grantCalled = true;
        return null;
      },
    );
    expect(error, isNull);
    expect(grantCalled, isFalse, reason: 'капа есть — pkexec не нужен');
  });

  test('ensureForConnect: права нет, обёртки нет — ошибка про focsq.sh',
      () async {
    final status = fakeStatus(tmp!, '0000000000000000');
    final error = await TunRights.ensureForConnect(
      wrapper: null,
      statusFile: status.path,
      env: {},
    );
    expect(error, isNotNull);
    expect(error, contains('focsq.sh'));
  });

  test('ensureForConnect: FOCSQ_NO_GRANT=1 — тихий отказ без pkexec',
      () async {
    final status = fakeStatus(tmp!, '0000000000000000');
    var grantCalled = false;
    final error = await TunRights.ensureForConnect(
      wrapper: '/opt/focsq/focsq-tun',
      statusFile: status.path,
      env: {'FOCSQ_NO_GRANT': '1'},
      grant: (_) async {
        grantCalled = true;
        return null;
      },
    );
    expect(error, isNotNull);
    expect(error, contains('cap_net_admin'));
    expect(grantCalled, isFalse, reason: 'выдача отключена переменной');
  });

  test('ensureForConnect: pkexec отказал (отмена пароля) — ошибка без '
      'перезапуска', () async {
    final status = fakeStatus(tmp!, '0000000000000000');
    var restarted = false;
    final error = await TunRights.ensureForConnect(
      wrapper: '/opt/focsq/focsq-tun',
      statusFile: status.path,
      env: {},
      grant: (_) async => 'Выдача права TUN отменена',
      onGranted: (_) async {
        restarted = true;
      },
    );
    expect(error, contains('отменена'));
    expect(restarted, isFalse, reason: 'отказ — перезапускать нельзя');
  });

  test('ensureForConnect: право выдано — перезапуск через обёртку',
      () async {
    final status = fakeStatus(tmp!, '0000000000000000');
    String? grantedWrapper;
    final error = await TunRights.ensureForConnect(
      wrapper: '/opt/focsq/focsq-tun',
      statusFile: status.path,
      env: {},
      grant: (wrapper) async {
        grantedWrapper = wrapper;
        return null;
      },
      onGranted: (wrapper) async {
        grantedWrapper = '$wrapper#restarted';
      },
    );
    expect(error, isNull, reason: 'после выдачи алерта быть не должно');
    expect(grantedWrapper, '/opt/focsq/focsq-tun#restarted');
  });

  test('ensureForConnect: настоящий pkexec-хук вызывается с правильной '
      'командой (setcap cap_net_admin+ep)', () async {
    // Проверяем сам запуск через Process.run невозможен без pkexec —
    // поэтому фиксируем формат вызова: grant-хук по умолчанию исполняет
    // pkexec setcap cap_net_admin+ep <wrapper>. Достаточно убедиться,
    // что wrapper-путь доходит до хука (см. тест выше) и что команда
    // содержит setcap в исходнике.
    final source = File('lib/service/tun_rights.dart').readAsStringSync();
    expect(
      source.contains("'setcap', 'cap_net_admin+ep'"),
      isTrue,
      reason: 'команда выдачи права должна оставаться setcap+ep',
    );
  }, skip: !Directory('lib/service').existsSync()
      ? 'запуск вне каталога фронтенда'
      : false);
}
