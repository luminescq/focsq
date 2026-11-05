// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

//! [FOCSQ] Владельцы исходящих IPv4-потоков и прямые сокеты split tunnelling.
//! Правила приложений не меняют общую таблицу маршрутов Windows.

use std::{
    collections::{HashMap, HashSet},
    ffi::c_void,
    io,
    net::{Ipv4Addr, SocketAddrV4},
    os::windows::io::RawSocket,
    ptr,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
use windows_sys::{
    Win32::{
        Foundation::{
            CloseHandle, ERROR_BUFFER_OVERFLOW, ERROR_INSUFFICIENT_BUFFER,
            ERROR_NETWORK_UNREACHABLE, FILETIME, HANDLE, NO_ERROR,
        },
        NetworkManagement::{
            IpHelper::{
                FreeMibTable, GetAdaptersAddresses, GetBestRoute2, GetExtendedTcpTable,
                GetExtendedUdpTable, GetIfEntry2, GetIpForwardTable2, GetIpInterfaceEntry,
                IP_ADAPTER_ADDRESSES_LH, MIB_IF_ROW2, MIB_IPFORWARD_ROW2, MIB_IPFORWARD_TABLE2,
                MIB_IPINTERFACE_ROW, TCP_TABLE_OWNER_PID_ALL, UDP_TABLE_OWNER_PID,
            },
            Ndis::IfOperStatusUp,
        },
        Networking::WinSock::{
            AF_INET, IP_UNICAST_IF, IPPROTO_IP, SOCKADDR_IN, SOCKADDR_INET, SOCKET_ERROR,
            WSAGetLastError, setsockopt,
        },
        System::{
            ProcessStatus::K32EnumProcesses,
            Threading::{
                GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
                QueryFullProcessImageNameW,
            },
        },
    },
    core::GUID,
};

// [FOCSQ] GUID совпадает с tun_win: переименование адаптера не разрешает петлю в TUN.
const TUN_GUID: GUID = GUID::from_u128(0xC5A6_E4B1_9F2D_4E7A_B3C8_1A2B_3C4D_5E6F);
const MAX_TABLE_BYTES: usize = 16 * 1024 * 1024;
const PROCESS_CACHE_LIMIT: usize = 512;
const PROCESS_CACHE_TTL: Duration = Duration::from_secs(2);

// [FOCSQ] Чистое представление маршрута позволяет проверить выбор без прав администратора.
#[derive(Clone, Copy, Debug)]
struct RouteCandidate {
    network: Ipv4Addr,
    prefix: u8,
    interface: u32,
    metric: u64,
}

fn route_matches(route: RouteCandidate, remote: Ipv4Addr) -> bool {
    if route.prefix > 32 {
        return false;
    }
    let mask = if route.prefix == 0 {
        0
    } else {
        u32::MAX << (32 - route.prefix)
    };
    u32::from(route.network) & mask == u32::from(remote) & mask
}

fn choose_interface(routes: &[RouteCandidate], remote: Ipv4Addr) -> Option<u32> {
    // [FOCSQ] Сначала longest-prefix, затем сумма метрик маршрута и интерфейса.
    // Поэтому маршрут рабочей VPN-сети выигрывает у физического default route.
    routes
        .iter()
        .copied()
        .filter(|route| route_matches(*route, remote))
        .min_by_key(|route| (32 - route.prefix, route.metric, route.interface))
        .map(|route| route.interface)
}

fn same_guid(left: &GUID, right: &GUID) -> bool {
    left.data1 == right.data1
        && left.data2 == right.data2
        && left.data3 == right.data3
        && left.data4 == right.data4
}

fn wide_string(value: &[u16]) -> String {
    let length = value
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(value.len());
    String::from_utf16_lossy(&value[..length])
}

fn interface_metric(index: u32) -> Option<u32> {
    // [FOCSQ] Проверяем живой интерфейс и GUID, а не только изменяемое имя адаптера.
    let mut info: MIB_IF_ROW2 = unsafe { std::mem::zeroed() };
    info.InterfaceIndex = index;
    if unsafe { GetIfEntry2(&mut info) } != NO_ERROR
        || info.OperStatus != IfOperStatusUp
        || same_guid(&info.InterfaceGuid, &TUN_GUID)
        || wide_string(&info.Alias).eq_ignore_ascii_case("csqtt")
    {
        return None;
    }
    let mut ip: MIB_IPINTERFACE_ROW = unsafe { std::mem::zeroed() };
    ip.Family = AF_INET;
    ip.InterfaceIndex = index;
    if unsafe { GetIpInterfaceEntry(&mut ip) } != NO_ERROR || ip.Connected == 0 {
        return None;
    }
    Some(ip.Metric)
}

fn direct_interface(remote: Ipv4Addr) -> io::Result<u32> {
    // [FOCSQ] Снимок берём при создании потока: подключения OpenVPN и смена сети
    // учитываются без перезапуска FOCSQ; системные маршруты при этом не меняются.
    let mut table = ptr::null_mut();
    let code = unsafe { GetIpForwardTable2(AF_INET, &mut table) };
    if code != NO_ERROR {
        return Err(io::Error::from_raw_os_error(code as i32));
    }
    if table.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Windows вернула пустую таблицу маршрутов",
        ));
    }
    struct RouteTable(*mut MIB_IPFORWARD_TABLE2);
    impl Drop for RouteTable {
        fn drop(&mut self) {
            // [FOCSQ] IP Helper выделяет память сам; освобождаем её и при раннем выходе.
            unsafe { FreeMibTable(self.0.cast()) };
        }
    }
    let table = RouteTable(table);
    let count = unsafe { (*table.0).NumEntries as usize };
    if count
        > MAX_TABLE_BYTES
            / std::mem::size_of::<windows_sys::Win32::NetworkManagement::IpHelper::MIB_IPFORWARD_ROW2>(
            )
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Слишком большая таблица маршрутов",
        ));
    }
    // [FOCSQ] Используем реальное смещение Table с padding; копируем только живые строки
    // внутри выделенного Windows блока, пока его RAII-владелец остаётся в области видимости.
    let rows = unsafe {
        std::slice::from_raw_parts(
            ptr::addr_of!((*table.0).Table)
                .cast::<windows_sys::Win32::NetworkManagement::IpHelper::MIB_IPFORWARD_ROW2>(),
            count,
        )
    };
    let mut interfaces = HashMap::new();
    let mut candidates = Vec::new();
    for row in rows {
        if row.ValidLifetime == 0
            || row.InterfaceIndex == 0
            || unsafe { row.DestinationPrefix.Prefix.si_family } != AF_INET
        {
            continue;
        }
        let metric = *interfaces
            .entry(row.InterfaceIndex)
            .or_insert_with(|| interface_metric(row.InterfaceIndex));
        let Some(metric) = metric else { continue };
        let address = unsafe { row.DestinationPrefix.Prefix.Ipv4.sin_addr.S_un.S_addr };
        candidates.push(RouteCandidate {
            network: Ipv4Addr::from(address.to_ne_bytes()),
            prefix: row.DestinationPrefix.PrefixLength,
            interface: row.InterfaceIndex,
            metric: u64::from(row.Metric) + u64::from(metric),
        });
    }
    choose_interface(&candidates, remote)
        .ok_or_else(|| io::Error::from_raw_os_error(ERROR_NETWORK_UNREACHABLE as i32))
}

/// [FOCSQ] Закрепить новый/wildcard-bound IPv4-сокет за прямым интерфейсом до connect/send.
/// Выбор учитывает более точные маршруты других VPN и никогда не возвращает csqtt.
pub fn bind_direct(raw_socket: RawSocket, remote: Ipv4Addr) -> io::Result<()> {
    let interface = direct_interface(remote)?.to_be();
    // SAFETY: Windows синхронно копирует DWORD; IP_UNICAST_IF требует network byte order.
    let result = unsafe {
        setsockopt(
            raw_socket as usize,
            IPPROTO_IP,
            IP_UNICAST_IF,
            (&interface as *const u32).cast(),
            std::mem::size_of::<u32>() as i32,
        )
    };
    if result == SOCKET_ERROR {
        return Err(io::Error::from_raw_os_error(unsafe { WSAGetLastError() }));
    }
    Ok(())
}

/// [FOCSQ] DNS работающих прямых интерфейсов в порядке маршрутной/интерфейсной метрики.
/// Порядок DNS внутри одного адаптера сохраняется; отключённые адаптеры и csqtt исключены.
pub fn direct_dns_servers() -> Vec<Ipv4Addr> {
    let mut size = 15 * 1024u32;
    for _ in 0..4 {
        if size as usize > MAX_TABLE_BYTES || size == 0 {
            return Vec::new();
        }
        // [FOCSQ] API возвращает связанные структуры в одном выровненном буфере.
        let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
        size = (buffer.len() * 8) as u32;
        let code = unsafe {
            GetAdaptersAddresses(
                u32::from(AF_INET),
                0,
                ptr::null(),
                buffer.as_mut_ptr().cast(),
                &mut size,
            )
        };
        if code == ERROR_BUFFER_OVERFLOW {
            continue;
        }
        if code != NO_ERROR {
            return Vec::new();
        }
        let mut adapter = buffer.as_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        let mut candidates = Vec::new();
        let mut adapter_count = 0;
        while !adapter.is_null() && adapter_count < 1024 {
            adapter_count += 1;
            // SAFETY: все указатели принадлежат живому буферу GetAdaptersAddresses.
            let row = unsafe { &*adapter };
            let index = unsafe { row.Anonymous1.Anonymous.IfIndex };
            if let Some(metric) = interface_metric(index) {
                let mut dns = row.FirstDnsServerAddress;
                let mut preference = 0u32;
                while !dns.is_null() && preference < 64 {
                    let entry = unsafe { &*dns };
                    let socket = entry.Address.lpSockaddr;
                    if !socket.is_null()
                        && entry.Address.iSockaddrLength as usize
                            >= std::mem::size_of::<SOCKADDR_IN>()
                        && unsafe { (*socket).sa_family } == AF_INET
                    {
                        let ip = unsafe { (*socket.cast::<SOCKADDR_IN>()).sin_addr.S_un.S_addr };
                        let ip = Ipv4Addr::from(ip.to_ne_bytes());
                        if !ip.is_unspecified() && !ip.is_multicast() {
                            // [FOCSQ] Проверяем доступность DNS именно на его интерфейсе;
                            // корпоративный DNS учитывает маршрут OpenVPN к внутренней сети.
                            let mut destination: SOCKADDR_INET = unsafe { std::mem::zeroed() };
                            destination.Ipv4.sin_family = AF_INET;
                            destination.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes(ip.octets());
                            let mut route: MIB_IPFORWARD_ROW2 = unsafe { std::mem::zeroed() };
                            let mut source: SOCKADDR_INET = unsafe { std::mem::zeroed() };
                            if unsafe {
                                GetBestRoute2(
                                    ptr::null(),
                                    index,
                                    ptr::null(),
                                    &destination,
                                    0,
                                    &mut route,
                                    &mut source,
                                )
                            } == NO_ERROR
                            {
                                candidates.push((
                                    u64::from(metric) + u64::from(route.Metric),
                                    index,
                                    preference,
                                    ip,
                                ));
                            }
                        }
                    }
                    preference += 1;
                    dns = entry.Next;
                }
            }
            adapter = row.Next;
        }
        candidates.sort_by_key(|(metric, index, preference, _)| (*metric, *index, *preference));
        let mut seen = HashSet::new();
        return candidates
            .into_iter()
            .map(|(_, _, _, ip)| ip)
            .filter(|ip| seen.insert(*ip))
            .collect();
    }
    Vec::new()
}

fn socket_table(tcp: bool) -> io::Result<Vec<u8>> {
    // [FOCSQ] Буфер DWORD-таблиц имеет выравнивание u64. Повторяем получение,
    // если между запросами размера и данных Windows успела добавить соединения.
    let query = |buffer: *mut c_void, size: &mut u32| unsafe {
        if tcp {
            GetExtendedTcpTable(
                buffer,
                size,
                0,
                u32::from(AF_INET),
                TCP_TABLE_OWNER_PID_ALL,
                0,
            )
        } else {
            GetExtendedUdpTable(buffer, size, 0, u32::from(AF_INET), UDP_TABLE_OWNER_PID, 0)
        }
    };
    let mut size = 0;
    let mut code = query(ptr::null_mut(), &mut size);
    for _ in 0..4 {
        if code != ERROR_INSUFFICIENT_BUFFER && code != NO_ERROR {
            return Err(io::Error::from_raw_os_error(code as i32));
        }
        let bytes = size as usize;
        if !(4..=MAX_TABLE_BYTES).contains(&bytes) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Некорректный размер таблицы сокетов",
            ));
        }
        let mut buffer = vec![0u64; bytes.div_ceil(std::mem::size_of::<u64>())];
        size = (buffer.len() * std::mem::size_of::<u64>()) as u32;
        code = query(buffer.as_mut_ptr().cast(), &mut size);
        if code == NO_ERROR {
            if size as usize > buffer.len() * std::mem::size_of::<u64>() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Таблица сокетов вышла за буфер",
                ));
            }
            // SAFETY: инициализированный Vec<u64> содержит не меньше size байтов;
            // дальше поля читаются побайтно, без невыровненных ссылок на C-структуры.
            return Ok(unsafe {
                std::slice::from_raw_parts(buffer.as_ptr().cast(), size as usize)
            }
            .to_vec());
        }
    }
    Err(io::Error::from_raw_os_error(code as i32))
}

fn table_rows(bytes: &[u8], row_size: usize) -> Option<std::slice::ChunksExact<'_, u8>> {
    // [FOCSQ] Проверяем счётчик и границы до разбора, включая переполнение умножения.
    let count = u32::from_ne_bytes(bytes.get(..4)?.try_into().ok()?) as usize;
    let end = 4usize.checked_add(count.checked_mul(row_size)?)?;
    Some(bytes.get(4..end)?.chunks_exact(row_size))
}

fn word(row: &[u8], index: usize) -> u32 {
    u32::from_ne_bytes(row[index * 4..index * 4 + 4].try_into().unwrap())
}

fn address(word: u32) -> Ipv4Addr {
    Ipv4Addr::from(word.to_ne_bytes())
}
fn port(word: u32) -> u16 {
    u16::from_be(word as u16)
}

fn unique_pid(pids: impl Iterator<Item = u32>) -> Option<u32> {
    // [FOCSQ] Не угадываем приложение при reuse-port, PID=0 или несовпадении владельцев.
    let mut result = None;
    for pid in pids {
        if pid == 0 || result.is_some_and(|previous| previous != pid) {
            return None;
        }
        result = Some(pid);
    }
    result
}

fn owner_pid(
    protocol: u8,
    source: SocketAddrV4,
    destination: SocketAddrV4,
    bytes: &[u8],
) -> Option<u32> {
    match protocol {
        // [FOCSQ] TCP определяется полным направленным 4-tuple, а не общим адресом назначения.
        6 => unique_pid(
            table_rows(bytes, 24)?
                .filter(|row| {
                    address(word(row, 1)) == *source.ip()
                        && port(word(row, 2)) == source.port()
                        && address(word(row, 3)) == *destination.ip()
                        && port(word(row, 4)) == destination.port()
                })
                .map(|row| word(row, 5)),
        ),
        // [FOCSQ] UDP-таблица Windows не содержит удалённой стороны: wildcard-bind
        // подходит к любому локальному IPv4, но разные PID означают неизвестного владельца.
        17 => unique_pid(
            table_rows(bytes, 12)?
                .filter(|row| {
                    let local = address(word(row, 0));
                    (local == *source.ip() || local.is_unspecified())
                        && port(word(row, 1)) == source.port()
                })
                .map(|row| word(row, 2)),
        ),
        _ => None,
    }
}

// [FOCSQ] HANDLE закрывается независимо от успеха чтения EXE/времени создания процесса.
struct ProcessHandle(HANDLE);
impl Drop for ProcessHandle {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}

struct ProcessEntry {
    created: u64,
    path: String,
    fetched: Instant,
}
static PROCESS_CACHE: OnceLock<Mutex<HashMap<u32, ProcessEntry>>> = OnceLock::new();

fn process_path(pid: u32) -> Option<String> {
    let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if handle == 0 {
        return None;
    }
    let handle = ProcessHandle(handle);
    let mut created: FILETIME = unsafe { std::mem::zeroed() };
    let mut exited = created;
    let mut kernel = created;
    let mut user = created;
    if unsafe { GetProcessTimes(handle.0, &mut created, &mut exited, &mut kernel, &mut user) } == 0
    {
        return None;
    }
    let created = u64::from(created.dwHighDateTime) << 32 | u64::from(created.dwLowDateTime);
    let cache = PROCESS_CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    {
        // [FOCSQ] Проверяем время создания при каждом обращении: повторно выданный PID
        // не наследует EXE завершившегося процесса даже внутри короткого TTL.
        let cache = cache.lock().ok()?;
        if let Some(entry) = cache.get(&pid)
            && entry.created == created
            && entry.fetched.elapsed() < PROCESS_CACHE_TTL
        {
            return Some(entry.path.clone());
        }
    }
    let mut path = vec![0u16; 512];
    loop {
        let mut size = path.len() as u32;
        if unsafe { QueryFullProcessImageNameW(handle.0, 0, path.as_mut_ptr(), &mut size) } != 0 {
            let path = String::from_utf16_lossy(&path[..size as usize]);
            if path.is_empty() {
                return None;
            }
            let mut cache = cache.lock().ok()?;
            cache.retain(|_, entry| entry.fetched.elapsed() < PROCESS_CACHE_TTL);
            if cache.len() >= PROCESS_CACHE_LIMIT {
                cache.clear();
            }
            cache.insert(
                pid,
                ProcessEntry {
                    created,
                    path: path.clone(),
                    fetched: Instant::now(),
                },
            );
            return Some(path);
        }
        // [FOCSQ] Поддерживаем длинные пути Windows, ограничив максимальный размер буфера.
        if io::Error::last_os_error().raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER as i32)
            || path.len() >= 32768
        {
            return None;
        }
        path.resize((path.len() * 2).min(32768), 0);
    }
}

/// [FOCSQ] Полный Win32-путь EXE владельца TCP/UDP, либо None при гонке/неоднозначности.
pub fn socket_owner(
    protocol: u8,
    source: SocketAddrV4,
    destination: SocketAddrV4,
) -> Option<String> {
    if !matches!(protocol, 6 | 17) {
        return None;
    }
    let table = socket_table(protocol == 6).ok()?;
    process_path(owner_pid(protocol, source, destination, &table)?)
}

/// [FOCSQ] (Название EXE, полный путь) для выбора запущенных приложений в настройках.
pub fn running_apps() -> Vec<(String, String)> {
    let mut pids = vec![0u32; 1024];
    loop {
        let capacity = pids.len() * std::mem::size_of::<u32>();
        let mut used = 0;
        if unsafe { K32EnumProcesses(pids.as_mut_ptr(), capacity as u32, &mut used) } == 0 {
            return Vec::new();
        }
        if used as usize >= capacity {
            // [FOCSQ] Повторяем усечённый список; жёсткий предел защищает память GUI.
            if pids.len() >= 65536 {
                return Vec::new();
            }
            pids.resize(pids.len() * 2, 0);
            continue;
        }
        pids.truncate(used as usize / std::mem::size_of::<u32>());
        break;
    }
    let mut seen = HashSet::new();
    let mut apps: Vec<_> = pids
        .into_iter()
        .filter_map(process_path)
        .filter(|path| seen.insert(path.to_lowercase()))
        .map(|path| {
            (
                path.rsplit(['\\', '/']).next().unwrap_or(&path).to_owned(),
                path,
            )
        })
        .collect();
    apps.sort_by_cached_key(|(name, path)| (name.to_lowercase(), path.to_lowercase()));
    apps
}

#[cfg(test)]
mod tests {
    use super::*;

    fn route(network: &str, prefix: u8, interface: u32, metric: u64) -> RouteCandidate {
        RouteCandidate {
            network: network.parse().unwrap(),
            prefix,
            interface,
            metric,
        }
    }
    fn table(rows: &[&[u32]]) -> Vec<u8> {
        let mut bytes = (rows.len() as u32).to_ne_bytes().to_vec();
        for row in rows {
            for value in *row {
                bytes.extend(value.to_ne_bytes());
            }
        }
        bytes
    }
    fn ip(value: &str) -> u32 {
        u32::from_ne_bytes(value.parse::<Ipv4Addr>().unwrap().octets())
    }
    fn endpoint(value: &str) -> SocketAddrV4 {
        value.parse().unwrap()
    }
    fn wire_port(port: u16) -> u32 {
        u32::from(port.to_be())
    }

    #[test]
    fn corporate_route_beats_cheaper_default_and_partial_prefixes_match() {
        // [FOCSQ] Корпоративная /23 должна выиграть независимо от метрики default route.
        let routes = [
            route("0.0.0.0", 0, 1, 1),
            route("10.3.0.0", 23, 2, 500),
            route("128.0.0.0", 1, 3, 1),
        ];
        assert_eq!(
            choose_interface(&routes, "10.3.1.40".parse().unwrap()),
            Some(2)
        );
        assert_eq!(
            choose_interface(&routes, "10.3.2.40".parse().unwrap()),
            Some(1)
        );
        assert_eq!(
            choose_interface(&routes, "195.133.24.240".parse().unwrap()),
            Some(3)
        );
    }
    #[test]
    fn equal_prefix_uses_total_metric_and_invalid_prefix_is_ignored() {
        let routes = [
            route("10.0.0.0", 8, 2, 70),
            route("10.0.0.0", 8, 3, 20),
            route("10.0.0.0", 33, 4, 1),
        ];
        assert_eq!(
            choose_interface(&routes, "10.3.0.40".parse().unwrap()),
            Some(3)
        );
        assert_eq!(choose_interface(&routes, "8.8.8.8".parse().unwrap()), None);
    }
    #[test]
    fn tcp_owner_matches_all_endpoints_and_rejects_ambiguity() {
        let source = endpoint("10.66.67.4:55000");
        let destination = endpoint("1.1.1.1:443");
        let row = [
            5,
            ip("10.66.67.4"),
            wire_port(55000),
            ip("1.1.1.1"),
            wire_port(443),
            42,
        ];
        assert_eq!(owner_pid(6, source, destination, &table(&[&row])), Some(42));
        assert_eq!(
            owner_pid(6, source, endpoint("1.1.1.1:80"), &table(&[&row])),
            None
        );
        let mut other = row;
        other[5] = 43;
        assert_eq!(
            owner_pid(6, source, destination, &table(&[&row, &other])),
            None
        );
    }
    #[test]
    fn udp_wildcard_is_supported_and_different_pids_are_unknown() {
        let source = endpoint("10.66.67.4:55000");
        let destination = endpoint("8.8.8.8:53");
        let wildcard = [0, wire_port(55000), 42];
        let specific = [ip("10.66.67.4"), wire_port(55000), 43];
        assert_eq!(
            owner_pid(17, source, destination, &table(&[&wildcard])),
            Some(42)
        );
        assert_eq!(
            owner_pid(17, source, destination, &table(&[&wildcard, &specific])),
            None
        );
    }
    #[test]
    fn truncated_or_zero_pid_tables_do_not_guess() {
        assert!(table_rows(&[], 24).is_none());
        assert!(table_rows(&u32::MAX.to_ne_bytes(), 24).is_none());
        assert!(table_rows(&[1, 0, 0, 0, 0], 24).is_none());
        assert_eq!(unique_pid([0].into_iter()), None);
        assert_eq!(unique_pid([42, 42].into_iter()), Some(42));
    }
    #[test]
    fn live_tcp_owner_is_current_executable() {
        // [FOCSQ] Настоящая таблица Windows должна вернуть владельца локального TCP-соединения.
        use std::net::{SocketAddr, TcpListener, TcpStream};
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_accepted, _) = listener.accept().unwrap();
        let SocketAddr::V4(source) = stream.local_addr().unwrap() else {
            panic!()
        };
        let SocketAddr::V4(destination) = stream.peer_addr().unwrap() else {
            panic!()
        };
        let owner = socket_owner(6, source, destination).expect("Windows TCP owner");
        assert_eq!(
            owner.to_lowercase(),
            std::env::current_exe()
                .unwrap()
                .to_string_lossy()
                .to_lowercase()
        );
    }
    #[test]
    fn live_udp_owner_accepts_wildcard_bind() {
        use std::net::UdpSocket;
        let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
        let source = SocketAddrV4::new(Ipv4Addr::LOCALHOST, socket.local_addr().unwrap().port());
        let owner = socket_owner(17, source, endpoint("127.0.0.1:53")).expect("Windows UDP owner");
        assert_eq!(
            owner.to_lowercase(),
            std::env::current_exe()
                .unwrap()
                .to_string_lossy()
                .to_lowercase()
        );
    }

    #[test]
    fn live_ipv4_owner_accepts_dual_stack_udp_socket() {
        // [FOCSQ] Браузеры могут отправлять IPv4 через dual-stack сокет AF_INET6.
        use socket2::{Domain, Socket, Type};
        let socket = Socket::new(Domain::IPV6, Type::DGRAM, None).unwrap();
        socket.set_only_v6(false).unwrap();
        socket.bind(&"[::]:0".parse::<std::net::SocketAddr>().unwrap().into()).unwrap();
        let port = socket.local_addr().unwrap().as_socket().unwrap().port();
        let source = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
        let owner = socket_owner(17, source, endpoint("127.0.0.1:53")).expect("dual-stack UDP owner");
        assert_eq!(owner.to_lowercase(), std::env::current_exe().unwrap().to_string_lossy().to_lowercase());
    }
    #[test]
    fn direct_udp_can_reach_loopback_without_route_changes() {
        // [FOCSQ] Проверяем настоящий IP_UNICAST_IF без внешней сети и изменений маршрутов.
        use std::{net::UdpSocket, os::windows::io::AsRawSocket};
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        server
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let client = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
        bind_direct(client.as_raw_socket(), Ipv4Addr::LOCALHOST).unwrap();
        client
            .send_to(b"split-loopback", server.local_addr().unwrap())
            .unwrap();
        let mut buffer = [0u8; 32];
        let (length, _) = server.recv_from(&mut buffer).unwrap();
        assert_eq!(&buffer[..length], b"split-loopback");
    }
}
