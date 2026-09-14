// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// focsq-tun — обёртка Linux-запуска FOCSQ с cap_net_admin.
//
// [FOCSQ] Зачем: /dev/net/tun и маршруты требуют CAP_NET_ADMIN. Но
// setcap на самом приложении ломает его запуск: file capability
// переводит процесс в secure-execution mode (AT_SECURE), и glibc в
// этом режиме игнорирует RUNPATH с $ORIGIN — приложение не находит
// собственные lib/*.so и падает на старте («cannot open shared
// object file: libdesktop_webview_window_plugin.so», живая Manjaro,
// 2026-09-03).
//
// Решение как у systemd (AmbientCapabilities=): capability живёт на
// ЭТОЙ крошечной обёртке. Порядок (capabilities(7)):
//   1. exec обёртки: setcap cap_net_admin+ep даёт permitted+effective;
//      inheritable НЕ наследуется от file caps (P'(inheritable) =
//      P(inheritable) — у родителя он пуст);
//   2. capset(2): капа из permitted добавляется в inheritable —
//      ядро это разрешает: без CAP_SETPCAP новый inheritable обязан
//      быть подмножеством (inheritable ∪ permitted) — наш случай;
//   3. prctl(PR_CAP_AMBIENT, RAISE): ambient-подъём требует капу в
//      permitted И inheritable — теперь оба условия выполнены;
//   4. exec приложения: ambient переживает execve без file caps на
//      наследнике и попадает в его permitted/effective, НЕ отметив
//      его AT_SECURE — $ORIGIN работает, WebView и плагины грузятся,
//      а TUN/маршруты открываются.
//
// Обёртка не парсит аргументы и не читает env: argv передаётся
// приложению как есть. Никакой логики, кроме execve, — поверхность
// атаки минимальна.
//
// Сборка (linux/runner/CMakeLists.txt): обычный C-исполняемый,
// ставится в корень бандла рядом с приложением; focsq.sh делает
// setcap cap_net_admin+ep на НЕЙ и запускает приложение так:
//   exec ./focsq-tun "$BUNDLE_DIR/focsq" "$@"
//
// Любая ошибка на этапах 1-3 (нет setcap, ядро < 4.3 без ambient,
// экзотический контейнер) — не отказ: продолжаем без права,
// приложение стартует, туннель при коннекте скажет «нужен root или
// setcap cap_net_admin». Лаунчер не должен был пускать сюда без
// getcap, так что это запасная деградация, а не норма.

#define _GNU_SOURCE

#include <errno.h>
#include <linux/capability.h>
#include <linux/prctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

// PR_CAP_AMBIENT (ядро ≥ 4.3) есть в свежих linux/prctl.h; для старых
// uapi-заголовков задаём вручную — значения ABI стабильны (prctl(2)).
#ifndef PR_CAP_AMBIENT
#define PR_CAP_AMBIENT 47
#endif
#ifndef PR_CAP_AMBIENT_RAISE
#define PR_CAP_AMBIENT_RAISE 2
#endif

static void warn_no_cap(const char *stage) {
  fprintf(stderr,
          "focsq-tun: cap_net_admin не поднята (%s: %s) — продолжаю без "
          "права, туннель не поднимется; запусти через focsq.sh\n",
          stage, strerror(errno));
}

static void raise_ambient_net_admin(void) {
  struct __user_cap_header_struct header;
  struct __user_cap_data_struct data[2];

  header.version = _LINUX_CAPABILITY_VERSION_3;
  header.pid = 0;
  if (syscall(SYS_capget, &header, data) < 0) {
    warn_no_cap("capget");
    return;
  }
  // CAP_NET_ADMIN = 12 < 32 — младшее слово; старшее не трогаем.
  data[0].inheritable |= 1u << CAP_NET_ADMIN;
  if (syscall(SYS_capset, &header, data) < 0) {
    warn_no_cap("capset inheritable");
    return;
  }
  if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_ADMIN, 0, 0) < 0) {
    warn_no_cap("prctl ambient");
  }
}

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "focsq-tun: focsq-tun <бинарник> [аргументы...]\n");
    return 1;
  }

  raise_ambient_net_admin();

  // exec приложения. argv[0] наследника — сам бинарник (не focsq-tun):
  // приложение ожидает argv[0] = своё имя (важно для /proc и GUI).
  char **app_argv = argv + 1;
  execvp(argv[1], app_argv);

  // exec вернулся только при ошибке.
  fprintf(stderr, "focsq-tun: exec %s: %s\n", argv[1], strerror(errno));
  return 127;
}
