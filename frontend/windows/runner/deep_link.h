#ifndef RUNNER_DEEP_LINK_H_
#define RUNNER_DEEP_LINK_H_

#include <windows.h>

#include <string>

namespace deep_link {

constexpr UINT kCopyDataMessage = WM_COPYDATA;
constexpr UINT kPipeLinkMessage = WM_APP + 0x51;
constexpr DWORD kCopyDataTag = 0xF0C5514E;

bool IsLink(const std::string& argument);
bool ForwardToRunningInstance(const std::string& link);
bool StartPipeServer(HWND target);
void StopPipeServer();

}  // namespace deep_link

#endif  // RUNNER_DEEP_LINK_H_
