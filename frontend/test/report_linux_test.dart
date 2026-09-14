import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/report.dart';

/// Парсеры Linux-диагностики отчёта: дистрибутив, эффективный uid,
/// бит CAP_NET_ADMIN. Файлы /etc/os-release и /proc читаются только на
/// Linux, поэтому парсеры принимают содержимое строкой — проверяем
/// вслепую написанный Linux-код прямо с Windows-машины.
void main() {
  group('parsePrettyName', () {
    test('Ubuntu — кавычки снимаются', () {
      const osRelease = 'NAME="Ubuntu"\n'
          'VERSION="24.04.1 LTS (Noble Numbat)"\n'
          'ID=ubuntu\n'
          'PRETTY_NAME="Ubuntu 24.04.1 LTS"\n';
      expect(ReportService.parsePrettyName(osRelease), 'Ubuntu 24.04.1 LTS');
    });

    test('дубликат PRETTY_NAME — побеждает последний', () {
      const osRelease = 'PRETTY_NAME="Fedora"\nPRETTY_NAME="Fedora Linux 41"\n';
      expect(ReportService.parsePrettyName(osRelease), 'Fedora Linux 41');
    });

    test('без PRETTY_NAME — пустая строка', () {
      expect(ReportService.parsePrettyName('NAME=Linux\nID=linux\n'), '');
    });

    test('значение без кавычек читается как есть', () {
      expect(ReportService.parsePrettyName('PRETTY_NAME=Gentoo\n'), 'Gentoo');
    });

    test('CRLF реального файла не попадает в значение', () {
      expect(ReportService.parsePrettyName('PRETTY_NAME="Arch Linux"\r\n'),
          'Arch Linux');
    });
  });

  group('parseEffectiveUid', () {
    test('второе поле — эффективный uid', () {
      const status = 'Name:\tfocsq\n'
          'Uid:\t1000\t1000\t1000\t1000\n'
          'Gid:\t1000\t1000\t1000\t1000\n';
      expect(ReportService.parseEffectiveUid(status), 1000);
    });

    test('root распознаётся', () {
      expect(ReportService.parseEffectiveUid('Uid:\t0\t0\t0\t0\n'), 0);
    });

    test('real uid != effective uid — берётся эффективный', () {
      expect(ReportService.parseEffectiveUid('Uid:\t1000\t0\t1000\t1000\n'), 0);
    });

    test('без строки Uid — null', () {
      expect(ReportService.parseEffectiveUid('Name:\tfocsq\n'), isNull);
    });
  });

  group('parseHasCapNetAdmin', () {
    test('бит 12 (0x1000) установлен', () {
      const status = 'CapInh:\t0000000000000000\n'
          'CapPrm:\t0000000000001000\n'
          'CapEff:\t0000000000001000\n';
      expect(ReportService.parseHasCapNetAdmin(status), isTrue);
    });

    test('бит 12 не установлен', () {
      expect(
          ReportService.parseHasCapNetAdmin('CapEff:\t0000000000000000\n'),
          isFalse);
    });

    test('маска прав обычного пользователя без setcap', () {
      // Docker по умолчанию: CAP_NET_ADMIN как раз вырезан.
      const status = 'Uid:\t0\t0\t0\t0\nCapEff:\t00000000a80425fb\n';
      expect(ReportService.parseHasCapNetAdmin(status), isFalse);
    });

    test('полная root-маска', () {
      expect(
          ReportService.parseHasCapNetAdmin('CapEff:\t000001ffffffffff\n'),
          isTrue);
    });

    test('без CapEff и мусор вместо hex не роняют разбор', () {
      expect(ReportService.parseHasCapNetAdmin('Name:\tfocsq\n'), isFalse);
      expect(
          ReportService.parseHasCapNetAdmin('CapEff:\tZZZZ\n'), isFalse);
    });
  });

  test('пустой ввод не роняет парсеры', () {
    expect(ReportService.parsePrettyName(''), '');
    expect(ReportService.parseEffectiveUid(''), isNull);
    expect(ReportService.parseHasCapNetAdmin(''), isFalse);
  });
}
