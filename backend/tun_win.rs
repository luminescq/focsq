// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

//! Нативный TUN на Windows через драйвер wintun (аналог VpnService на Android).
//!
//! Режим включается аргументом tun_uds="wintun": ядро само создаёт адаптер,
//! а после получения TUNCONF от сервера настраивает адрес/DNS/маршруты
//! (0.0.0.0/1 + 128.0.0.0/1 через адаптер; TURN/VK — через исходный шлюз).
//! Требуются права администратора и wintun.dll рядом с исполняемым файлом.
//!
//! [FOCSQ] Оптимизированная версия tun_win.rs из форка (поведение идентично):
//! • константы масок/шлюза вынесены (HOST_MASK/HALF_MASK/HALF_NETS/ON_LINK_GATEWAY)
//! • apply_tunconf разбит на именованные шаги: set_address/set_dns/
//!   set_interface_metric/add_half_routes/add_exclude_route/route_delete
//! • find_default_route через итератор с min_by_key
//! • wait_for_reader_release вынесен из teardown в метод WinTunDevice

use anyhow::{Context, Result, bail};
use std::{
    collections::HashSet,
    net::{IpAddr, Ipv4Addr},
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};
use windows_sys::core::GUID;
use windows_sys::Win32::{
    Foundation::{ERROR_OBJECT_ALREADY_EXISTS, NO_ERROR},
    NetworkManagement::{
        IpHelper::{
            ConvertInterfaceLuidToGuid, CreateUnicastIpAddressEntry, DeleteUnicastIpAddressEntry,
            GetIpInterfaceEntry, InitializeIpInterfaceEntry, InitializeUnicastIpAddressEntry,
            MIB_IPINTERFACE_ROW, MIB_UNICASTIPADDRESS_ROW, SetIpInterfaceEntry,
        },
        Ndis::NET_LUID_LH,
    },
    Networking::WinSock::{
        AF_INET, IpDadStatePreferred, IpPrefixOriginManual, IpSuffixOriginManual,
    },
};

const ADAPTER_NAME: &str = "csqtt";
/// Фиксированный GUID (как WintunStaticRequestedGUID в PWDTT/WireGuard):
/// повторные запуски переиспользуют тот же адаптер вместо конфликта имён
const ADAPTER_GUID: u128 = 0xC5A6_E4B1_9F2D_4E7A_B3C8_1A2B_3C4D_5E6F;
/// MTU как в оригинале (Constants.Vpn.DEFAULT_MTU)
const MTU: usize = 1300;
/// Низкая метрика интерфейса — маршруты через TUN обязаны выигрывать
const INTERFACE_METRIC: &str = "1";

const HOST_MASK: &str = "255.255.255.255";
const HALF_MASK: &str = "128.0.0.0";
const HALF_NETS: [&str; 2] = ["0.0.0.0", "128.0.0.0"];
const ON_LINK_GATEWAY: &str = "0.0.0.0";

/// Живой адаптер текущей сессии — нужен apply_tunconf из config_task
static ADAPTER: Mutex<Option<Arc<wintun::Adapter>>> = Mutex::new(None);
/// Активное устройство — для полного teardown при остановке клиента
static DEVICE: Mutex<Option<Arc<WinTunDevice>>> = Mutex::new(None);
/// Исходный шлюз/интерфейс из apply_tunconf — источник для динамических
/// exclude-маршрутов (TURN-серверы узнаются уже после настройки TUN) [FOCSQ]
static GATEWAY_ROUTE: Mutex<Option<(String, String)>> = Mutex::new(None);
/// IP, исключённые динамически: дедуп вызовов от всех воркеров.
/// IP, увиденные ДО apply_tunconf, применяются сразу после перехвата
/// маршрутов — иначе гоночное окно с петлёй на первых аллокациях [FOCSQ]
static DYNAMIC_EXCLUDES: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn dynamic_excludes() -> &'static Mutex<HashSet<String>> {
    DYNAMIC_EXCLUDES.get_or_init(|| Mutex::new(HashSet::new()))
}
static CLEANUP_ROUTES: OnceLock<Mutex<Vec<String>>> = OnceLock::new();

fn cleanup_routes() -> &'static Mutex<Vec<String>> {
    CLEANUP_ROUTES.get_or_init(|| Mutex::new(Vec::new()))
}

pub struct WinTunDevice {
    // Порядок полей = порядок drop: сначала сессия, затем адаптер и DLL
    session: Arc<wintun::Session>,
    // Держим Arc адаптера ради порядка drop (сессия должна закрыться первой)
    _adapter: Arc<wintun::Adapter>,
    _wintun: wintun::Wintun,
}

impl WinTunDevice {
    pub fn new() -> Result<Arc<Self>> {
        let dll_path = resolve_dll()?;
        // SAFETY: загрузка библиотеки wintun.dll; путь проверен на существование
        let wintun = unsafe { wintun::load_from_path(&dll_path) }.with_context(|| {
            format!("не удалось загрузить {dll_path:?} (нужен wintun.dll с wintun.net)")
        })?;
        let adapter = wintun::Adapter::create(&wintun, ADAPTER_NAME, "CSQTT", Some(ADAPTER_GUID))
            .context("не удалось создать wintun-адаптер (запустите приложение от администратора)")?;
        let session = Arc::new(
            adapter
                .start_session(wintun::MAX_RING_CAPACITY)
                .context("не удалось открыть wintun-сессию")?,
        );
        // [FOCSQ] MTU НЕ ставим крейтовским adapter.set_mtu(): внутри он
        // запускает `netsh interface ipv4 set subinterface` голым Command
        // без скрытия окна — это и была вспышка консоли при подключении.
        // MTU выставляется нативно в configure_interface (NlMtu).
        // Заменяем предыдущий адаптер (переиспользуется по GUID между запусками)
        *ADAPTER.lock().unwrap() = Some(adapter.clone());
        let device = Arc::new(Self {
            session,
            _adapter: adapter,
            _wintun: wintun,
        });
        // Регистрируем для полного teardown при остановке клиента
        *DEVICE.lock().unwrap() = Some(device.clone());
        Ok(device)
    }

    /// Прервать блокирующее чтение (при остановке клиента)
    pub fn interrupt(&self) {
        let _ = self.session.shutdown();
    }

    /// Блокирующее чтение IP-пакета из адаптера
    pub fn receive_blocking(&self) -> std::io::Result<Vec<u8>> {
        let packet = self.session.receive_blocking()?;
        Ok(packet.bytes().to_vec())
    }

    /// Отправка IP-пакета в адаптер
    pub fn send(&self, data: &[u8]) -> std::io::Result<()> {
        let length =
            u16::try_from(data.len()).map_err(|_| std::io::Error::other("пакет слишком велик"))?;
        let mut packet = self.session.allocate_send_packet(length)?;
        packet.bytes_mut().copy_from_slice(data);
        self.session.send_packet(packet);
        Ok(())
    }

    /// Ждём, пока reader-поток отпустит свой Arc — иначе адаптер не удалится
    fn wait_for_reader_release(this: &Arc<Self>) {
        for _ in 0..50 {
            if Arc::strong_count(this) <= 1 {
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}

/// [FOCSQ] Явный путь к wintun.dll (передаётся из Flutter после распаковки
/// в %LOCALAPPDATA%\FOCSQ) — паттерн WireGuard/Amnezia.
static DLL_PATH_OVERRIDE: Mutex<Option<String>> = Mutex::new(None);

/// Задать путь к wintun.dll в обход стандартного поиска.
pub fn set_dll_path_override(path: String) {
    *DLL_PATH_OVERRIDE.lock().unwrap() = Some(path);
}

fn resolve_dll() -> Result<PathBuf> {
    if let Ok(path) = std::env::var("CSQTT_WINTUN_DLL")
        && !path.trim().is_empty()
    {
        return Ok(PathBuf::from(path));
    }
    if let Some(path) = DLL_PATH_OVERRIDE.lock().unwrap().clone()
        && PathBuf::from(&path).exists()
    {
        return Ok(PathBuf::from(path));
    }
    let exe = std::env::current_exe().context("нет пути к исполняемому файлу")?;
    let path = exe
        .parent()
        .ok_or_else(|| anyhow::anyhow!("нет папки у исполняемого файла"))?
        .join("wintun.dll");
    if !path.exists() {
        bail!("wintun.dll не найден рядом с приложением ({})", path.display());
    }
    Ok(path)
}

// [FOCSQ] Запуск консольных утилит (netsh/route/reg/powershell) без окон.
// Одного CREATE_NO_WINDOW через std::process::Command оказалось недостаточно:
// в боевой сборке netsh всё равно мигал окном консоли. Поэтому raw
// CreateProcessW с ДВОЙНЫМ скрытием — CREATE_NO_WINDOW плюс
// STARTF_USESHOWWINDOW|SW_HIDE в STARTUPINFO: гасит и стартовую консоль,
// и любую AllocConsole внутри ребёнка (показ окна консоли берёт wShowWindow
// из STARTUPINFO, который раньше оставался дефолтным = показать).
mod hidden_process {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    type HANDLE = isize;
    type BOOL = i32;
    type DWORD = u32;

    pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const STARTF_USESHOWWINDOW: u32 = 0x0000_0001;
    const STARTF_USESTDHANDLES: u32 = 0x0000_0100;
    const SW_HIDE: u16 = 0;
    const HANDLE_FLAG_INHERIT: DWORD = 0x0000_0001;
    const START_SUCCESS: BOOL = 1;

    #[repr(C)]
    struct StartupInfoW {
        cb: DWORD,
        lp_reserved: *mut u16,
        lp_desktop: *mut u16,
        lp_title: *mut u16,
        dw_x: DWORD,
        dw_y: DWORD,
        dw_x_size: DWORD,
        dw_y_size: DWORD,
        dw_x_count_chars: DWORD,
        dw_y_count_chars: DWORD,
        dw_fill_attribute: DWORD,
        dw_flags: DWORD,
        w_show_window: u16,
        cb_reserved2: u16,
        lp_reserved2: *mut u8,
        h_std_input: HANDLE,
        h_std_output: HANDLE,
        h_std_error: HANDLE,
    }

    #[repr(C)]
    struct ProcessInformation {
        h_process: HANDLE,
        h_thread: HANDLE,
        dw_process_id: DWORD,
        dw_thread_id: DWORD,
    }

    #[repr(C)]
    struct SecurityAttributes {
        n_length: DWORD,
        lp_security_descriptor: *mut core::ffi::c_void,
        b_inherit_handle: BOOL,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn CreatePipe(
            read: *mut HANDLE,
            write: *mut HANDLE,
            attrs: *const SecurityAttributes,
            size: DWORD,
        ) -> BOOL;
        fn SetHandleInformation(handle: HANDLE, mask: DWORD, flags: DWORD) -> BOOL;
        fn CreateFileW(
            name: *const u16,
            access: DWORD,
            share: DWORD,
            attrs: *const SecurityAttributes,
            disposition: DWORD,
            flags: DWORD,
            template: HANDLE,
        ) -> HANDLE;
        fn CreateProcessW(
            application: *const u16,
            command_line: *mut u16,
            process_attrs: *const SecurityAttributes,
            thread_attrs: *const SecurityAttributes,
            inherit_handles: BOOL,
            flags: DWORD,
            environment: *const core::ffi::c_void,
            current_directory: *const u16,
            startup_info: *mut StartupInfoW,
            process_information: *mut ProcessInformation,
        ) -> BOOL;
        fn CloseHandle(handle: HANDLE) -> BOOL;
        fn WaitForSingleObject(handle: HANDLE, milliseconds: DWORD) -> DWORD;
        fn GetExitCodeProcess(handle: HANDLE, code: *mut DWORD) -> BOOL;
        fn ReadFile(
            file: HANDLE,
            buffer: *mut u8,
            to_read: DWORD,
            read: *mut DWORD,
            overlapped: *mut core::ffi::c_void,
        ) -> BOOL;
    }

    const GENERIC_READ: DWORD = 0x8000_0000;
    const FILE_SHARE_READ: DWORD = 1;
    const FILE_SHARE_WRITE: DWORD = 2;
    const OPEN_EXISTING: DWORD = 3;
    const INVALID_HANDLE_VALUE: HANDLE = -1;
    const INFINITE: DWORD = 0xFFFF_FFFF;

    fn wide(s: &str) -> Vec<u16> {
        OsStr::new(s).encode_wide().chain(Some(0)).collect()
    }

    /// Экранирование аргумента по правилам CommandLineToArgvW (как у std).
    fn quote_arg(arg: &str) -> String {
        if !arg.is_empty()
            && !arg
                .bytes()
                .any(|b| b == b' ' || b == b'"' || b == b'\t')
        {
            return arg.to_owned();
        }
        let mut out = String::with_capacity(arg.len() + 2);
        out.push('"');
        let mut backslashes = 0usize;
        for ch in arg.chars() {
            match ch {
                '\\' => backslashes += 1,
                '"' => {
                    out.extend(std::iter::repeat('\\').take(backslashes * 2 + 1));
                    backslashes = 0;
                    out.push('"');
                }
                _ => {
                    out.extend(std::iter::repeat('\\').take(backslashes));
                    backslashes = 0;
                    out.push(ch);
                }
            }
        }
        out.extend(std::iter::repeat('\\').take(backslashes * 2));
        out.push('"');
        out
    }

    fn last_error() -> i32 {
        // GetLastError-код — u32; знаковость i32 для Win32-ошибок договорная.
        unsafe { GetLastErrorRaw() as i32 }
    }

    unsafe extern "system" {
        #[link_name = "GetLastError"]
        fn GetLastErrorRaw() -> u32;
    }

    /// Читает пайп до EOF (дочерний поток: netsh пишет и stdout, и stderr).
    fn drain_pipe(handle: HANDLE) -> Vec<u8> {
        let mut out = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let mut read: DWORD = 0;
            let ok = unsafe { ReadFile(handle, buf.as_mut_ptr(), buf.len() as DWORD, &mut read, std::ptr::null_mut()) };
            if ok == 0 || read == 0 {
                break;
            }
            out.extend_from_slice(&buf[..read as usize]);
        }
        out
    }

    /// Запуск с полным скрытием окна. Возвращает (код выхода, вывод).
    pub fn run(command: &str, args: &[&str]) -> std::io::Result<(i32, String)> {
        let command_line = std::iter::once(quote_arg(command))
            .chain(args.iter().map(|a| quote_arg(a)))
            .collect::<Vec<_>>()
            .join(" ");
        let mut cl_wide = wide(&command_line);

        // Пайпы stdout/stderr с наследуемыми записывающими концами
        let mut out_r: HANDLE = 0;
        let mut out_w: HANDLE = 0;
        let mut err_r: HANDLE = 0;
        let mut err_w: HANDLE = 0;
        let sa = SecurityAttributes {
            n_length: std::mem::size_of::<SecurityAttributes>() as DWORD,
            lp_security_descriptor: std::ptr::null_mut(),
            b_inherit_handle: 1,
        };
        unsafe {
            if CreatePipe(&mut out_r, &mut out_w, &sa, 0) == 0
                || CreatePipe(&mut err_r, &mut err_w, &sa, 0) == 0
            {
                return Err(std::io::Error::last_os_error());
            }
            for h in [out_w, err_w] {
                if SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) == 0 {
                    return Err(std::io::Error::last_os_error());
                }
            }
        }
        // stdin — устройство NUL (аналог Stdio::null)
        let nul_name = wide("NUL");
        let stdin_nul = unsafe {
            CreateFileW(
                nul_name.as_ptr(),
                GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                &sa,
                OPEN_EXISTING,
                0,
                0,
            )
        };

        let mut si = StartupInfoW {
            cb: std::mem::size_of::<StartupInfoW>() as DWORD,
            lp_reserved: std::ptr::null_mut(),
            lp_desktop: std::ptr::null_mut(),
            lp_title: std::ptr::null_mut(),
            dw_x: 0,
            dw_y: 0,
            dw_x_size: 0,
            dw_y_size: 0,
            dw_x_count_chars: 0,
            dw_y_count_chars: 0,
            dw_fill_attribute: 0,
            dw_flags: STARTF_USESHOWWINDOW | STARTF_USESTDHANDLES,
            w_show_window: SW_HIDE,
            cb_reserved2: 0,
            lp_reserved2: std::ptr::null_mut(),
            h_std_input: if stdin_nul == INVALID_HANDLE_VALUE { 0 } else { stdin_nul },
            h_std_output: out_w,
            h_std_error: err_w,
        };
        let mut pi = ProcessInformation {
            h_process: 0,
            h_thread: 0,
            dw_process_id: 0,
            dw_thread_id: 0,
        };

        let spawned = unsafe {
            CreateProcessW(
                std::ptr::null(),
                cl_wide.as_mut_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                1,
                CREATE_NO_WINDOW,
                std::ptr::null(),
                std::ptr::null(),
                &mut si,
                &mut pi,
            )
        };

        if spawned != START_SUCCESS {
            let err = last_error();
            unsafe {
                CloseHandle(out_r);
                CloseHandle(out_w);
                CloseHandle(err_r);
                CloseHandle(err_w);
                if stdin_nul != INVALID_HANDLE_VALUE {
                    CloseHandle(stdin_nul);
                }
            }
            return Err(std::io::Error::from_raw_os_error(err));
        }

        // Родительские концы записывающих пайпов закрываем — иначе EOF не придёт
        unsafe {
            CloseHandle(out_w);
            CloseHandle(err_w);
            if stdin_nul != INVALID_HANDLE_VALUE {
                CloseHandle(stdin_nul);
            }
        }

        let out_handle = out_r;
        let err_handle = err_r;
        let err_reader = std::thread::spawn(move || drain_pipe(err_handle));
        let stdout = drain_pipe(out_handle);
        let stderr = err_reader.join().unwrap_or_default();
        unsafe {
            CloseHandle(out_handle);
            CloseHandle(err_handle);
        }

        let mut code: DWORD = 0;
        unsafe {
            // Дождаться завершения — иначе код выхода может быть STILL_ACTIVE
            WaitForSingleObject(pi.h_process, INFINITE);
            GetExitCodeProcess(pi.h_process, &mut code);
            CloseHandle(pi.h_process);
            CloseHandle(pi.h_thread);
        }

        let mut text = String::from_utf8_lossy(&stdout).into_owned();
        text.push_str(&String::from_utf8_lossy(&stderr));
        Ok((code as i32, text))
    }
}

fn run_cmd(command: &str, args: &[&str]) -> Result<String> {
    let (code, text) = hidden_process::run(command, args)
        .with_context(|| format!("запуск {command} не удался"))?;
    // [FOCSQ] Ненулевой код — ошибка ВСЕГДА, даже если процесс что-то
    // напечатал (route.exe печатает "The route addition failed..." и
    // возвращает 1 — раньше это считалось успехом, и падение добавления
    // half-routes выглядело в логе как «[TUN] Настроен» без маршрутов).
    if code != 0 {
        bail!("{command} завершился с кодом {code}: {}", text.trim());
    }
    Ok(text)
}

fn run_route(args: &[&str]) -> Result<String> {
    run_cmd("route", args)
}

/// route.exe с owned-аргументами (удобно после сборки Vec<String>)
fn run_route_owned(args: &[String]) -> Result<String> {
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();
    run_route(&refs)
}

/// `route delete <destination> mask <mask>` — ошибки игнорируются
fn route_delete(destination_mask: &str) {
    let mut args: Vec<String> = vec!["delete".into()];
    args.extend(destination_mask.split_whitespace().map(str::to_owned));
    let _ = run_route_owned(&args);
}

/// Фиктивный шлюз внутри /24 нашего адреса: wintun доставляет всё в сессию,
/// реального ARP не нужно, но маршруты через него работают.
fn tun_gateway(ip: Ipv4Addr) -> Ipv4Addr {
    let [a, b, c, d] = ip.octets();
    let last = if d == 1 { 2 } else { 1 };
    Ipv4Addr::new(a, b, c, last)
}

/// Аргументы route.exe: /32 до `destination` через исходный шлюз
/// (или on-link через интерфейс, если шлюза нет — LTE/USB-модемы)
/// [FOCSQ] Подсети VK, которые всегда ходят через исходный шлюз — подход
/// PWDTT (vkExcludeCIDRs): CDN VK ротирует IP, статические диапазоны
/// покрывают его целиком без гонок динамического резолва. Динамический
/// exclude_host_ip остаётся страховкой.
///
/// [FOCSQ] Публичные DNS (8.8.8.0/24, 1.1.1.0/24) из списка УДАЛЕНЫ:
/// заехали из PWDTT, где DNS принципиально идёт напрямую, а у нас DNS
/// туннеля (1.1.1.1) обязан идти В ТУННЕЛЬ — иначе провайдер с DPI
/// отравляет резолв заблокированных доменов даже на сторонний резолвер.
const VK_EXCLUDE_CIDRS: [&str; 13] = [
    "87.240.128.0/18",
    "87.240.192.0/19",
    "90.156.0.0/16",
    "93.186.224.0/21",
    "95.142.192.0/21",
    "95.163.0.0/16",
    "95.213.0.0/18",
    "155.212.192.0/20",
    "185.16.28.0/22",
    "194.67.64.0/18",
    "195.82.146.0/23",
    "213.180.193.0/24",
    "77.88.0.0/18",
];

/// [FOCSQ] Бывшие члены VK_EXCLUDE_CIDRS: их /24-маршруты напрямую могли
/// остаться от прошлых сессий (крэш без teardown) — подчистить при
/// следующем подключении, иначе DNS туннеля продолжит уходить напрямую.
const LEGACY_DNS_EXCLUDE_CIDRS: [&str; 2] = ["8.8.8.0/24", "1.1.1.0/24"];

/// CIDR "a.b.c.d/p" → ("a.b.c.d", "dotted mask")
fn parse_cidr(cidr: &str) -> Option<(String, String)> {
    let (ip_part, prefix_part) = cidr.split_once('/')?;
    let ip: Ipv4Addr = ip_part.parse().ok()?;
    let prefix: u32 = prefix_part.parse().ok()?;
    if prefix > 32 {
        return None;
    }
    let mask_u32: u32 = if prefix == 0 {
        0
    } else {
        u32::MAX << (32 - prefix)
    };
    Some((ip.to_string(), Ipv4Addr::from(mask_u32).to_string()))
}

fn exclude_via_gateway(
    destination: &str,
    mask: &str,
    gateway: &str,
    interface: &str,
) -> Vec<String> {
    let on_link =
        gateway == ON_LINK_GATEWAY || gateway.eq_ignore_ascii_case("on-link");
    let mut args = vec![
        "add".to_owned(),
        destination.to_owned(),
        "mask".to_owned(),
        mask.to_owned(),
    ];
    if on_link {
        args.extend(["0.0.0.0".to_owned(), "IF".to_owned(), interface.to_owned()]);
    } else {
        args.push(gateway.to_owned());
    }
    args.extend(["metric".to_owned(), "1".to_owned()]);
    args
}

/// Добавить exclude-маршрут через исходный шлюз; при ошибке — лог и false
fn add_exclude_route(
    destination: &str,
    gateway: &str,
    interface: &str,
    what: &str,
) -> bool {
    match run_route_owned(&exclude_via_gateway(
        destination,
        HOST_MASK,
        gateway,
        interface,
    )) {
        Ok(_) => true,
        Err(error) => {
            crate::log_error!("[TUN] Exclude-маршрут {what} {destination} не добавлен: {error}");
            false
        }
    }
}

/// Системные IPv4 DNS-серверы (все интерфейсы, кроме нашего адаптера).
/// [FOCSQ] Читаем реестр вместо PowerShell: вызов Get-DnsClientServerAddress
/// через -EncodedCommand из GUI-процесса нестабилен (cmdlet не резолвится,
/// stderr-CLIXML маскирует ошибку под успех → тихий пустой список).
fn system_dns_servers() -> Vec<String> {
    const TCPIP_IFACES: &str =
        r"HKLM\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces";
    const NETWORK_CLASS: &str =
        r"HKLM\SYSTEM\CurrentControlSet\Control\Network\{4D36E972-E325-11CE-BFC1-08002BE10318}";

    let mut result: Vec<String> = Vec::new();
    let Ok(interfaces) = run_cmd("reg", &["query", TCPIP_IFACES]) else {
        crate::log_error!("[TUN] Не удалось перечислить интерфейсы (reg query)");
        return result;
    };
    // Строки вывода — полные пути вида
    // HKEY_LOCAL_MACHINE\...\Interfaces\{GUID}; берём сегмент после
    // последнего разделителя.
    let guids: Vec<&str> = interfaces
        .lines()
        .filter_map(|line| line.split_whitespace().last())
        .filter_map(|path| path.rsplit(['\\', '/']).next())
        .filter(|segment| segment.starts_with('{') && segment.ends_with('}'))
        .collect();
    if guids.is_empty() {
        crate::log_error!("[TUN] Интерфейсы Tcpip не найдены — системные DNS пропущены");
    }
    for guid in guids {
        // Сопоставляем GUID → имя адаптера, чтобы исключить наш 'csqtt'
        let connection_key =
            format!(r"{NETWORK_CLASS}\{guid}\Connection");
        let is_ours = run_cmd("reg", &["query", &connection_key, "/v", "Name"])
            .map(|output| {
                output
                    .lines()
                    .any(|line| line.contains(ADAPTER_NAME))
            })
            .unwrap_or(false);
        if is_ours {
            continue;
        }
        let interface_key = format!(r"{TCPIP_IFACES}\{guid}");
        for value_name in ["DhcpNameServer", "NameServer"] {
            let Ok(value) = run_cmd(
                "reg",
                &["query", &interface_key, "/v", value_name],
            ) else {
                continue;
            };
            for line in value.lines() {
                let Some(pos) = line.find("REG_") else { continue };
                for token in line[pos..].split_whitespace().skip(1) {
                    if token.parse::<Ipv4Addr>().is_ok() && token != "0.0.0.0" {
                        result.push(token.to_owned());
                    }
                }
            }
        }
    }
    result.sort();
    result.dedup();
    if result.is_empty() {
        crate::log_error!(
            "[TUN] Системные DNS не обнаружены — проверь маршруты до роутера"
        );
    }
    result
}

/// Исходный шлюз по умолчанию (до добавления наших маршрутов):
/// парсим `route print -4`, строку "0.0.0.0 0.0.0.0 <gw> <iface> <metric>"
fn find_default_route() -> Result<(String, String)> {
    let output = run_route(&["print", "-4", "0.0.0.0"])?;
    let best = output
        .lines()
        .filter_map(|line| {
            let tokens: Vec<&str> = line.split_whitespace().collect();
            if tokens.len() < 5 || tokens[0] != "0.0.0.0" || tokens[1] != "0.0.0.0" {
                return None;
            }
            let metric = tokens[4].parse::<u32>().ok()?;
            Some((metric, tokens[2].to_owned(), tokens[3].to_owned()))
        })
        .min_by_key(|(metric, _, _)| *metric);
    let (_, gateway, interface) = best.context("не найден шлюз по умолчанию в route print -4")?;
    Ok((gateway, interface))
}

/// Адрес/маска/шлюз через netsh (идемпотентная замена, как в PWDTT):
/// адаптер переживает сессию (фиксированный GUID), поэтому нативный
/// set_address падает на дубликате при повторном запуске.
/// [FOCSQ] ЗАМЕНЕНО нативным CreateUnicastIpAddressEntry: netsh set address —
/// единственный вызов, чья консоль мигала над GUI даже с
/// CREATE_NO_WINDOW|SW_HIDE. IP + префикс /24 создают on-link маршрут
/// подсети; фиктивный шлюз (tun_gateway) лежит внутри неё, поэтому
/// route.exe-маршруты работают без изменений. Ошибка «уже существует»
/// (адаптер переживает сессию) — не ошибка.
fn set_address(luid: u64, ip: Ipv4Addr) -> Result<()> {
    unsafe fn row_for(luid: u64, ip: Ipv4Addr) -> MIB_UNICASTIPADDRESS_ROW {
        let mut row: MIB_UNICASTIPADDRESS_ROW = unsafe { std::mem::zeroed() };
        unsafe { InitializeUnicastIpAddressEntry(&mut row) };
        row.Address.si_family = AF_INET;
        row.Address.Ipv4.sin_family = AF_INET;
        row.Address.Ipv4.sin_addr = unsafe { std::mem::zeroed() };
        // S_addr — байты адреса В ПОРЯДКЕ ПАМЯТИ (network order).
        // from_be_bytes здесь КЛАЛ БАЙТЫ НАОБОРОТ (10.66.67.4 →
        // 4.67.66.10): on-link подсеть не совпадала, half-routes
        // привязывались к физическому интерфейсу и весь трафик
        // уходил в ARP-чёрную дыру. from_ne_bytes кладёт октеты
        // как есть — корректно на любой endianness.
        row.Address.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes(ip.octets());
        row.InterfaceLuid.Value = luid;
        row.PrefixOrigin = IpPrefixOriginManual;
        row.SuffixOrigin = IpSuffixOriginManual;
        // Бессрочный адрес, DAD не ждём — интерфейс поднимается мгновенно
        row.ValidLifetime = 0xFFFF_FFFF;
        row.PreferredLifetime = 0xFFFF_FFFF;
        row.OnLinkPrefixLength = 24;
        row.DadState = IpDadStatePreferred;
        row
    }
    unsafe {
        // Прошлый адрес той же сессии снимаем (адаптер переживает сессию,
        // повторный запуск с тем же IP — обычный случай); «не найден» не ошибка
        let mut old = row_for(luid, ip);
        DeleteUnicastIpAddressEntry(&mut old);
        let mut row = row_for(luid, ip);
        let code = CreateUnicastIpAddressEntry(&mut row);
        if code != NO_ERROR && code != ERROR_OBJECT_ALREADY_EXISTS {
            bail!("CreateUnicastIpAddressEntry: {code}");
        }
    }
    Ok(())
}

/// [FOCSQ] DNS адаптера — нативно через SetInterfaceDnsSettings (см. выше).
/// Старая netsh-версия (set + add dnsservers) удалена целиком.

/// Низкая метрика интерфейса (паттерн PWDTT/WireGuard): суммарная метрика
/// наших маршрутов должна бить физический дефолт. Там же выставляется MTU —
/// крейтовский adapter.set_mtu() внутри гоняет netsh видимым окном.
/// [FOCSQ] Нативно через GetIpInterfaceEntry → SetIpInterfaceEntry: Set
/// перезаписывает ВСЕ изменяемые поля, поэтому сначала читаем текущие.
fn configure_interface(luid: u64) -> Result<()> {
    unsafe {
        let mut row: MIB_IPINTERFACE_ROW = std::mem::zeroed();
        InitializeIpInterfaceEntry(&mut row);
        row.Family = AF_INET;
        row.InterfaceLuid.Value = luid;
        let code = GetIpInterfaceEntry(&mut row);
        if code != NO_ERROR {
            bail!("GetIpInterfaceEntry: {code}");
        }
        // Документированное требование для SetIpInterfaceEntry на IPv4
        row.SitePrefixLength = 0;
        row.UseAutomaticMetric = 0;
        row.Metric = INTERFACE_METRIC.parse().unwrap_or(1);
        row.NlMtu = MTU as u32;
        let code = SetIpInterfaceEntry(&mut row);
        if code != NO_ERROR {
            bail!("SetIpInterfaceEntry: {code}");
        }
    }
    Ok(())
}

// [FOCSQ] DNS адаптера — нативно через SetInterfaceDnsSettings (dnsapi),
// вместо netsh set/add dnsservers: чистый API-вызов без дочерних процессов.
#[repr(C)]
struct DnsIfSettings {
    version: u32,
    domain: *mut u16,
    name_server: *mut u16,
    search_list: *mut u16,
}

#[link(name = "dnsapi")]
unsafe extern "system" {
    fn SetInterfaceDnsSettings(guid: GUID, settings: *const DnsIfSettings) -> i32;
}

fn to_wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

fn luid_to_guid(luid: u64) -> Result<GUID> {
    let mut lh: NET_LUID_LH = unsafe { std::mem::zeroed() };
    // Запись в union-поле безопасна (опасно только чтение) — unsafe не нужен.
    lh.Value = luid;
    let mut guid: GUID = unsafe { std::mem::zeroed() };
    let code = unsafe { ConvertInterfaceLuidToGuid(&lh, &mut guid) };
    if code != NO_ERROR {
        bail!("ConvertInterfaceLuidToGuid: {code}");
    }
    Ok(guid)
}

/// DNS адаптера: все серверы одной строкой через запятую (замена
/// netsh set + add dnsservers). Конфигурация умирает вместе с адаптером.
fn set_dns(guid: GUID, dns_servers: &[String]) -> Result<()> {
    let name_server = to_wide(&dns_servers.join(","));
    let settings = DnsIfSettings {
        version: 1,
        domain: std::ptr::null_mut(),
        name_server: name_server.as_ptr() as *mut u16,
        search_list: std::ptr::null_mut(),
    };
    let code = unsafe { SetInterfaceDnsSettings(guid, &settings) };
    if code != 0 {
        bail!("SetInterfaceDnsSettings: {code}");
    }
    Ok(())
}

/// Маршруты "по умолчанию" через адаптер: точнее настоящего дефолта,
/// но сам 0.0.0.0/0 не трогаем — классический трюк 0.0.0.0/1 + 128.0.0.0/1
fn add_half_routes(gateway_tun: Ipv4Addr) -> Result<()> {
    for destination in HALF_NETS {
        run_route(&[
            "add",
            destination,
            "mask",
            HALF_MASK,
            &gateway_tun.to_string(),
            "metric",
            "1",
        ])
        .with_context(|| format!("маршрут {destination}/1 через TUN не добавлен"))?;
    }
    verify_half_routes(gateway_tun)
}

/// [FOCSQ] Проверяем, что half-routes РЕАЛЬНО в таблице: route.exe может
/// отчитаться успехом, а маршрута не окажется — и тогда «адаптер поднят,
/// а трафик в туннель не идёт» без единой ошибки в логе.
fn verify_half_routes(gateway_tun: Ipv4Addr) -> Result<()> {
    let output = run_route(&["print", "-4"])?;
    let gateway = gateway_tun.to_string();
    for destination in HALF_NETS {
        let present = output.lines().any(|line| {
            let tokens: Vec<&str> = line.split_whitespace().collect();
            tokens.len() >= 5 && tokens[0] == destination && tokens[1] == HALF_MASK && tokens[2] == gateway
        });
        if !present {
            bail!(
                "маршрута {destination}/{HALF_MASK} через {gateway} нет в таблице после добавления"
            );
        }
    }
    Ok(())
}

/// Применить TUNCONF: адрес/шлюз/DNS через netsh (идемпотентно),
/// маршруты — через route.exe. Вызывается при получении TUNCONF.
pub async fn apply_tunconf(ip: &str, dns: &str, peer_ip: &str) -> Result<()> {
    let ip_addr: Ipv4Addr = ip
        .parse()
        .with_context(|| format!("некорректный TUNCONF IP: {ip}"))?;

    // 1. Исходный дефолтный маршрут — ДО того как перехватываем таблицу
    let (gateway, interface) = find_default_route()?;
    // [FOCSQ] Запоминаем для динамических exclude-маршрутов (TURN)
    *GATEWAY_ROUTE.lock().unwrap() = Some((gateway.clone(), interface.clone()));

    // 2. Адрес + фиктивный шлюз + DNS + метрика интерфейса
    let gateway_tun = tun_gateway(ip_addr);
    let adapter = ADAPTER
        .lock()
        .unwrap()
        .clone()
        .context("адаптер TUN не создан — адрес выставить некому")?;
    let luid = unsafe { adapter.get_luid().Value };
    set_address(luid, ip_addr)?;

    let dns_servers: Vec<String> = dns
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    if dns_servers.is_empty() {
        bail!("TUNCONF не содержит DNS");
    }
    if let Err(error) = configure_interface(luid) {
        crate::log_error!("[TUN] Конфигурация интерфейса (метрика/MTU) не выставлена: {error}");
    }

    // 3. Предочистка стейл-маршрутов от прошлой сессии (игнорируем ошибки —
    // их может и не быть), затем добавляем заново
    let mut stale_routes = vec![
        format!("0.0.0.0 mask {HALF_MASK}"),
        format!("128.0.0.0 mask {HALF_MASK}"),
        format!("{peer_ip} mask {HOST_MASK}"),
    ];
    for cidr in VK_EXCLUDE_CIDRS {
        if let Some((network, mask)) = parse_cidr(cidr) {
            stale_routes.push(format!("{network} mask {mask}"));
        }
    }
    // [FOCSQ] Легаси-DNS-подсети: маршруты напрямую от прошлых сессий
    for cidr in LEGACY_DNS_EXCLUDE_CIDRS {
        if let Some((network, mask)) = parse_cidr(cidr) {
            stale_routes.push(format!("{network} mask {mask}"));
        }
    }
    // [FOCSQ] Стейл-DNS-исключения прошлых сессий: раньше TUNCONF-DNS
    // исключался наружу /32-маршрутом — теперь он должен идти в туннель.
    // route_delete ошибки игнорирует, удаление идемпотентно.
    for server in dns_servers.iter().chain(system_dns_servers().iter()) {
        if server.parse::<Ipv4Addr>().is_ok() {
            stale_routes.push(format!("{server} mask {HOST_MASK}"));
        }
    }
    for stale in &stale_routes {
        route_delete(stale);
    }
    // [FOCSQ] Стейл-исключения TURN от прошлой сессии
    for ip in dynamic_excludes().lock().unwrap().drain() {
        route_delete(&format!("{ip} mask {HOST_MASK}"));
    }

    // 4. Перехват трафика. Пир НЕ исключаем: транспорт всегда TURN
    //    (клиент на IP пира напрямую не стучится — только ChannelBind
    //    через релей), а исключение выкидывало весь хостинг на пиру
    //    (Gitea и пр.) на прямой путь провайдера. Через туннель трафик
    //    до собственного IP сервер доставляет себе локально (loopback).
    add_half_routes(gateway_tun)?;
    // [FOCSQ] Подсети VK/DNS целиком через исходный шлюз (PWDTT-style):
    // закрывают ротацию CDN без гонок динамического резолва
    let mut cidr_cleanup: Vec<String> = Vec::new();
    for cidr in VK_EXCLUDE_CIDRS {
        let Some((network, mask)) = parse_cidr(cidr) else {
            continue;
        };
        match run_route_owned(&exclude_via_gateway(
            &network,
            &mask,
            &gateway,
            &interface,
        )) {
            Ok(_) => cidr_cleanup.push(format!("{network} mask {mask}")),
            Err(error) => crate::log_error!(
                "[TUN] Exclude-подсеть VK {cidr} не добавлена: {error}"
            ),
        }
    }
    // [FOCSQ] TURN-IP, увиденные до перехвата, исключаются немедленно
    apply_deferred_excludes(&gateway, &interface);

    // 5. [FOCSQ] DNS туннеля больше НЕ исключается наружу: запросы к
    // TUNCONF-DNS уходят через VPN-сервер (как на Android-клиенте) —
    // резолв идёт с выхода сервера, и сайты, блокируемые на уровне DNS
    // провайдера, открываются. Наружу исключаем только системные DNS
    // (роутер/провайдер): приватные адреса из сети пира недостижимы, а
    // «сырые» прямые запросы отдельных приложений пусть не рвутся. Параллельные
    // запросы резолвера к ним подавляет NRPT-правило (см. set_nrpt_rule).
    let mut dns_excludes = system_dns_servers();
    dns_excludes.sort();
    dns_excludes.dedup();

    // Запоминаем, что чистить при остановке (маршруты 0/1 и 128/1 умирают
    // вместе с адаптером, но подстрахуемся)
    let mut pending = cleanup_routes().lock().unwrap();
    pending.clear();
    pending.extend([
        format!("0.0.0.0 mask {HALF_MASK}"),
        format!("128.0.0.0 mask {HALF_MASK}"),
    ]);
    // [FOCSQ] Подсети VK чистятся при остановке
    pending.extend(cidr_cleanup);
    for server in &dns_excludes {
        if server.parse::<Ipv4Addr>().is_err() {
            continue;
        }
        // [FOCSQ] Шлюз часто совпадает с DNS (роутер даёт и DHCP, и DNS).
        // Раньше такой сервер пропускался — и после перехвата 0/1+128/1
        // трафик к роутеру (вместе со ВСЕЙ системной резолюцией) уходил
        // в туннель. /32 до самого шлюза через себя валиден: это стандартная
        // практика pin'а шлюза (так делает WireGuard для своего эндпоинта).
        if add_exclude_route(server, &gateway, &interface, "DNS") {
            pending.push(format!("{server} mask {HOST_MASK}"));
        }
    }
    drop(pending);

    // [FOCSQ] DNS на адаптер — последним шагом, когда ВСЕ exclude-маршруты
    // уже стоят. Резолвер переключаем на TUNCONF-DNS (1.1.1.1): его трафик
    // идёт через туннель (шаг 5), так что резолюция выполняется с выхода
    // VPN-сервера. Окно флапа отсутствует — маршруты уже живые.
    set_dns(luid_to_guid(luid)?, &dns_servers)?;

    // [FOCSQ] NRPT подавляет параллельные запросы к DNS физического
    // адаптера (роутер отвечает быстрее и перебивает туннельный ответ).
    set_nrpt_rule(&dns_servers[0]);

    crate::log_error!(
        "[TUN] Настроен: ip={ip}/24 gw={gateway_tun} dns={dns} | исходный шлюз {gateway} (iface {interface}), пир {peer_ip} — через туннель, dns-exclude {} шт",
        dns_excludes.len()
    );
    Ok(())
}

/// [FOCSQ] NRPT-правило (Name Resolution Policy Table) на время сессии:
/// Namespace "." перехватывает ВСЕ запросы системного резолвера и направляет
/// их в DNS туннеля. Без него Windows (Smart Multi-Homed Name Resolution)
/// опрашивает DNS всех интерфейсов параллельно и берёт самый быстрый ответ —
/// роутер отвечает за миллисекунды, и резолюция фактически идёт мимо туннеля
/// к провайдеру. Требуются права администратора (приложение уже elevated).
fn set_nrpt_rule(dns_server: &str) {
    // Стейл-правила прошлых сессий (крэш без teardown) снимаем заранее —
    // вместе со всеми накопленными дубликатами namespace "."
    remove_nrpt_rule();
    match run_cmd(
        "powershell",
        &[
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            &format!(
                "Add-DnsClientNrptRule -Namespace '.' -NameServers '{dns_server}'"
            ),
        ],
    ) {
        Ok(_) => {
            crate::log_error!("[TUN] NRPT: системный резолвер направлен в {dns_server} (через туннель)")
        }
        Err(error) => crate::log_error!(
            "[TUN] NRPT-правило не создано — резолюция пойдёт по интерфейсам: {error}"
        ),
    }
}

/// [FOCSQ] Снять ВСЕ NRPT-правила с namespace "." (и текущее, и стейл
/// прошлых сессий).
///
/// ВАЖНО: у Remove-DnsClientNrptRule параметр называется -Name, а НЕ
/// -Namespace (у Add — именно -Namespace). Старый вариант
/// `Remove- -Namespace '.'` падал с «не удаётся найти параметр», ошибка
/// глоталась — и правила накапливались: 38 стейл-копий за 38 сессий.
/// Пайп через Get-DnsClientNrptRule удаляет всё совпадающее разом.
fn remove_nrpt_rule() {
    match run_cmd(
        "powershell",
        &[
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-DnsClientNrptRule | Where-Object {$_.Namespace -eq '.'} \
             | Remove-DnsClientNrptRule -Force",
        ],
    ) {
        Ok(_) => {}
        Err(error) => {
            crate::log_error!("[TUN] NRPT: снятие правил не удалось: {error}");
        }
    }
    // Контроль остатка: стейл-правила при выключенном приложении уводят
    // ВЕСЬ системный DNS в 1.1.1.1 напрямую — если провайдер когда-нибудь
    // начнёт его блокировать, DNS умрёт во всей системе
    if let Ok(output) = run_cmd(
        "powershell",
        &[
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-DnsClientNrptRule | Measure-Object).Count",
        ],
    ) {
        let residual = output.trim().parse::<u32>().unwrap_or(0);
        if residual > 0 {
            crate::log_error!(
                "[TUN] NRPT: в системе осталось {residual} посторонних правил (не наши — не трогаем)"
            );
        }
    }
}

/// [FOCSQ] Динамический exclude-маршрут для хоста, чей трафик обязан идти
/// мимо туннеля (TURN-серверы: и STUN :19302, и relay-порты на том же IP).
/// До apply_tunconf маршрут добавить некуда — IP просто запоминается и
/// исключается сразу после перехвата. Повторные вызовы дедупятся.
pub fn exclude_host_ip(ip: IpAddr) {
    // Перехватывается только IPv4 (half-routes 0/1+128/1 — IPv4)
    let IpAddr::V4(v4) = ip else { return };
    let destination = v4.to_string();
    let mut seen = dynamic_excludes().lock().unwrap();
    if !seen.insert(destination.clone()) {
        return;
    }
    drop(seen);
    let Some((gateway, interface)) = GATEWAY_ROUTE.lock().unwrap().clone() else {
        crate::log_error!("[TUN] TURN {destination}: перехвата ещё нет, исключим при TUNCONF");
        return;
    };
    if add_exclude_route(&destination, &gateway, &interface, "TURN") {
        cleanup_routes()
            .lock()
            .unwrap()
            .push(format!("{destination} mask {HOST_MASK}"));
        crate::log_error!("[TUN] Exclude-маршрут TURN {destination} добавлен");
    }
}

/// [FOCSQ] Применить отложенные exclude-маршруты (IP, увиденные до
/// apply_tunconf). Вызывается сразу после перехвата half-routes.
fn apply_deferred_excludes(gateway: &str, interface: &str) {
    let pending: Vec<String> = dynamic_excludes().lock().unwrap().iter().cloned().collect();
    for destination in pending {
        if add_exclude_route(&destination, &gateway, &interface, "TURN") {
            cleanup_routes()
                .lock()
                .unwrap()
                .push(format!("{destination} mask {HOST_MASK}"));
            crate::log_error!("[TUN] Отложенный Exclude-маршрут TURN {destination} добавлен");
        }
    }
}

/// Снять маршруты, добавленные apply_tunconf. Вызывается при остановке клиента.
pub fn remove_routes() {
    // [FOCSQ] NRPT-правило сессии больше не должно перехватывать резолюцию
    remove_nrpt_rule();
    let entries: Vec<String> = {
        let mut pending = cleanup_routes().lock().unwrap();
        std::mem::take(&mut *pending)
    };
    // [FOCSQ] Параллельное удаление: ~25 записей по одному route.exe
    // занимали 5-15 секунд, одновременно — меньше секунды.
    std::thread::scope(|scope| {
        for entry in &entries {
            scope.spawn(|| route_delete(entry));
        }
    });
    // [FOCSQ] Динамические исключения больше не действительны
    *GATEWAY_ROUTE.lock().unwrap() = None;
    dynamic_excludes().lock().unwrap().clear();
}

/// [FOCSQ] Мгновенное опускание интерфейса при закрытии: гасим сессию и
/// выгружаем адаптер в фоновом потоке (маршруты интерфейса исчезают вместе
/// с ним), не дожидаясь остановки воркеров. Не блокирует вызывающего.
pub fn drop_interface_now() {
    let device = DEVICE.lock().unwrap().take();
    *ADAPTER.lock().unwrap() = None;
    if let Some(device) = device {
        device.interrupt();
        std::thread::spawn(move || {
            WinTunDevice::wait_for_reader_release(&device);
            drop(device);
            crate::log_error!("[TUN] Интерфейс опущен (быстрое закрытие)");
        });
    }
}

/// Полный teardown при остановке: маршруты + удаление адаптера из системы.
/// Адаптер исчезает из «Сетевых подключений», как у WireGuard/PWDTT.
pub fn teardown() {
    remove_routes();
    if let Some(device) = DEVICE.lock().unwrap().take() {
        // Прерываем блокирующее чтение reader-потока (на случай гонки с cancel)
        device.interrupt();
        WinTunDevice::wait_for_reader_release(&device);
        drop(device);
    }
    *ADAPTER.lock().unwrap() = None;
    crate::log_error!("[TUN] Адаптер удалён");
}
