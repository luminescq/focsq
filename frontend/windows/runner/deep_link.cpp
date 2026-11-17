#include "deep_link.h"

#include <windows.h>
#include <sddl.h>

#include <algorithm>
#include <cstring>
#include <cwchar>
#include <iterator>
#include <string>
#include <utility>

namespace deep_link {
namespace {

constexpr const wchar_t kWindowClassName[] = L"FLUTTER_RUNNER_WIN32_WINDOW";
constexpr const wchar_t kPipeName[] = L"\\\\.\\pipe\\FOCSQ.deep-link.v1";
constexpr size_t kMaxLinkBytes = 16 * 1024;
constexpr DWORD kPipeBufferBytes = 32 * 1024;

struct PipeServerState {
  HWND target = nullptr;
  HANDLE stop_event = nullptr;
  HANDLE thread = nullptr;
};

PipeServerState* g_pipe_server = nullptr;

std::wstring ExecutablePath() {
  std::wstring path(32768, L'\0');
  DWORD length = static_cast<DWORD>(path.size());
  if (!::QueryFullProcessImageNameW(::GetCurrentProcess(), 0, path.data(),
                                    &length)) {
    return {};
  }
  path.resize(length);
  return path;
}

BOOL CALLBACK FindWindowCallback(HWND window, LPARAM parameter) {
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
  const bool same_process =
      ::QueryFullProcessImageNameW(process, 0, path.data(), &length) != FALSE &&
      path.substr(0, length) == ExecutablePath();
  ::CloseHandle(process);

  if (same_process) {
    *found = window;
    return FALSE;
  }
  return TRUE;
}

DWORD WINAPI PipeServerThread(LPVOID parameter) {
  auto* state = static_cast<PipeServerState*>(parameter);

  while (::WaitForSingleObject(state->stop_event, 0) != WAIT_OBJECT_0) {
    PSECURITY_DESCRIPTOR security_descriptor = nullptr;
    SECURITY_ATTRIBUTES security_attributes{
        sizeof(SECURITY_ATTRIBUTES), nullptr, FALSE};
    if (::ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)", SDDL_REVISION_1,
            &security_descriptor, nullptr)) {
      security_attributes.lpSecurityDescriptor = security_descriptor;
    }

    HANDLE pipe = ::CreateNamedPipeW(
        kPipeName, PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT |
            PIPE_REJECT_REMOTE_CLIENTS,
        1, kPipeBufferBytes, kPipeBufferBytes, 0, &security_attributes);
    if (security_descriptor != nullptr) ::LocalFree(security_descriptor);
    if (pipe == INVALID_HANDLE_VALUE) break;

    const BOOL connected = ::ConnectNamedPipe(pipe, nullptr);
    if (!connected && ::GetLastError() != ERROR_PIPE_CONNECTED) {
      ::CloseHandle(pipe);
      break;
    }

    std::string link;
    char buffer[4096]{};
    DWORD bytes_read = 0;
    while (link.size() < kMaxLinkBytes &&
           ::ReadFile(pipe, buffer, static_cast<DWORD>(std::size(buffer)),
                      &bytes_read, nullptr)) {
      if (bytes_read == 0) break;
      link.append(buffer, bytes_read);
    }

    if (link.size() < kMaxLinkBytes && IsLink(link) &&
        ::WaitForSingleObject(state->stop_event, 0) != WAIT_OBJECT_0) {
      auto* pending = new std::string(std::move(link));
      if (!::PostMessage(state->target, kPipeLinkMessage, 0,
                         reinterpret_cast<LPARAM>(pending))) {
        delete pending;
      }
    }

    ::DisconnectNamedPipe(pipe);
    ::CloseHandle(pipe);
  }

  return 0;
}

}  // namespace

bool IsLink(const std::string& argument) {
  return argument.rfind("csqtt://", 0) == 0;
}

bool StartPipeServer(HWND target) {
  if (target == nullptr || g_pipe_server != nullptr) return false;

  auto* state = new PipeServerState();
  state->target = target;
  state->stop_event = ::CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (state->stop_event == nullptr) {
    delete state;
    return false;
  }

  state->thread =
      ::CreateThread(nullptr, 0, &PipeServerThread, state, 0, nullptr);
  if (state->thread == nullptr) {
    ::CloseHandle(state->stop_event);
    delete state;
    return false;
  }

  g_pipe_server = state;
  return true;
}

void StopPipeServer() {
  auto* state = g_pipe_server;
  if (state == nullptr) return;
  g_pipe_server = nullptr;

  ::SetEvent(state->stop_event);
  if (state->thread != nullptr) {
    ::CancelSynchronousIo(state->thread);
  }

  // Wake a server blocked in ConnectNamedPipe so it can observe the stop event.
  HANDLE wake = ::CreateFileW(kPipeName, GENERIC_WRITE, 0, nullptr,
                              OPEN_EXISTING, 0, nullptr);
  if (wake != INVALID_HANDLE_VALUE) ::CloseHandle(wake);

  if (state->thread != nullptr) {
    ::WaitForSingleObject(state->thread, 5000);
    ::CloseHandle(state->thread);
  }
  ::CloseHandle(state->stop_event);
  delete state;
}

void FocusWindow(HWND target) {
  if (target == nullptr) return;
  ::ShowWindow(target, SW_RESTORE);
  ::SetForegroundWindow(target);
}

bool ForwardToRunningInstance(const std::string& link) {
  if (link.empty() || link.size() >= kMaxLinkBytes) return false;

  HANDLE pipe = ::CreateFileW(kPipeName, GENERIC_WRITE, 0, nullptr,
                              OPEN_EXISTING, 0, nullptr);
  if (pipe != INVALID_HANDLE_VALUE) {
    DWORD written = 0;
    const BOOL sent = ::WriteFile(pipe, link.data(),
                                  static_cast<DWORD>(link.size()), &written,
                                  nullptr);
    ::CloseHandle(pipe);
    if (sent && written == link.size()) {
      HWND target = nullptr;
      ::EnumWindows(&FindWindowCallback, reinterpret_cast<LPARAM>(&target));
      FocusWindow(target);
      return true;
    }
  }

  HWND target = nullptr;
  ::EnumWindows(&FindWindowCallback, reinterpret_cast<LPARAM>(&target));
  if (target == nullptr) return false;

  COPYDATASTRUCT payload{};
  payload.dwData = kCopyDataTag;
  payload.cbData = static_cast<DWORD>(link.size() + 1);
  payload.lpData = const_cast<char*>(link.c_str());

  const LRESULT result = ::SendMessageW(
      target, kCopyDataMessage, static_cast<WPARAM>(GetCurrentProcessId()),
      reinterpret_cast<LPARAM>(&payload));
  if (result != 1) return false;

  ::ShowWindow(target, SW_RESTORE);
  ::SetForegroundWindow(target);
  return true;
}

}  // namespace deep_link
