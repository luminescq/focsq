//
// Created by yangbin on 2021/11/12.
//

#include "web_view.h"

#include <tchar.h>
#include <windows.h>

#include <cassert>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "flutter/method_result_functions.h"
#include "strconv.h"
#include "utils.h"

namespace webview_window {

// Diagnostic metadata only: no URLs, cookies, page contents or credentials.
static void TraceNavigation(const char* stage, long code = 0) {
  if (!GetEnvironmentVariableW(L"FOCSQ_WEBVIEW_DIAGNOSTICS", nullptr, 0)) return;
  wchar_t temp[MAX_PATH];
  const DWORD length = GetTempPathW(MAX_PATH, temp);
  if (!length || length >= MAX_PATH) return;
  std::ofstream log(std::filesystem::path(temp) / L"focsq-webview-navigation.log",
                    std::ios::app);
  log << GetTickCount64() << " pid=" << GetCurrentProcessId()
      << " " << stage << " code=" << code << "\n";
}


static LRESULT CALLBACK WndProc(HWND const window, UINT const message,
                                WPARAM const wparam,
                                LPARAM const lparam) noexcept {
  return DefWindowProc(window, message, wparam, lparam);
}

const auto kWebViewClassName = _T("web_view_window_web_view");

using namespace Microsoft::WRL;

WebView::WebView(
    std::shared_ptr<flutter::MethodChannel<flutter::EncodableValue>>
        method_channel,
    int64_t web_view_id, std::wstring userDataFolder,
    std::function<void(HRESULT)> on_web_view_created)
    : method_channel_(std::move(method_channel)),
      web_view_id_(web_view_id),
      user_data_folder_(std::move(userDataFolder)),
      on_web_view_created_callback_(std::move(on_web_view_created)) {
  RegisterWindowClass(kWebViewClassName, WndProc);
  view_window_ = wil::unique_hwnd(::CreateWindowEx(
      0, kWebViewClassName, L"", WS_CHILD | WS_VISIBLE, 0, 0, 0, 0,
      HWND_MESSAGE, nullptr, ::GetModuleHandle(nullptr), nullptr));
  assert(view_window_ != nullptr);
  if (!view_window_) {
    FinishCreation(HRESULT_FROM_WIN32(GetLastError()));
    return;
  }

  const HRESULT environment_result = CreateCoreWebView2EnvironmentWithOptions(
      nullptr, user_data_folder_.c_str(), nullptr,
      Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
          [this](HRESULT result, ICoreWebView2Environment *env) -> HRESULT {
            if (FAILED(result) || !env) {
              FinishCreation(FAILED(result) ? result : E_POINTER);
              return S_OK;
            }
            const HRESULT controller_result = env->CreateCoreWebView2Controller(
                view_window_.get(),
                Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                    [this](HRESULT result,
                           ICoreWebView2Controller *controller) -> HRESULT {
                      if (FAILED(result) || !controller) {
                        FinishCreation(FAILED(result) ? result : E_POINTER);
                        return S_OK;
                      }
                      webview_controller_ = controller;
                      // Dart can issue launch as soon as creation completes.
                      // Do not signal readiness until the WebView exists.
                      const HRESULT initialized = OnWebviewControllerCreated();
                      FinishCreation(initialized);
                      return S_OK;
                    }).Get());
            if (FAILED(controller_result)) {
              FinishCreation(controller_result);
            }
            return S_OK;
          }).Get());
  if (FAILED(environment_result)) {
    FinishCreation(environment_result);
  }
}

void WebView::FinishCreation(HRESULT result) {
  // The owner can destroy this WebView on failure; retain the callback locally.
  auto callback = std::move(on_web_view_created_callback_);
  if (callback) callback(result);
}

HRESULT WebView::OnWebviewControllerCreated() {
  if (!webview_controller_) {
    return E_UNEXPECTED;
  }
  
  HRESULT hr = webview_controller_->get_CoreWebView2(&webview_);
  if (FAILED(hr) || !webview_) {
    std::cerr << "failed to get core webview" << std::endl;
    return FAILED(hr) ? hr : E_POINTER;
  }

  wil::com_ptr<ICoreWebView2Settings> settings;
  hr = webview_->get_Settings(&settings);
  if (FAILED(hr) || !settings) {
    std::cerr << "failed to get settings" << std::endl;
    return FAILED(hr) ? hr : E_POINTER;
  }
  
  settings->put_IsScriptEnabled(true);
  settings->put_IsZoomControlEnabled(false);
  settings->put_AreDefaultContextMenusEnabled(false);
  settings->put_IsStatusBarEnabled(false);
  settings->put_IsWebMessageEnabled(true);

  wil::com_ptr<ICoreWebView2Settings2> settings2;
  hr = settings->QueryInterface(IID_PPV_ARGS(&settings2));
  if (SUCCEEDED(hr) && settings2) {
    wil::unique_cotaskmem_string user_agent;
    hr = settings2->get_UserAgent(&user_agent);
    if (SUCCEEDED(hr) && user_agent) {
      default_user_agent_ = std::wstring(user_agent.get());
    }
  }

  UpdateBounds();

  // Always use single window to load web page.
  webview_->add_NewWindowRequested(
      Callback<ICoreWebView2NewWindowRequestedEventHandler>(
          [](ICoreWebView2 *sender,
             ICoreWebView2NewWindowRequestedEventArgs *args) {
            wil::unique_cotaskmem_string url;
            HRESULT hr = args->get_Uri(&url);
            if (SUCCEEDED(hr) && url) {
              sender->Navigate(url.get());
            }
            args->put_Handled(true);
            return S_OK;
          })
          .Get(),
      nullptr);

  webview_->add_ContentLoading(
      Callback<ICoreWebView2ContentLoadingEventHandler>(
          [](ICoreWebView2 *sender,
             ICoreWebView2ContentLoadingEventArgs *args) { return S_OK; })
          .Get(),
      nullptr);

  webview_->add_HistoryChanged(
      Callback<ICoreWebView2HistoryChangedEventHandler>(
          [this](ICoreWebView2 *sender, IUnknown *args) {
            auto method_args = flutter::EncodableMap{
                {flutter::EncodableValue("id"),
                 flutter::EncodableValue(web_view_id_)},
                {flutter::EncodableValue("canGoBack"),
                 flutter::EncodableValue(CanGoBack())},
                {flutter::EncodableValue("canGoForward"),
                 flutter::EncodableValue(CanGoForward())},
            };
            method_channel_->InvokeMethod(
                "onHistoryChanged",
                std::make_unique<flutter::EncodableValue>(method_args));
            return S_OK;
          })
          .Get(),
      nullptr);

  webview_->add_NavigationStarting(
      Callback<ICoreWebView2NavigationStartingEventHandler>(
          [this](ICoreWebView2 *sender,
                 ICoreWebView2NavigationStartingEventArgs *args) {
            method_channel_->InvokeMethod(
                "onNavigationStarted",
                std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
                    {flutter::EncodableValue("id"),
                     flutter::EncodableValue(web_view_id_)},
                }));

            TraceNavigation(triggerOnUrlRequestedEvent && !allow_next_replayed_navigation_
                                ? "navigation-filter" : "navigation-allow");
            if (triggerOnUrlRequestedEvent && !allow_next_replayed_navigation_) {
              wil::unique_cotaskmem_string uri;
              HRESULT hr = args->get_Uri(&uri);
              if (FAILED(hr) || !uri) {
                args->put_Cancel(true);
                return S_OK;
              }

              // Capture URI string before async callback
              std::wstring uri_string(uri.get());

              auto result_handler =
                  std::make_unique<flutter::MethodResultFunctions<>>(
                      [uri_string, sender,
                       this](const flutter::EncodableValue *success_value) {
                        bool letPass = false;
                        if (success_value && 
                            std::holds_alternative<bool>(*success_value)) {
                          letPass = std::get<bool>(*success_value);
                        }
                        TraceNavigation(letPass ? "filter-approved" : "filter-declined");
                        if (letPass) {
                          // Bypass this replay once without changing the configured policy.
                          allow_next_replayed_navigation_ = true;
                          if (FAILED(sender->Navigate(uri_string.c_str()))) {
                            allow_next_replayed_navigation_ = false;
                          }
                        }
                      },
                      nullptr, nullptr);

              method_channel_->InvokeMethod(
                  "onUrlRequested",
                  std::make_unique<flutter::EncodableValue>(
                      flutter::EncodableMap{
                          {flutter::EncodableValue("id"),
                           flutter::EncodableValue(web_view_id_)},
                          {flutter::EncodableValue("url"),
                           flutter::EncodableValue(
                               wide_to_utf8(uri_string))},
                      }),
                  std::move(result_handler));

              // navigation is canceled here and retriggered later from the
              // callback passed to the method channel
              args->put_Cancel(true);
            } else {
              args->put_Cancel(false);
              allow_next_replayed_navigation_ = false;
            }
            return S_OK;
          })
          .Get(),
      nullptr);
  webview_->add_NavigationCompleted(
      Callback<ICoreWebView2NavigationCompletedEventHandler>(
          [this](ICoreWebView2 *sender,
                 ICoreWebView2NavigationCompletedEventArgs *args) {
            BOOL succeeded = FALSE;
            COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
            args->get_IsSuccess(&succeeded);
            args->get_WebErrorStatus(&status);
            TraceNavigation(succeeded ? "navigation-completed" : "navigation-failed", status);
            auto method_args = flutter::EncodableMap{
                {flutter::EncodableValue("id"),
                 flutter::EncodableValue(web_view_id_)},
            };
            method_channel_->InvokeMethod(
                "onNavigationCompleted",
                std::make_unique<flutter::EncodableValue>(method_args));
            return S_OK;
          })
          .Get(),
      nullptr);
  webview_->add_WebMessageReceived(
      Callback<ICoreWebView2WebMessageReceivedEventHandler>(
          [this](ICoreWebView2 *sender,
                 ICoreWebView2WebMessageReceivedEventArgs *args) {
            wil::unique_cotaskmem_string messageRaw;
            HRESULT hrString = args->TryGetWebMessageAsString(&messageRaw);
            if (FAILED(hrString)) {
              if (hrString == E_INVALIDARG) {
                // web message was not a string --> should only happen if it was
                // a JSON object
                HRESULT hrJson = args->get_WebMessageAsJson(&messageRaw);
                if (FAILED(hrJson)) {
                  return hrJson;
                }
              } else {
                return hrString;
              }
            }
            method_channel_->InvokeMethod(
                "onWebMessageReceived",
                std::make_unique<flutter::EncodableValue>(flutter::EncodableMap{
                    {flutter::EncodableValue("id"),
                     flutter::EncodableValue(web_view_id_)},
                    {flutter::EncodableValue("message"),
                     flutter::EncodableValue(
                         wide_to_utf8(std::wstring(messageRaw.get())))},
                }));
            return S_OK;
          })
          .Get(),
      nullptr);
  return S_OK;
}

void WebView::UpdateBounds() {
  // Resize WebView to fit the bounds of the parent window
  RECT bounds;
  GetClientRect(view_window_.get(), &bounds);
  if (webview_controller_) 
    webview_controller_->put_Bounds(bounds);
}

HRESULT WebView::Navigate(const std::wstring &url) {
  if (!webview_) return E_UNEXPECTED;
  const HRESULT hr = webview_->Navigate(url.c_str());
  TraceNavigation("navigate-requested", hr);
  return hr;
}

void WebView::AddScriptToExecuteOnDocumentCreated(
    const std::wstring &javaScript) {
  if (webview_) {
    webview_->AddScriptToExecuteOnDocumentCreated(javaScript.c_str(), nullptr);
  }
}

void WebView::SetApplicationNameForUserAgent(const std::wstring &name) {
  if (webview_) {
    wil::com_ptr<ICoreWebView2Settings> settings;
    HRESULT hr = webview_->get_Settings(&settings);
    if (FAILED(hr) || !settings) {
      return;
    }
    wil::com_ptr<ICoreWebView2Settings2> settings2;
    hr = settings->QueryInterface(IID_PPV_ARGS(&settings2));
    if (SUCCEEDED(hr) && settings2) {
      settings2->put_UserAgent((default_user_agent_ + name).c_str());
    }
  }
}

void WebView::GoBack() {
  if (webview_) {
    webview_->GoBack();
  }
}

void WebView::GoForward() {
  if (webview_) {
    webview_->GoForward();
  }
}

void WebView::Reload() {
  if (webview_) {
    webview_->Reload();
  }
}

void WebView::Stop() {
  if (webview_) {
    webview_->Stop();
  }
}

void WebView::GetAllCookies(
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  if (webview_) {
    wil::com_ptr<ICoreWebView2_2> webView2;
    HRESULT hr = webview_->QueryInterface(IID_PPV_ARGS(&webView2));

    if (FAILED(hr) || !webView2) {
      result->Error("0", "Failed to get ICoreWebView2_2");
      return;
    }

    wil::com_ptr<ICoreWebView2CookieManager> cookieManager;
    HRESULT hrc = webView2->get_CookieManager(&cookieManager);

    if (FAILED(hrc) || !cookieManager) {
      result->Error("0", "Failed to get ICoreWebView2CookieManager");
      return;
    }

    cookieManager->GetCookies(
        nullptr,
        Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [result = std::move(result)](
                HRESULT hr, ICoreWebView2CookieList *cookieList) -> HRESULT {
              if (FAILED(hr) || !cookieList) {
                result->Error("0", "Failed to get cookies");
                return S_OK;
              }

              UINT cookieCount;
              hr = cookieList->get_Count(&cookieCount);
              if (FAILED(hr)) {
                result->Error("0", "Failed to get cookie count");
                return S_OK;
              }

              std::vector<flutter::EncodableValue> cookies;
              for (UINT i = 0; i < cookieCount; ++i) {
                wil::com_ptr<ICoreWebView2Cookie> cookie;
                hr = cookieList->GetValueAtIndex(i, &cookie);
                if (FAILED(hr) || !cookie) {
                  continue;
                }

                wil::unique_cotaskmem_string name;
                wil::unique_cotaskmem_string value;
                wil::unique_cotaskmem_string domain;
                wil::unique_cotaskmem_string path;
                double expires;
                BOOL isSecure;
                BOOL isHttpOnly;
                BOOL isSessionOnly;

                hr = cookie->get_Name(&name);
                if (FAILED(hr)) continue;
                hr = cookie->get_Value(&value);
                if (FAILED(hr)) continue;
                hr = cookie->get_Domain(&domain);
                if (FAILED(hr)) continue;
                hr = cookie->get_Path(&path);
                if (FAILED(hr)) continue;
                hr = cookie->get_Expires(&expires);
                if (FAILED(hr)) continue;
                hr = cookie->get_IsSecure(&isSecure);
                if (FAILED(hr)) continue;
                hr = cookie->get_IsHttpOnly(&isHttpOnly);
                if (FAILED(hr)) continue;
                hr = cookie->get_IsSession(&isSessionOnly);
                if (FAILED(hr)) continue;

                std::map<flutter::EncodableValue, flutter::EncodableValue>
                    cookieMap;
                cookieMap[flutter::EncodableValue("name")] =
                    flutter::EncodableValue(
                        webview_window::ConvertLPCWSTRToString(name.get()));
                cookieMap[flutter::EncodableValue("value")] =
                    flutter::EncodableValue(
                        webview_window::ConvertLPCWSTRToString(value.get()));
                cookieMap[flutter::EncodableValue("domain")] =
                    flutter::EncodableValue(
                        webview_window::ConvertLPCWSTRToString(domain.get()));
                cookieMap[flutter::EncodableValue("path")] =
                    flutter::EncodableValue(
                        webview_window::ConvertLPCWSTRToString(path.get()));

                if (expires >= 0) {
                  cookieMap[flutter::EncodableValue(std::string("expires"))] =
                      flutter::EncodableValue(static_cast<double>(expires));
                } else {
                  cookieMap[flutter::EncodableValue(std::string("expires"))] =
                      flutter::EncodableValue();
                }

                cookieMap[flutter::EncodableValue("secure")] =
                    flutter::EncodableValue(static_cast<bool>(isSecure));
                cookieMap[flutter::EncodableValue("httpOnly")] =
                    flutter::EncodableValue(static_cast<bool>(isHttpOnly));
                cookieMap[flutter::EncodableValue("sessionOnly")] =
                    flutter::EncodableValue(static_cast<bool>(isSessionOnly));

                cookies.push_back(flutter::EncodableValue(cookieMap));
              }

              result->Success(flutter::EncodableValue(cookies));
              return S_OK;
            })
            .Get());

  } else {
    result->Error("0", "webview not created");
  }
}

void WebView::openDevToolsWindow() {
  if (webview_) {
    webview_->OpenDevToolsWindow();
  }
}

bool WebView::CanGoBack() const {
  if (webview_) {
    BOOL can_go_back;
    webview_->get_CanGoBack(&can_go_back);
    return can_go_back;
  }
  return false;
}

bool WebView::CanGoForward() const {
  if (webview_) {
    BOOL can_go_forward;
    webview_->get_CanGoForward(&can_go_forward);
    return can_go_forward;
  }
  return false;
}

void WebView::ExecuteJavaScript(
    const std::wstring &javaScript,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> completer) {
  if (!webview_) {
    completer->Error("webview_not_ready", "WebView2 is not ready");
    return;
  }
  auto result = std::shared_ptr<flutter::MethodResult<flutter::EncodableValue>>(
      std::move(completer));
  const HRESULT started = webview_->ExecuteScript(
      javaScript.c_str(),
      Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
          [result](HRESULT error, PCWSTR value) -> HRESULT {
            if (FAILED(error)) {
              result->Error(
                  "webview_script_" + std::to_string(static_cast<unsigned long>(error)),
                  "WebView2 script execution failed");
            } else {
              result->Success(flutter::EncodableValue(
                  value ? wide_to_utf8(std::wstring(value)) : ""));
            }
            return S_OK;
          }).Get());
  // Synchronous failure does not schedule the completion handler.
  if (FAILED(started)) {
    result->Error(
        "webview_script_" + std::to_string(static_cast<unsigned long>(started)),
        "WebView2 could not start script execution");
  }
}

void WebView::PostWebMessageAsString(
    const std::wstring &webmessage,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> completer) {
  if (webview_) {
    if (webview_->PostWebMessageAsString(webmessage.c_str()) == NOERROR) {
      completer->Success();
    } else {
      completer->Error("0", "Error posting webmessage as String");
    }
  } else {
    completer->Error("0", "webview not created");
  }
}

void WebView::PostWebMessageAsJson(
    const std::wstring &webmessage,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> completer) {
  if (webview_) {
    if (webview_->PostWebMessageAsJson(webmessage.c_str()) == NOERROR) {
      completer->Success();
    } else {
      completer->Error("0", "Error posting webmessage as JSON");
    }
  } else {
    completer->Error("0", "webview not created");
  }
}

void WebView::setTriggerOnUrlRequestedEvent(const bool value) {
  this->triggerOnUrlRequestedEvent = value;
  allow_next_replayed_navigation_ = false;
}

WebView::~WebView() {
  if (webview_) {
    webview_->Stop();
  }
  if (webview_controller_) {
    webview_controller_->Close();
  }
}

}  // namespace webview_window