import 'package:flutter_test/flutter_test.dart';

import 'package:frontend/service/autostart.dart';

/// Содержимое XDG-записи автозапуска Linux: формат фиксирован
/// спецификацией Desktop Entry, проверяем вслепую — файл пишется
/// только на Linux.
void main() {
  group('buildDesktopEntry', () {
    test('заголовок и тип записи', () {
      final entry = AutostartService.buildDesktopEntry('/opt/focsq/focsq.sh');
      expect(entry, startsWith('[Desktop Entry]\n'));
      expect(entry, contains('Type=Application\n'));
      expect(entry, contains('Name=FOCSQ\n'));
      expect(entry, contains('Terminal=false\n'));
      expect(entry, contains('X-GNOME-Autostart-enabled=true\n'));
    });

    test('Exec: путь в кавычках и аргумент сворачивания в трей', () {
      final entry = AutostartService.buildDesktopEntry('/opt/focsq/focsq.sh');
      expect(entry, contains('Exec="/opt/focsq/focsq.sh" --minimized\n'));
    });

    test('TryExec: скрытие записи при переносе бандла', () {
      // Автозапуск идёт через лаунчер: если папку перенесли, запись
      // должна молча исчезнуть, а не падать в journalctl.
      final entry = AutostartService.buildDesktopEntry('/opt/focsq/focsq.sh');
      expect(entry, contains('TryExec=/opt/focsq/focsq.sh\n'));
    });

    test('путь с пробелами экранируется кавычками', () {
      final entry =
          AutostartService.buildDesktopEntry('/opt/My Apps/focsq.sh');
      expect(entry, contains('Exec="/opt/My Apps/focsq.sh" --minimized\n'));
    });

    test('путь с переменной шелла не ломает разбор', () {
      // Переменные в Exec разворачивает шелл — путь с $ внутри кавычек
      // всё равно пишется как есть.
      final entry =
          AutostartService.buildDesktopEntry(r'/home/u/$app/focsq.sh');
      expect(entry, contains(r'Exec="/home/u/$app/focsq.sh" --minimized'));
    });
  });
}
