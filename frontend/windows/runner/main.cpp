#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>
#include <shellapi.h>

#include <cwchar>
#include <iterator>
#include <thread>

#include "deep_link.h"
#include "flutter_window.h"
#include "utils.h"

namespace {

// Разрешён один экземпляр FOCSQ: второй запуск выводит на передний план
// уже запущенное окно. Мьютекс держится весь срок жизни процесса.
constexpr wchar_t kSingleInstanceMutex[] = L"Local\\FOCSQ.SingleInstance.v1";
constexpr wchar_t kWindowClassName[] = L"FLUTTER_RUNNER_WIN32_WINDOW";
constexpr wchar_t kActivateEventName[] = L"Local\\FOCSQ.ActivateWindow.v1";

std::wstring CurrentExecutablePath() {
  std::wstring path(32768, L'\0');
  DWORD length = static_cast<DWORD>(path.size());
  if (!::QueryFullProcessImageNameW(::GetCurrentProcess(), 0, path.data(),
                                    &length)) {
    return {};
  }
  path.resize(length);
  return path;
}

BOOL CALLBACK FindExistingWindowCallback(HWND window, LPARAM parameter) {
  auto* found = reinterpret_cast<HWND*>(parameter);
  if (found == nullptr || *found != nullptr) return FALSE;

  wchar_t class_name[256]{};
  if (!::GetClassNameW(window, class_name,
                       static_cast<int>(std::size(class_name))) ||
      std::wcscmp(class_name, kWindowClassName) != 0) {
    return TRUE;
  }

  DWORD process_id = 0;
  ::GetWindowThreadProcessId(window, &process_id);
  if (process_id == 0) return TRUE;

  HANDLE process =
      ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, process_id);
  if (process == nullptr) return TRUE;

  std::wstring path(32768, L'\0');
  DWORD length = static_cast<DWORD>(path.size());
  const bool same_app =
      ::QueryFullProcessImageNameW(process, 0, path.data(), &length) != FALSE &&
      path.substr(0, length) == CurrentExecutablePath();
  ::CloseHandle(process);

  if (same_app) {
    *found = window;
    return FALSE;
  }
  return TRUE;
}

// Handle живёт до выхода процесса и держит мьютекс захваченным;
// перед само-повышением закрывается явно, иначе повышенная копия
// увидит «уже запущено» от собственного не-элевированного прародителя.
HANDLE g_single_instance_mutex = nullptr;

// Именованное событие «покажи окно»: сигналят вторые экземпляры и заглушка,
// слушатель (поток ниже по коду) показывает главное окно приложения.
HANDLE g_activate_event = nullptr;

// true — это первый экземпляр (мьютекс захвачен), false — уже запущено.
bool AcquireSingleInstanceLock() {
  HANDLE mutex = ::CreateMutexW(nullptr, FALSE, kSingleInstanceMutex);
  if (mutex == nullptr) return true;  // не блокируем запуск на отказе API
  if (::GetLastError() == ERROR_ALREADY_EXISTS) {
    ::CloseHandle(mutex);
    return false;
  }
  g_single_instance_mutex = mutex;
  return true;
}

void ReleaseSingleInstanceLock() {
  if (g_single_instance_mutex != nullptr) {
    ::CloseHandle(g_single_instance_mutex);
    g_single_instance_mutex = nullptr;
  }
}

bool IsProcessElevated() {
  HANDLE token = nullptr;
  if (!::OpenProcessToken(::GetCurrentProcess(), TOKEN_QUERY, &token)) {
    return false;
  }
  TOKEN_ELEVATION elevation = {};
  DWORD size = sizeof(elevation);
  // ВНИМАНИЕ: GetTokenInformation возвращает BOOL успеха вызова, а значение
  // токена лежит в elevation.TokenIsElevated. Если брать сам BOOL, функция
  // всегда истинна — с манифестом asInvoker процесс никогда не повышается,
  // UAC не появляется, а TUN падает с «нужны права администратора».
  const bool ok = ::GetTokenInformation(token, TokenElevation, &elevation,
                                        sizeof(elevation), &size) != FALSE;
  ::CloseHandle(token);
  return ok && elevation.TokenIsElevated != 0;
}

// Манифест asInvoker: повышаемся сами, один раз, до создания окна и Flutter.
// Любой второй запуск (ярлык, пин, двойной клик по exe) попадает в ветку
// ActivateExistingInstance выше и UAC-промпт не видит вообще.
void RelaunchElevated(wchar_t *command_line) {
  const std::wstring exe = CurrentExecutablePath();
  ::ShellExecuteW(nullptr, L"runas", exe.c_str(), command_line, nullptr,
                  SW_SHOWNORMAL);
}

void ActivateExistingInstance() {
  // Второй экземпляр не трогает чужое окно напрямую: запущенный экземпляр
  // может быть elevated, и UIPI молча заблокирует ему ShowWindow. Вместо
  // этого выставляем именованное событие — живой экземпляр показывает своё
  // окно сам (фолбэк на EnumWindows — для старых сборок без слушателя).
  HANDLE ev = ::CreateEventW(nullptr, FALSE, FALSE, kActivateEventName);
  if (ev != nullptr) {
    if (::GetLastError() == ERROR_ALREADY_EXISTS) {
      ::SetEvent(ev);
      ::CloseHandle(ev);
      return;
    }
    ::CloseHandle(ev);
  }
  HWND target = nullptr;
  ::EnumWindows(&FindExistingWindowCallback, reinterpret_cast<LPARAM>(&target));
  if (target != nullptr) {
    ::ShowWindow(target, SW_RESTORE);
    ::SetForegroundWindow(target);
  }
}

}  // namespace

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  // Attach to console when present (e.g., 'flutter run') or create a
  // new console when running with a debugger.
  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  // Initialize COM, so that it is available for use in the library and/or
  // plugins.
  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  std::vector<std::string> command_line_arguments =
      GetCommandLineArguments();

  // A browser launch of csqtt://... must reach the live instance without
  // starting a second process or showing UAC. The pipe/WM_COPYDATA bridge
  // works across integrity levels (see deep_link.cpp).
  for (const std::string& argument : command_line_arguments) {
    if (deep_link::IsLink(argument) &&
        deep_link::ForwardToRunningInstance(argument)) {
      ::CoUninitialize();
      return EXIT_SUCCESS;
    }
  }

  if (!AcquireSingleInstanceLock()) {
    ActivateExistingInstance();
    ::CoUninitialize();
    return EXIT_SUCCESS;
  }

  // TUN (wintun) и маршруты требуют админа. Повышаемся сами: отпускаем
  // мьютекс, перезапускаемся через runas и выходим. В debug-сборках
  // (flutter run) не повышаемся — отладчик терял бы процесс.
#if defined(_DEBUG)
  const bool elevated = true;
#else
  const bool elevated = IsProcessElevated();
#endif
  if (!elevated) {
    ReleaseSingleInstanceLock();
    RelaunchElevated(command_line);
    ::CoUninitialize();
    return EXIT_SUCCESS;
  }

  // Событие, по которому второй экземпляр/заглушка просят показать окно.
  g_activate_event = ::CreateEventW(nullptr, FALSE, FALSE, kActivateEventName);

  flutter::DartProject project(L"data");

  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  FlutterWindow window(project);
  Win32Window::Point origin(10, 10);
  Win32Window::Size size(1280, 720);
  if (!window.Create(L"FOCSQ", origin, size)) {
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  // Слушатель сигнала «покажи окно» от вторых экземпляров и заглушки.
  if (g_activate_event != nullptr) {
    const HWND main_hwnd = window.GetHandle();
    const HANDLE event = g_activate_event;
    std::thread([main_hwnd, event]() {
      for (;;) {
        if (::WaitForSingleObject(event, INFINITE) != WAIT_OBJECT_0) break;
        ::ShowWindow(main_hwnd, SW_RESTORE);
        ::SetForegroundWindow(main_hwnd);
      }
    }).detach();
  }

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  return EXIT_SUCCESS;
}
