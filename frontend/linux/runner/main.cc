#include <errno.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/prctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "my_application.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

// [FOCSQ] WebKitGTK (окно входа ВК/капчи): на системах, где EGL
// поднимается с ошибками (пустой стек GPU на Live-ISO — в терминал
// льётся «Gdk-WARNING eglMakeCurrent failed», страница подтормаживает),
// отключаем DMA-BUF-рендерер — штатный запасной путь WebKit (то же
// самое рекомендуют для NVIDIA/Wayland). overwrite=0: явная переменная
// пользователя не затирается.
//
// НЕ добавлять сюда WEBKIT_DISABLE_COMPOSITING_MODE: нонкомпозитный
// путь давно выпилен из WebKitGTK — переменная не «отключает
// ускорение», а валит веб-процесс: белое окно и страница ошибки
// «Operation was cancelled» (живая Manjaro, webkit2gtk 2.52).
//
// Основной Flutter-интерфейс здесь не участвует: движок Flutter
// EGL использует напрямую и рендерится независимо от WebKit.
static void configure_webkit_rendering() {
  setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 0);
}

// [FOCSQ] Сброс ambient-capability перед стартом GTK.
// Обёртка focsq-tun поднимает cap_net_admin в ambient-набор, чтобы
// право дожило до exec приложения (см. focsq-tun.c). Но ambient
// переживает ЛЮБОЙ exec дальше: его наследует каждый ребёнок
// приложения. Bubblewrap (песочница glycin — загрузчика картинок
// gdk-pixbuf) видит у себя capabilities и отказывается работать:
// «Unexpected capabilities but not setuid» — и тогда GTK не может
// декодировать ни одного изображения: иконки из бандла не грузятся
// (gtk_window_set_icon_from_file = FALSE), а фолбэк image-missing.svg
// из темы роняет GTK assertion'ом при создании окна входа ВК — abort
// всего приложения (живая Manjaro 2026-09-04, glycin 2.1.5).
//
// Приложению для TUN/маршрутов достаточно собственного
// permitted/effective (они после exec уже стоят и от ambient не
// зависят) — ambient нужен был только чтобы донести капу ДО exec'а.
// Сбрасываем его первым делом (вместе с inheritable — без ambient он
// всё равно ничего детям не даёт): дети (bwrap, glycin, WebKit)
// рождаются без capabilities, песочницы работают, право туннеля
// у самого процесса сохранено.
//
// Если приложение запущено без обёртки (dev-сборка, обычный запуск)
// — ambient пуст и prctl просто ничего не делает.
static void drop_ambient_capabilities() {
  prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0);

  struct __user_cap_header_struct header;
  struct __user_cap_data_struct data[2];
  header.version = _LINUX_CAPABILITY_VERSION_3;
  header.pid = 0;
  if (syscall(SYS_capget, &header, data) == 0 &&
      (data[0].inheritable != 0 || data[1].inheritable != 0)) {
    data[0].inheritable = 0;
    data[1].inheritable = 0;
    syscall(SYS_capset, &header, data);
  }
}

// [FOCSQ] Есть ли у ЭТОГО процесса cap_net_admin в effective-наборе:
// читается до любых GTK-объектов в main().
static bool have_cap_eff_net_admin() {
  struct __user_cap_header_struct header;
  struct __user_cap_data_struct data[2];
  header.version = _LINUX_CAPABILITY_VERSION_3;
  header.pid = 0;
  if (syscall(SYS_capget, &header, data) < 0) {
    return false;
  }
  return (data[CAP_NET_ADMIN / 32].effective &
          (1u << (CAP_NET_ADMIN % 32))) != 0;
}

int main(int argc, char** argv) {
  drop_ambient_capabilities();

  // [FOCSQ] Самобутстрап права TUN: бинарник — самодостаточная точка
  // входа (меню, автозапуск, двойной клик), focsq.sh больше не обязателен
  // для запуска. Если процесс без cap_net_admin, а рядом лежит обёртка
  // focsq-tun с выданным правом — перезапускаем себя через неё: обёртка
  // поднимет ambient и exec'нет нас обратно уже с капой. Маркер
  // FOCSQ_TUN_REEXEC не даёт зациклиться (обёртка без setcap честно
  // запустит нас без права — ядро тогда скажет «нужен root или setcap»,
  // а connect-флоу предложит выдать право через pkexec).
  if (getenv("FOCSQ_TUN_REEXEC") == nullptr &&
      !have_cap_eff_net_admin()) {
    char exe_path[PATH_MAX];
    const ssize_t length =
        readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (length > 0) {
      exe_path[length] = '\0';
      char* bundle = g_path_get_dirname(exe_path);
      char* wrapper = g_build_filename(bundle, "focsq-tun", nullptr);
      if (access(wrapper, X_OK) == 0) {
        setenv("FOCSQ_TUN_REEXEC", "1", 1);
        char** child_argv = static_cast<char**>(
            malloc(sizeof(char*) * (static_cast<size_t>(argc) + 2)));
        if (child_argv != nullptr) {
          child_argv[0] = wrapper;
          child_argv[1] = exe_path;
          for (int i = 1; i < argc; i++) {
            child_argv[i + 1] = argv[i];
          }
          child_argv[argc + 1] = nullptr;
          execv(wrapper, child_argv);
          // exec вернулся только при ошибке — запускаемся без права.
          perror("focsq: exec focsq-tun");
        }
      }
      g_free(wrapper);
      g_free(bundle);
    }
  }

  configure_webkit_rendering();
  g_autoptr(MyApplication) app = my_application_new();
  return g_application_run(G_APPLICATION(app), argc, argv);
}
