#include "my_application.h"

#include <flutter_linux/flutter_linux.h>
#include <limits.h>
#include <string.h>
#include <unistd.h>

#include "flutter/generated_plugin_registrant.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

struct _MyApplication {
  GtkApplication parent_instance;
  char** dart_entrypoint_arguments;
};

G_DEFINE_TYPE(MyApplication, my_application, GTK_TYPE_APPLICATION)

// [FOCSQ] Иконка окна — из PNG бандла, минуя икон-тему.
// Живая Manjaro/KDE (тема breeze): окно БЕЗ иконки при realize
// заставляет GTK грузить фолбэк image-missing.svg → песочный
// SVG-загрузчик (glycin + bwrap) в системе сломан и умирает →
// assertion в gtkiconhelper — abort всего приложения при старте.
// gtk_window_set_icon_from_file грузит файл напрямую, минуя тему
// (и не deprecated, в отличие от set_default_icon*).
static void set_bundle_window_icon(GtkWindow* window) {
  char exe_path[PATH_MAX];
  const ssize_t length =
      readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
  if (length <= 0) {
    return;
  }
  exe_path[length] = '\0';
  gchar* bundle = g_path_get_dirname(exe_path);

  const gchar* sizes[] = {"256x256", "128x128", "64x64",
                          "48x48", "32x32", "16x16"};
  for (gulong index = 0; index < G_N_ELEMENTS(sizes); index++) {
    gchar* file =
        g_build_filename(bundle, "data", "icons", "hicolor", sizes[index],
                         "apps", "focsq.png", nullptr);
    const gboolean loaded =
        gtk_window_set_icon_from_file(window, file, nullptr);
    g_free(file);
    if (loaded) {
      g_free(bundle);
      return;
    }
  }
  g_free(bundle);
}

// Called when first Flutter frame received.
static void first_frame_cb(MyApplication* self, FlView* view) {
  gtk_widget_show(gtk_widget_get_toplevel(GTK_WIDGET(view)));
}

// Implements GApplication::activate.
static void my_application_activate(GApplication* application) {
  MyApplication* self = MY_APPLICATION(application);
  GtkWindow* window =
      GTK_WINDOW(gtk_application_window_new(GTK_APPLICATION(application)));

  // [FOCSQ] Иконка до realize окна — иначе GTK на безликом окне
  // грузит image-missing из темы (см. set_bundle_window_icon).
  set_bundle_window_icon(window);

  // [FOCSQ] Header bar ставится ВСЕГДА — окно у нас рисует свою
  // шапку (CustomTitleBar), и TitleBarStyle.hidden от window_manager
  // эту панель скрывает. Но сам titlebar-виджет должен стоять: без
  // него GTK на Wayland просит у композитора серверную рамку — KDE
  // рисовал собственный заголовок ПОВЕРХ нашей. Кнопка закрытия
  // выключена: её иконка (window-close-symbolic, SVG из темы) на
  // системах со сломанным SVG-загрузчиком (glycin/bwrap, живая
  // Manjaro) роняла GTK, а закрывает всё равно наша шапка.
  GtkHeaderBar* header_bar = GTK_HEADER_BAR(gtk_header_bar_new());
  gtk_widget_show(GTK_WIDGET(header_bar));
  gtk_header_bar_set_title(header_bar, "FOCSQ");
  gtk_header_bar_set_show_close_button(header_bar, FALSE);
  gtk_window_set_titlebar(window, GTK_WIDGET(header_bar));

  gtk_window_set_default_size(window, 1280, 720);

  g_autoptr(FlDartProject) project = fl_dart_project_new();
  fl_dart_project_set_dart_entrypoint_arguments(
      project, self->dart_entrypoint_arguments);

  FlView* view = fl_view_new(project);
  GdkRGBA background_color;
  // Background defaults to black, override it here if necessary, e.g. #00000000
  // for transparent.
  gdk_rgba_parse(&background_color, "#000000");
  fl_view_set_background_color(view, &background_color);
  gtk_widget_show(GTK_WIDGET(view));
  gtk_container_add(GTK_CONTAINER(window), GTK_WIDGET(view));

  // Show the window when Flutter renders.
  // Requires the view to be realized so we can start rendering.
  g_signal_connect_swapped(view, "first-frame", G_CALLBACK(first_frame_cb),
                           self);
  gtk_widget_realize(GTK_WIDGET(view));

  fl_register_plugins(FL_PLUGIN_REGISTRY(view));

  gtk_widget_grab_focus(GTK_WIDGET(view));
}

// Implements GApplication::local_command_line.
static gboolean my_application_local_command_line(GApplication* application,
                                                  gchar*** arguments,
                                                  int* exit_status) {
  MyApplication* self = MY_APPLICATION(application);
  // Strip out the first argument as it is the binary name.
  self->dart_entrypoint_arguments = g_strdupv(*arguments + 1);

  g_autoptr(GError) error = nullptr;
  if (!g_application_register(application, nullptr, &error)) {
    g_warning("Failed to register: %s", error->message);
    *exit_status = 1;
    return TRUE;
  }

  g_application_activate(application);
  *exit_status = 0;

  return TRUE;
}

// Implements GApplication::startup.
static void my_application_startup(GApplication* application) {
  // [FOCSQ] Guard рисования по разрушенному окну. Окно WebView (вход ВК,
  // капча) разрушается посреди paint-цикла — teardown второго движка на
  // Flutter 3.44 кривой (fl_engine_remove_view отказывает: «The implicit
  // view cannot be removed»), и GDK доводит до конца DAMAGE-событие
  // уничтоженного GdkWindow: SIGSEGV в gdk_window_end_draw_frame
  // (керндампы 2026-09-05, eglMakeCurrent failed перед падением).
  // Рисовать по destroyed-окну нечем — событие просто отбрасываем.
  gdk_event_handler_set(
      +[](GdkEvent* event, gpointer data) {
        if ((event->type == GDK_DAMAGE || event->type == GDK_EXPOSE) &&
            event->any.window != nullptr &&
            gdk_window_is_destroyed(event->any.window)) {
          return;
        }
        gtk_main_do_event(event);
      },
      nullptr, nullptr);

  // MyApplication* self = MY_APPLICATION(object);

  // Perform any actions required during application startup.

  G_APPLICATION_CLASS(my_application_parent_class)->startup(application);
}

// Implements GApplication::shutdown.
static void my_application_shutdown(GApplication* application) {
  // MyApplication* self = MY_APPLICATION(object);

  // Perform any actions required at application shutdown.

  G_APPLICATION_CLASS(my_application_parent_class)->shutdown(application);
}

// Implements GObject::dispose.
static void my_application_dispose(GObject* object) {
  MyApplication* self = MY_APPLICATION(object);
  g_clear_pointer(&self->dart_entrypoint_arguments, g_strfreev);
  G_OBJECT_CLASS(my_application_parent_class)->dispose(object);
}

static void my_application_class_init(MyApplicationClass* klass) {
  G_APPLICATION_CLASS(klass)->activate = my_application_activate;
  G_APPLICATION_CLASS(klass)->local_command_line =
      my_application_local_command_line;
  G_APPLICATION_CLASS(klass)->startup = my_application_startup;
  G_APPLICATION_CLASS(klass)->shutdown = my_application_shutdown;
  G_OBJECT_CLASS(klass)->dispose = my_application_dispose;
}

static void my_application_init(MyApplication* self) {}

MyApplication* my_application_new() {
  // Set the program name to the application ID, which helps various systems
  // like GTK and desktop environments map this running application to its
  // corresponding .desktop file. This ensures better integration by allowing
  // the application to be recognized beyond its binary name.
  g_set_prgname(APPLICATION_ID);

  return MY_APPLICATION(g_object_new(my_application_get_type(),
                                     "application-id", APPLICATION_ID, "flags",
                                     G_APPLICATION_NON_UNIQUE, nullptr));
}
