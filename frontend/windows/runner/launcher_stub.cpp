// FOCSQ single-instance launcher stub.
//
// Ярлыки указывают на этот исполняемый файл (asInvoker — без прав
// администратора), а не на focsq.exe с requireAdministrator. Это убирает
// UAC-промпт при попытке открыть уже запущенное приложение:
//   - окно найдено -> вывести на передний план и выйти (UAC нет);
//   - окно не найдено -> запустить настоящий focsq.exe через ShellExecute
//     с verb runas (UAC один раз — на реальный запуск).
// Защита от двух живых копий остаётся в focsq.exe (mutex в main.cpp).
//
// csqtt:// зарегистрирован на focsq.exe: второй запуск форвардит ссылку
// живому экземпляру до UAC (см. main.cpp / deep_link.cpp). Заглушка
// остаётся только для ярлыков.

#include <windows.h>
#include <shellapi.h>

#include <cwchar>
#include <iterator>
#include <string>

namespace {

constexpr wchar_t kWindowClassName[] = L"FLUTTER_RUNNER_WIN32_WINDOW";
constexpr wchar_t kTargetExeName[] = L"focsq.exe";
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

std::wstring StubDir() {
  std::wstring path = CurrentExecutablePath();
  const auto slash = path.find_last_of(L'\\');
  if (slash == std::wstring::npos) return {};
  path.resize(slash);
  return path;
}

BOOL CALLBACK FindAppWindowCallback(HWND window, LPARAM parameter) {
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
      length > 0 &&
      ::_wcsicmp(path.substr(0, length).c_str(),
                 (StubDir() + L"\\" + kTargetExeName).c_str()) == 0;
  ::CloseHandle(process);

  if (same_app) {
    *found = window;
    return FALSE;
  }
  return TRUE;
}

void FocusRunningApp(HWND target) {
  ::ShowWindow(target, SW_RESTORE);
  ::SetForegroundWindow(target);
  // Перестраховка: переключение на окно elevated-процесса из обычного
  // процесса иногда требует SwitchToThisWindow.
  if (::GetForegroundWindow() != target) {
    ::SwitchToThisWindow(target, TRUE);
  }
}

void LaunchApp() {
  const std::wstring dir = StubDir();
  const std::wstring target = dir + L"\\" + kTargetExeName;
  // runas, а не open: манифест focsq.exe — asInvoker, и повышается он сам.
  // «open» добавлял лишний не-elevated процесс-переходник, который успевал
  // забрать мьютекс и потратить UAC-запрос впустую.
  ::ShellExecuteW(nullptr, L"runas", target.c_str(), nullptr, dir.c_str(),
                  SW_SHOWNORMAL);
}

}  // namespace

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  // Быстрый путь: живой экземпляр ловит именованное событие и показывает
  // своё окно сам. Прямой ShowWindow из заглушки не работает, когда
  // focsq.exe запущен elevated (UIPI молча блокирует).
  HANDLE event = ::CreateEventW(nullptr, FALSE, FALSE, kActivateEventName);
  if (event != nullptr) {
    if (::GetLastError() == ERROR_ALREADY_EXISTS) {
      ::SetEvent(event);
      ::CloseHandle(event);
      return EXIT_SUCCESS;
    }
    ::CloseHandle(event);
  }
  HWND target = nullptr;
  ::EnumWindows(&FindAppWindowCallback, reinterpret_cast<LPARAM>(&target));
  if (target != nullptr) {
    FocusRunningApp(target);
  } else {
    LaunchApp();
  }
  return EXIT_SUCCESS;
}
