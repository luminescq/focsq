; FOCSQ installer (Inno Setup 6).
; Build:  flutter build windows --release
; Compile: ISCC tools\focsq_setup.iss
; Result:  build\FOCSQ-Setup.exe
;
; Версия передаётся снаружи (CI берёт её из pubspec.yaml):
;   ISCC /DAppVersion=1.0.0 tools\focsq_setup.iss
; Без /D используется фолбэк #define ниже.

; Версия из /DAppVersion (CI), иначе фолбэк.
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

#define AppName "FOCSQ"
#define AppPublisher "Luminescq"
#define ReleaseDir "..\build\windows\x64\runner\Release"

[Setup]
AppId={{7E1F2C64-9A3B-4E8D-B5C1-A1B2C3D4E5F6}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayIcon={app}\focsq.exe
OutputDir=..\build
OutputBaseFilename=FOCSQ-Setup
Compression=lzma2/max
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
SetupIconFile=..\windows\runner\resources\app_icon.ico
WizardImageFile=wizard_sidebar.bmp
DisableProgramGroupPage=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

; vk_auth_data / vk_captcha_data — профильные данные WebView, появляются
; в Release после локальных запусков приложения; в установщик не попадают.
[Files]
Source: "{#ReleaseDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion; Excludes: "vk_auth_data\*,vk_captcha_data\*"

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\focsq.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\focsq.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Ярлык на рабочем столе"; GroupDescription: "Дополнительно:"
Name: "autostart"; Description: "Запускать при входе в Windows (без UAC-промпта)"; GroupDescription: "Дополнительно:"

; Автозапуск через планировщик с правами админа — без UAC при входе
[Run]
Filename: "schtasks"; Parameters: "/Create /TN FOCSQ /TR """"{app}\focsq.exe"" --minimized"" /SC ONLOGON /RL HIGHEST /F"; Flags: runhidden; Tasks: autostart
; runascurrentuser: postinstall по умолчанию стартует от исходного (не
; повышенного) пользователя, а focsq.exe с requireAdministrator даёт
; CreateProcess code 740 — запускаем из контекста установщика (админ)
Filename: "{app}\focsq.exe"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent runascurrentuser

[UninstallRun]
Filename: "schtasks"; Parameters: "/Delete /TN FOCSQ /F"; Flags: runhidden; RunOnceId: "DelTask"
Filename: "taskkill.exe"; Parameters: "/IM focsq.exe /F"; Flags: runhidden; RunOnceId: "KillApp"
