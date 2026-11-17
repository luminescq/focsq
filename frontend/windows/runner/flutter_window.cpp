#include "flutter_window.h"

#include <flutter/standard_method_codec.h>

#include <optional>

#include "deep_link.h"
#include "flutter/generated_plugin_registrant.h"

FlutterWindow::FlutterWindow(const flutter::DartProject& project)
    : project_(project) {}

FlutterWindow::~FlutterWindow() {}

bool FlutterWindow::OnCreate() {
  if (!Win32Window::OnCreate()) {
    return false;
  }

  RECT frame = GetClientArea();

  // The size here must match the window dimensions to avoid unnecessary surface
  // creation / destruction in the startup path.
  flutter_controller_ = std::make_unique<flutter::FlutterViewController>(
      frame.right - frame.left, frame.bottom - frame.top, project_);
  // Ensure that basic setup of the controller was successful.
  if (!flutter_controller_->engine() || !flutter_controller_->view()) {
    return false;
  }
  RegisterPlugins(flutter_controller_->engine());

  // Later app launches forward csqtt:// links over this pipe. A lower-integrity
  // sender must also be able to deliver WM_COPYDATA to the elevated window.
  ::ChangeWindowMessageFilterEx(GetHandle(), WM_COPYDATA, MSGFLT_ALLOW,
                                nullptr);
  deep_link_channel_ =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          flutter_controller_->engine()->messenger(), "focsq/deep_link",
          &flutter::StandardMethodCodec::GetInstance());
  deep_link::StartPipeServer(GetHandle());

  SetChildContent(flutter_controller_->view()->GetNativeWindow());

  flutter_controller_->engine()->SetNextFrameCallback([&]() {
    this->Show();
  });

  // Flutter can complete the first frame before the "show window" callback is
  // registered. The following call ensures a frame is pending to ensure the
  // window is shown. It is a no-op if the first frame hasn't completed yet.
  flutter_controller_->ForceRedraw();

  return true;
}

void FlutterWindow::OnDestroy() {
  deep_link::StopPipeServer();
  if (flutter_controller_) {
    flutter_controller_ = nullptr;
  }

  Win32Window::OnDestroy();
}

LRESULT
FlutterWindow::MessageHandler(HWND hwnd, UINT const message,
                              WPARAM const wparam,
                              LPARAM const lparam) noexcept {
  if (message == deep_link::kPipeLinkMessage) {
    auto* pending = reinterpret_cast<std::string*>(lparam);
    if (pending == nullptr) return 0;
    if (deep_link_channel_ && deep_link::IsLink(*pending)) {
      deep_link_channel_->InvokeMethod(
          "onLink", std::make_unique<flutter::EncodableValue>(*pending));
      Show();
    }
    delete pending;
    return 1;
  }

  if (message == deep_link::kCopyDataMessage) {
    auto* payload = reinterpret_cast<COPYDATASTRUCT*>(lparam);
    const auto* data =
        payload == nullptr ? nullptr : static_cast<const char*>(payload->lpData);
    if (payload == nullptr || payload->dwData != deep_link::kCopyDataTag ||
        data == nullptr || payload->cbData == 0 ||
        payload->cbData > 16 * 1024 || data[payload->cbData - 1] != '\0') {
      return 0;
    }
    const std::string link(data, payload->cbData - 1);
    if (!deep_link::IsLink(link) || !deep_link_channel_) return 0;
    deep_link_channel_->InvokeMethod(
        "onLink", std::make_unique<flutter::EncodableValue>(link));
    Show();
    return 1;
  }

  // Give Flutter, including plugins, an opportunity to handle window messages.
  if (flutter_controller_) {
    std::optional<LRESULT> result =
        flutter_controller_->HandleTopLevelWindowProc(hwnd, message, wparam,
                                                      lparam);
    if (result) {
      return *result;
    }
  }

  switch (message) {
    case WM_FONTCHANGE:
      flutter_controller_->engine()->ReloadSystemFonts();
      break;
  }

  return Win32Window::MessageHandler(hwnd, message, wparam, lparam);
}
