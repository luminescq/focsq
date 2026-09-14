// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

//! Нативный Linux TUN: `/dev/net/tun` + ioctl-адреса + netlink-маршруты.
//!
//! [FOCSQ] Сетевая модель зеркалит Windows-версию (tun_win.rs):
//!   1. исходный шлюз запоминается ДО перехвата таблицы;
//!   2. подсети VK/TURN/DNS-роутера уходят через исходный шлюз —
//!      иначе петля (пакеты до них снова попадают в туннель). Пир НЕ
//!      исключается: транспорт всегда TURN, а хостинг на пиру должен
//!      открываться через туннель;
//!   3. перехват — half-маршрутами 0/1 + 128/1: бьют любой дефолт,
//!      но оставляют место для более конкретных исключений.
//! Отличия от Windows:
//!   • интерфейс живёт ровно столько, сколько открыт fd — стейл-маршруты
//!     физически невозможны (никаких NRPT-хвостов и route.exe-накоплений);
//!   • DNS — systemd-resolved (resolvectl) с fallback на /etc/resolv.conf.
//! Маршруты — netlink RTM_NEWROUTE, адреса — ioctl SIOCSIF*: никаких
//! дочерних ip-процессов, всё нативно.

use std::{
    collections::HashSet,
    fs::File,
    net::{IpAddr, Ipv4Addr},
    os::fd::{AsRawFd, FromRawFd, OwnedFd},
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail};

/// MTU как в оригинале (Constants.Vpn.DEFAULT_MTU)
const MTU: usize = 1300;

const TUNSETIFF: libc::c_ulong = 0x4004_54ca; // _IOW('t', 202, int)
const IFF_TUN: i16 = 0x0001;
const IFF_NO_PI: i16 = 0x1000;

/// struct ifreq: ifr_name[16] + union 16 байт
const IFREQ_SIZE: usize = 32;

/// Подсети VK/TURN целиком через исходный шлюз (PWDTT-style): закрывают
/// ротацию CDN без гонок динамического резолва. Совпадает с tun_win.rs.
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

// ===========================================================================
// Разбор CIDR
// ===========================================================================

fn parse_cidr(cidr: &str) -> Result<(Ipv4Addr, u8)> {
    let (address, prefix) = cidr.split_once('/').context("CIDR без префикса")?;
    let address: Ipv4Addr = address
        .parse()
        .with_context(|| format!("некорректный IP: {address}"))?;
    let prefix: u8 = prefix
        .parse()
        .with_context(|| format!("некорректный префикс: {prefix}"))?;
    if prefix > 32 {
        bail!("префикс /{prefix} больше 32");
    }
    Ok((address, prefix))
}

fn parse_addr(text: &str) -> Result<Ipv4Addr> {
    text.parse().with_context(|| format!("некорректный IP: {text}"))
}

/// Маска префикса как u32 в «старшем-первый» представлении (255.255.255.0 → 0xFFFFFF00).
fn prefix_mask(prefix: u8) -> u32 {
    if prefix == 0 {
        0
    } else {
        u32::MAX << (32 - prefix as u32)
    }
}

// ===========================================================================
// Netlink: маршруты
// ===========================================================================

/// RTM_*-константы libc: RTM_NEWROUTE=8, NLM_F_*, NETLINK_ROUTE — есть в libc crate.
/// Последовательности netlink должны быть уникальными на сокет.
static NL_SEQUENCE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(1);

#[repr(C)]
struct NlMsgHeader {
    length: u32,
    type_: u16,
    flags: u16,
    sequence: u32,
    port: u32,
}

/// struct rtmsg (linux/route.h).
#[repr(C)]
struct RtMsg {
    family: u8,
    dst_len: u8,
    src_len: u8,
    tos: u8,
    table: u8,
    protocol: u8,
    scope: u8,
    kind: u8,
    flags: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RtAttr {
    length: u16,
    kind: u16,
}

const RTA_DST: u16 = 1;
const RTA_GATEWAY: u16 = 5;
const RTA_OIF: u16 = 4;

const RT_SCOPE_UNIVERSE: u8 = 0;
const RT_PROTOCOL_STATIC: u8 = 4;
const RTN_UNICAST: u8 = 1;
const RT_TABLE_MAIN: u8 = 254;

const NLMSG_ERROR: u16 = 2;

fn nlmsg_align(length: usize) -> usize {
    (length + 3) & !3
}

fn nl_header(length: u32, kind: u16, flags: u16) -> NlMsgHeader {
    NlMsgHeader {
        length,
        type_: kind,
        flags,
        sequence: NL_SEQUENCE.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
        port: 0,
    }
}

fn push_bytes<T>(buffer: &mut Vec<u8>, value: &T) {
    let bytes = unsafe {
        std::slice::from_raw_parts((value as *const T).cast(), std::mem::size_of::<T>())
    };
    buffer.extend_from_slice(bytes);
}

fn push_rta(buffer: &mut Vec<u8>, kind: u16, payload: &[u8]) {
    push_bytes(
        buffer,
        &RtAttr {
            length: (std::mem::size_of::<RtAttr>() + payload.len()) as u16,
            kind,
        },
    );
    buffer.extend_from_slice(payload);
    while buffer.len() % 4 != 0 {
        buffer.push(0);
    }
}

/// Открыть netlink-сокет routing-таблицы. Приём — с таймаутом SO_RCVTIMEO:
/// если ACK не пришёл за отведённое время, считаем тихий успех (успешный
/// RTM_NEWROUTE с NLM_F_ACK отвечает мгновенно; тишина дольше — аномалия).
fn open_rtnetlink() -> Result<File> {
    let descriptor =
        unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, libc::NETLINK_ROUTE) };
    if descriptor < 0 {
        return Err(std::io::Error::last_os_error()).context("netlink socket");
    }
    // 2с на приём ответа — как обычный таймаут обращения к ядру.
    let timeout = libc::timeval {
        tv_sec: 2,
        tv_usec: 0,
    };
    if unsafe {
        libc::setsockopt(
            descriptor,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            (&timeout as *const libc::timeval).cast(),
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        )
    } < 0 {
        return Err(std::io::Error::last_os_error()).context("SO_RCVTIMEO");
    }
    // SAFETY: дескриптор только что создан и ещё ничей
    let file = unsafe { File::from_raw_fd(descriptor) };
    let mut address: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
    address.nl_family = libc::AF_NETLINK as libc::sa_family_t;
    if unsafe {
        libc::bind(
            file.as_raw_fd(),
            (&mut address as *mut libc::sockaddr_nl).cast(),
            std::mem::size_of::<libc::sockaddr_nl>() as libc::socklen_t,
        )
    } < 0
    {
        return Err(std::io::Error::last_os_error()).context("netlink bind");
    }
    Ok(file)
}

/// Добавить IPv4-маршрут в главную таблицу. gateway=None — on-link
/// (для TUN-маршрутов через фиктивный шлюз .1, как WireGuard).
///
/// [FOCSQ] ensure=true: сначала удалить существующий маршрут с тем же
/// назначением (ESRCH = не было — не ошибка), затем добавить. Нужно
/// для маршрутов ЧЕРЕЗ аплинк (TURN-исключения, VK-подсети):
/// half-маршруты 0/1+128/1 исчезают вместе с TUN-интерфейсом, а эти —
/// нет и переживают teardown. Без ensure повторный коннект получал
/// EEXIST («Файл существует», живая Manjaro 2026-09-04) — и весь
/// TUNCONF падал, второй коннект не поднимался вовсе.
fn route_add(
    destination: &str,
    gateway: Option<Ipv4Addr>,
    interface: i32,
    ensure: bool,
) -> Result<()> {
    let (network, prefix) = parse_cidr(destination)?;
    if prefix == 0 {
        bail!("маршрут 0/0 не поддерживается — используйте half-маршруты 0/1 + 128/1");
    }
    if ensure {
        route_del(destination);
    }

    let mut buffer: Vec<u8> = Vec::with_capacity(128);
    // NLM_F_ACK обязателен: без него ядро на УСПЕШНЫЙ маршрут ничего не
    // отвечает, и цикл приёма навсегда зависает в recv.
    push_bytes(
        &mut buffer,
        &nl_header(
            0, // дозаполним после сборки
            libc::RTM_NEWROUTE as u16,
            (libc::NLM_F_REQUEST | libc::NLM_F_CREATE | libc::NLM_F_EXCL
                | libc::NLM_F_ACK) as u16,
        ),
    );
    push_bytes(
        &mut buffer,
        &RtMsg {
            family: libc::AF_INET as u8,
            dst_len: prefix,
            src_len: 0,
            tos: 0,
            table: RT_TABLE_MAIN,
            protocol: RT_PROTOCOL_STATIC,
            scope: if gateway.is_some() {
                RT_SCOPE_UNIVERSE
            } else {
                libc::RT_SCOPE_LINK as u8
            },
            kind: RTN_UNICAST,
            flags: 0,
        },
    );
    while buffer.len() % 4 != 0 {
        buffer.push(0);
    }

    push_rta(&mut buffer, RTA_DST, &network.octets());
    if let Some(gateway) = gateway {
        push_rta(&mut buffer, RTA_GATEWAY, &gateway.octets());
    }
    push_rta(&mut buffer, RTA_OIF, &(interface as u32).to_ne_bytes());

    let length = buffer.len() as u32;
    buffer[0..4].copy_from_slice(&length.to_ne_bytes());

    let socket = open_rtnetlink()?;
    send_all(&socket, &buffer)?;
    if let Some(error) = receive_netlink_error(&socket)? {
        bail!("route add {destination}: {error}");
    }
    Ok(())
}

/// Удалить маршрут из главной таблицы по назначению (и интерфейсу, если
/// задан). Отсутствие маршрута (ESRCH) — не ошибка: идемпотентность.
/// Ошибки только логируем: удаление — «лучшее усилие» (запускать
/// туннель нельзя держать на чистке мусора прошлой сессии).
fn route_del(destination: &str) {
    let Ok((network, prefix)) = parse_cidr(destination) else {
        return;
    };
    if prefix == 0 {
        return;
    }
    let mut buffer: Vec<u8> = Vec::with_capacity(128);
    push_bytes(
        &mut buffer,
        &nl_header(
            0,
            libc::RTM_DELROUTE as u16,
            (libc::NLM_F_REQUEST | libc::NLM_F_ACK) as u16,
        ),
    );
    push_bytes(
        &mut buffer,
        &RtMsg {
            family: libc::AF_INET as u8,
            dst_len: prefix,
            src_len: 0,
            tos: 0,
            table: RT_TABLE_MAIN,
            protocol: RT_PROTOCOL_STATIC,
            scope: libc::RT_SCOPE_NOWHERE as u8,
            kind: RTN_UNICAST,
            flags: 0,
        },
    );
    while buffer.len() % 4 != 0 {
        buffer.push(0);
    }
    push_rta(&mut buffer, RTA_DST, &network.octets());
    let length = buffer.len() as u32;
    buffer[0..4].copy_from_slice(&length.to_ne_bytes());

    let Ok(socket) = open_rtnetlink() else {
        return;
    };
    if send_all(&socket, &buffer).is_err() {
        return;
    }
    // Ответ не ждём всерьёз: даже ESRCH/ENOENT — норма (маршрута не было).
    let _ = receive_netlink_error(&socket);
}

/// Отправить буфер целиком (send может уйти частями).
fn send_all(socket: &File, buffer: &[u8]) -> Result<()> {
    let mut sent = 0;
    while sent < buffer.len() {
        let written = unsafe {
            libc::send(
                socket.as_raw_fd(),
                buffer[sent..].as_ptr().cast(),
                buffer.len() - sent,
                libc::MSG_NOSIGNAL,
            )
        };
        if written <= 0 {
            return Err(std::io::Error::last_os_error()).context("netlink send");
        }
        sent += written as usize;
    }
    Ok(())
}

/// Дождаться ответа: Ok(None) — ACK/тишина (успех), Ok(Some(text)) — ошибка ядра.
fn receive_netlink_error(socket: &File) -> Result<Option<String>> {
    let mut buffer = vec![0u8; 8192];
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        if Instant::now() > deadline {
            return Ok(None);
        }
        let received = unsafe { libc::recv(socket.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len(), 0) };
        if received < 0 {
            let error = std::io::Error::last_os_error();
            if error.kind() == std::io::ErrorKind::WouldBlock {
                continue;
            }
            return Err(error).context("netlink recv");
        }
        let length = received as usize;
        let mut offset = 0;
        while offset + std::mem::size_of::<NlMsgHeader>() <= length {
            // SAFETY: буфер выровнен по u32 (выделён Vec<u8> с выравниванием >4,
            // заголовок netlink требует 4-байтового выравнивания — соблюдено)
            let header: NlMsgHeader =
                unsafe { std::ptr::read_unaligned(buffer.as_ptr().add(offset).cast()) };
            if header.type_ == NLMSG_ERROR
                && header.length as usize >= std::mem::size_of::<NlMsgHeader>() + 4
                && offset + std::mem::size_of::<NlMsgHeader>() + 4 <= length
            {
                // struct nlmsgerr: nlmsghdr(16) + error(4)
                let code = unsafe {
                    std::ptr::read_unaligned(
                        buffer
                            .as_ptr()
                            .add(offset + std::mem::size_of::<NlMsgHeader>())
                            .cast::<i32>(),
                    )
                };
                if code == 0 {
                    return Ok(None); // чистый ACK
                }
                return Ok(Some(format!(
                    "ошибка ядра: {}",
                    std::io::Error::from_raw_os_error(-code)
                )));
            }
            offset += nlmsg_align(header.length as usize);
        }
    }
}

// ===========================================================================
// Таблица маршрутов: /proc/net/route
// ===========================================================================

/// Строка таблицы маршрутов из /proc/net/route.
struct RouteRow {
    interface: String,
    /// Сеть в сетевом порядке (u32 «старший-первый»: 10.66.67.0 → 0x0A424300)
    destination: u32,
    mask: u32,
    gateway: u32,
    /// RTF_UP=1, RTF_GATEWAY=2
    flags: u32,
}

/// Разбор /proc/net/route. Колонки: Iface Destination Gateway Flags
/// RefCnt Use Metric Mask. Числа — hex little-endian представления u32,
/// где байты IP лежат в сетевом порядке: 192.168.8.1 → «0108A8C0».
/// Переводим к «старшему-первому» через swap_bytes.
fn parse_route_rows() -> Result<Vec<RouteRow>> {
    let table = std::fs::read_to_string("/proc/net/route").context("/proc/net/route")?;
    let mut rows = Vec::new();
    for line in table.lines().skip(1) {
        let mut fields = line.split_whitespace();
        let (
            Some(interface),
            Some(destination),
            Some(gateway),
            Some(flags),
            Some(_refcnt),
            Some(_use),
            Some(_metric),
            Some(mask),
        ) = (
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
            fields.next(),
        ) else {
            continue;
        };
        let (Ok(destination), Ok(gateway), Ok(flags), Ok(mask)) = (
            u32::from_str_radix(destination, 16),
            u32::from_str_radix(gateway, 16),
            u32::from_str_radix(flags, 16),
            u32::from_str_radix(mask, 16),
        ) else {
            continue;
        };
        rows.push(RouteRow {
            interface: interface.to_owned(),
            destination: destination.swap_bytes(),
            mask: mask.swap_bytes(),
            gateway: gateway.swap_bytes(),
            flags,
        });
    }
    Ok(rows)
}

/// Исходный default-маршрут: сеть 0.0.0.0/0 через шлюз (RTF_GATEWAY).
fn find_default_gateway() -> Result<(Ipv4Addr, i32)> {
    for row in parse_route_rows()? {
        if row.destination == 0
            && row.mask == 0
            && row.flags & 2 != 0 // RTF_GATEWAY
        {
            return Ok((Ipv4Addr::from(row.gateway), interface_index(&row.interface)?));
        }
    }
    bail!("default-маршрут не найден — нет сети?")
}

/// Подтверждение перехвата (аналог verify_half_routes в tun_win.rs):
/// маршрут стоит на ожидаемом интерфейсе с ожидаемым шлюзом.
fn route_exists(destination: &str, gateway: Ipv4Addr, interface: &str) -> Result<bool> {
    let (network, prefix) = parse_cidr(destination)?;
    let wanted_mask = prefix_mask(prefix);
    for row in parse_route_rows()? {
        if row.destination == u32::from(network)
            && row.mask == wanted_mask
            && row.interface == interface
            && Ipv4Addr::from(row.gateway) == gateway
        {
            return Ok(true);
        }
    }
    Ok(false)
}

// ===========================================================================
// Устройство
// ===========================================================================

/// Устройство TUN сессии. Интерфейс живёт, пока открыт дескриптор: drop
/// устройства = исчезновение интерфейса и всех его маршрутов из системы.
pub struct TunDevice {
    file: File,
    name: String,
}

/// Активное устройство сессии (для teardown).
static DEVICE: Mutex<Option<Arc<TunDevice>>> = Mutex::new(None);
/// Исходный шлюз и индекс интерфейса (до перехвата) — для exclude-маршрутов.
static GATEWAY_ROUTE: Mutex<Option<(Ipv4Addr, i32)>> = Mutex::new(None);
/// TURN-IP, исключённые динамически: дедуп от всех воркеров [FOCSQ]
static DYNAMIC_EXCLUDES: OnceLock<Mutex<HashSet<Ipv4Addr>>> = OnceLock::new();

/// Маршруты, добавленные нами ЧЕРЕЗ аплинк (TURN-исключения,
/// VK-подсети). В отличие от half-маршрутов 0/1+128/1, уходящих вместе
/// с TUN-интерфейсом, эти переживают teardown — убираем сами.
static UPLINK_ROUTES: OnceLock<Mutex<Vec<String>>> = OnceLock::new();

fn uplink_routes() -> &'static Mutex<Vec<String>> {
    UPLINK_ROUTES.get_or_init(|| Mutex::new(Vec::new()))
}

fn dynamic_excludes() -> &'static Mutex<HashSet<Ipv4Addr>> {
    DYNAMIC_EXCLUDES.get_or_init(|| Mutex::new(HashSet::new()))
}

impl TunDevice {
    /// Открыть /dev/net/tun и создать интерфейс (имя ≤ 15 символов, IFNAMSIZ).
    pub fn open(name: &str) -> Result<Arc<Self>> {
        if name.len() >= 16 {
            bail!("имя TUN-интерфейса {name:?} длиннее 15 символов (IFNAMSIZ)");
        }
        let descriptor = unsafe {
            libc::open(
                b"/dev/net/tun\0".as_ptr().cast(),
                libc::O_RDWR | libc::O_CLOEXEC,
            )
        };
        if descriptor < 0 {
            return Err(std::io::Error::last_os_error()).context(
                "не удалось открыть /dev/net/tun (нужен root или setcap cap_net_admin)",
            );
        }
        // SAFETY: дескриптор только что создан и ещё ничей
        let file = unsafe { File::from_raw_fd(descriptor) };

        // TUNSETIFF: ifreq { ifr_name[16], ifr_flags }
        let mut ifreq = [0u8; IFREQ_SIZE];
        ifreq[..name.len()].copy_from_slice(name.as_bytes());
        ifreq[16..18].copy_from_slice(&((IFF_TUN | IFF_NO_PI) as u16).to_ne_bytes());
        if unsafe { libc::ioctl(file.as_raw_fd(), TUNSETIFF, ifreq.as_mut_ptr()) } < 0 {
            return Err(std::io::Error::last_os_error())
                .context("TUNSETIFF не удался — нет CAP_NET_ADMIN?");
        }
        let name_end = ifreq[..16].iter().position(|byte| *byte == 0).unwrap_or(16);
        let actual_name = String::from_utf8_lossy(&ifreq[..name_end]).into_owned();

        let device = Arc::new(Self {
            file,
            name: actual_name,
        });
        *DEVICE.lock().unwrap() = Some(device.clone());
        crate::log_error!("[КЛИЕНТ] TUN-интерфейс создан: {}", device.name);
        Ok(device)
    }

    /// Имя интерфейса в системе.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Дублированный дескриптор для poll-циклов (AsyncFd). Владение
    /// устройством остаётся у TunDevice: закрытие дубля цикл не роняет.
    pub fn poll_fd(&self) -> Result<OwnedFd> {
        let copy =
            unsafe { libc::fcntl(self.file.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if copy < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        // SAFETY: дескриптор только что создан дублированием и ещё ничей
        Ok(unsafe { OwnedFd::from_raw_fd(copy) })
    }

    /// [FOCSQ] Дубликат fd как File в неблокирующем режиме — формат,
    /// который ждёт read_tun/write_tun диспетчера v2.1.9: там TUN приходит
    /// готовым File. O_NONBLOCK ставится на общее описание открытого
    /// файла (дубликаты делят его) — блокирующих читателей исходного fd
    /// нет, так что эффект безопасен.
    pub fn nonblocking_file(&self) -> Result<File> {
        use std::os::fd::{AsRawFd, FromRawFd};
        let copy = unsafe { libc::fcntl(self.file.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if copy < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let status = unsafe { libc::fcntl(copy, libc::F_GETFL) };
        if status < 0 || unsafe { libc::fcntl(copy, libc::F_SETFL, status | libc::O_NONBLOCK) } < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        // SAFETY: дублированный дескриптор, владение передаётся File
        Ok(unsafe { File::from_raw_fd(copy) })
    }
}

// ===========================================================================
// ioctl-хелперы
// ===========================================================================

fn ioctl_socket() -> Result<i32> {
    let descriptor = unsafe { libc::socket(libc::AF_INET, libc::SOCK_DGRAM | libc::SOCK_CLOEXEC, 0) };
    if descriptor < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(descriptor)
}

/// Индекс интерфейса по имени (SIOCGIFINDEX).
fn interface_index(name: &str) -> Result<i32> {
    if name.len() >= 16 {
        bail!("имя интерфейса {name:?} длиннее 15 символов");
    }
    let descriptor = ioctl_socket()?;
    let mut ifreq = [0u8; IFREQ_SIZE];
    ifreq[..name.len()].copy_from_slice(name.as_bytes());
    let result = unsafe { libc::ioctl(descriptor, libc::SIOCGIFINDEX, ifreq.as_mut_ptr()) };
    let error = std::io::Error::last_os_error();
    unsafe { libc::close(descriptor) };
    if result < 0 {
        return Err(error).context("SIOCGIFINDEX");
    }
    // ifr_ifindex лежит в начале union (offset 16)
    let index = unsafe { std::ptr::read_unaligned(ifreq[16..].as_ptr().cast::<i32>()) };
    Ok(index)
}

/// sockaddr_in байтами: family(2) + port(2) + addr(4) + pad(8).
/// sin_addr кладётся байтами IP как есть — порядок памяти (урок из
/// S_addr-бага, fixes-2026-08-30.md §1: from_ne_bytes, НЕ from_be).
fn sockaddr_in_bytes(address: Ipv4Addr) -> [u8; 16] {
    let mut bytes = [0u8; 16];
    bytes[0..2].copy_from_slice(&(libc::AF_INET as u16).to_ne_bytes());
    bytes[4..8].copy_from_slice(&address.octets());
    bytes
}

/// Адрес, маска и MTU интерфейса через SIOCSIF*.
fn set_address(name: &str, address: Ipv4Addr, mtu: usize) -> Result<()> {
    let descriptor = ioctl_socket()?;
    let result = (|| -> Result<()> {
        let mut ifreq_addr = [0u8; IFREQ_SIZE];
        ifreq_addr[..name.len()].copy_from_slice(name.as_bytes());
        let sockaddr = sockaddr_in_bytes(address);
        ifreq_addr[16..32].copy_from_slice(&sockaddr);

        let mut ifreq_mask = [0u8; IFREQ_SIZE];
        ifreq_mask[..name.len()].copy_from_slice(name.as_bytes());
        let mask = sockaddr_in_bytes(Ipv4Addr::from(prefix_mask(24)));
        ifreq_mask[16..32].copy_from_slice(&mask);

        let mut ifreq_mtu = [0u8; IFREQ_SIZE];
        ifreq_mtu[..name.len()].copy_from_slice(name.as_bytes());
        let mtu = mtu as i32;
        ifreq_mtu[16..20].copy_from_slice(&mtu.to_ne_bytes());

        for (request, ifreq, what) in [
            (libc::SIOCSIFADDR, &ifreq_addr, "адрес"),
            (libc::SIOCSIFNETMASK, &ifreq_mask, "маска"),
            (libc::SIOCSIFMTU, &ifreq_mtu, "MTU"),
        ] {
            if unsafe { libc::ioctl(descriptor, request, ifreq.as_ptr() as *mut libc::c_void) } < 0 {
                bail!("SIOCSIF-{what}: {}", std::io::Error::last_os_error());
            }
        }

        // [FOCSQ] Поднять интерфейс. SIOCSIFADDR этого НЕ делает (в
        // отличие от Windows, где Wintun-адаптер становится Up сам),
        // а маршруты через лежащий интерфейс ядро отклоняет
        // ENETUNREACH: «route add 0.0.0.0/1: ошибка ядра: Network is
        // unreachable», живая Manjaro 2026-09-03.
        let mut ifreq_flags = [0u8; IFREQ_SIZE];
        ifreq_flags[..name.len()].copy_from_slice(name.as_bytes());
        if unsafe { libc::ioctl(descriptor, libc::SIOCGIFFLAGS, ifreq_flags.as_mut_ptr()) } < 0 {
            bail!("SIOCGIFFLAGS: {}", std::io::Error::last_os_error());
        }
        // ifr_flags — начало union (offset 16)
        let mut flags = unsafe {
            std::ptr::read_unaligned(ifreq_flags[16..].as_ptr().cast::<libc::c_short>())
        };
        flags |= libc::IFF_UP as libc::c_short | libc::IFF_RUNNING as libc::c_short;
        ifreq_flags[16..18].copy_from_slice(&flags.to_ne_bytes());
        if unsafe { libc::ioctl(descriptor, libc::SIOCSIFFLAGS, ifreq_flags.as_mut_ptr()) } < 0 {
            bail!("SIOCSIFFLAGS: {}", std::io::Error::last_os_error());
        }
        Ok(())
    })();
    unsafe { libc::close(descriptor) };
    result
}

// ===========================================================================
// TUNCONF
// ===========================================================================

/// Шлюз TUN — .1 от адреса (модель WireGuard: точка-в-точку через .1).
fn tun_gateway(ip: Ipv4Addr) -> Ipv4Addr {
    let octets = ip.octets();
    Ipv4Addr::new(octets[0], octets[1], octets[2], 1)
}

/// Применить TUNCONF: адрес + перехват half-маршрутами + exclude + DNS.
/// Модель — tun_win.rs::apply_tunconf.
pub async fn apply_tunconf(ip: &str, dns: &str, peer_ip: &str) -> Result<()> {
    let address = parse_addr(ip)?;
    let device = DEVICE
        .lock()
        .unwrap()
        .clone()
        .context("TUN-устройство не создано — адрес выставить некому")?;
    let peer = parse_addr(peer_ip)?;

    let dns_servers: Vec<String> = dns
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    if dns_servers.is_empty() {
        bail!("TUNCONF не содержит DNS");
    }

    // 1. Исходный шлюз — ДО перехвата таблицы
    let (gateway, uplink_index) = find_default_gateway()?;
    *GATEWAY_ROUTE.lock().unwrap() = Some((gateway, uplink_index));
    let tun_index = interface_index(&device.name)?;
    let tun_gateway = tun_gateway(address);

    // 2. Адрес/маска/MTU на TUN-интерфейсе
    set_address(&device.name, address, MTU)?;

    // 3. Перехват. Пир НЕ исключаем: транспорт всегда TURN (клиент на
    //    IP пира напрямую не стучится — только ChannelBind через релей),
    //    а исключение выкидывало весь хостинг на пиру на прямой путь
    //    провайдера. Через туннель трафик до собственного IP сервер
    //    доставляет себе локально (loopback).
    //    Exclude — через аплинк, ensure=true: half-маршруты умирают
    //    вместе с TUN-интерфейсом, а эти переживают teardown прошлой
    //    сессии — чистим перед добавлением.
    route_add("0.0.0.0/1", Some(tun_gateway), tun_index, false)?;
    route_add("128.0.0.0/1", Some(tun_gateway), tun_index, false)?;
    if !route_exists("0.0.0.0/1", tun_gateway, &device.name)? {
        bail!("half-маршрут 0.0.0.0/1 не подтверждён в таблице маршрутов");
    }
    // [FOCSQ] Стейл peer-/32 от прошлой сессии (когда пир исключался):
    // в uplink_routes() он уже не попадает, а пережил бы teardown и мешал
    // бы хостингу на пиру ходить через туннель. route_del идемпотентен.
    route_del(&format!("{peer}/32"));

    // 4. Подсети VK/TURN через исходный шлюз (PWDTT-style)
    for cidr in VK_EXCLUDE_CIDRS {
        match route_add(cidr, Some(gateway), uplink_index, true) {
            Ok(()) => uplink_routes().lock().unwrap().push(cidr.to_string()),
            Err(error) => {
                crate::log_error!("[TUN] Exclude-подсеть VK {cidr} не добавлена: {error:#}");
            }
        }
    }
    apply_deferred_excludes();

    // 5. DNS системы — на туннельные серверы (через туннель: half-маршруты
    //    уже перехватывают их трафик, NRPT на Linux не нужен)
    set_dns(&dns_servers, &device)?;

    crate::log_error!(
        "[TUN] Настроен: ip={address}/24 gw={tun_gateway} dns={dns} | исходный шлюз {gateway} (iface {uplink_index}), пир {peer} — через туннель"
    );
    Ok(())
}

/// [FOCSQ] Динамический exclude-маршрут для TURN-хостов (мимо туннеля):
/// и STUN :19302, и relay-порты на том же IP. До apply_tunconf IP
/// запоминается и исключается сразу после перехвата.
pub fn exclude_host_ip(ip: IpAddr) {
    let IpAddr::V4(address) = ip else { return };
    if !dynamic_excludes().lock().unwrap().insert(address) {
        return;
    }
    let Some((gateway, uplink_index)) = GATEWAY_ROUTE.lock().unwrap().clone() else {
        crate::log_error!("[TUN] TURN {address}: перехвата ещё нет, исключим при TUNCONF");
        return;
    };
    match route_add(&format!("{address}/32"), Some(gateway), uplink_index, true) {
        Ok(()) => {
            uplink_routes()
                .lock()
                .unwrap()
                .push(format!("{address}/32"));
            crate::log_error!("[TUN] Exclude-маршрут TURN {address} добавлен");
        }
        Err(error) => crate::log_error!("[TUN] Exclude-маршрут TURN {address}: {error:#}"),
    }
}

/// Отложенные TURN-исключения (IP, увиденные до перехвата).
fn apply_deferred_excludes() {
    let pending: Vec<Ipv4Addr> = dynamic_excludes().lock().unwrap().iter().copied().collect();
    let Some((gateway, uplink_index)) = GATEWAY_ROUTE.lock().unwrap().clone() else {
        return;
    };
    for address in pending {
        match route_add(&format!("{address}/32"), Some(gateway), uplink_index, true) {
            Ok(()) => {
                uplink_routes()
                    .lock()
                    .unwrap()
                    .push(format!("{address}/32"));
                crate::log_error!("[TUN] Отложенный Exclude-маршрут TURN {address} добавлен")
            }
            Err(error) => crate::log_error!("[TUN] Отложенный exclude TURN {address}: {error:#}"),
        }
    }
}

/// DNS системы — на туннельные серверы. systemd-resolved (resolvectl) на
/// большинстве современных систем, прямой resolv.conf — как fallback.
fn set_dns(servers: &[String], device: &TunDevice) -> Result<()> {
    let list = servers.join(" ");
    if std::path::Path::new("/run/systemd/resolve").exists() {
        let status = std::process::Command::new("resolvectl")
            .arg("dns")
            .arg(&device.name)
            .args(servers.iter().map(String::as_str))
            .status();
        match status {
            Ok(status) if status.success() => {
                crate::log_error!("[TUN] DNS направлен в {list} (systemd-resolved)");
                return Ok(());
            }
            Ok(status) => bail!("resolvectl dns: код выхода {status}"),
            Err(error) => bail!("resolvectl недоступен: {error}"),
        }
    }
    let content = servers
        .iter()
        .map(|server| format!("nameserver {server}\n"))
        .collect::<String>();
    std::fs::write("/etc/resolv.conf", content).context("/etc/resolv.conf")?;
    crate::log_error!("[TUN] DNS направлен в {list} (/etc/resolv.conf)");
    Ok(())
}

/// Teardown: закрыть устройство — интерфейс и его маршруты исчезают
/// ядром автоматически. Системный DNS systemd-resolved вернёт сам
/// (наш интерфейс исчез из его списка).
pub fn teardown() {
    *GATEWAY_ROUTE.lock().unwrap() = None;
    dynamic_excludes().lock().unwrap().clear();
    // [FOCSQ] Аплинк-маршруты (TURN/VK-исключения) не исчезают
    // вместе с TUN-интерфейсом — снимаем сами, иначе они копятся между
    // сессиями, а повторный коннект падал на EEXIST.
    let routes: Vec<String> = uplink_routes().lock().unwrap().drain(..).collect();
    if !routes.is_empty() {
        for route in &routes {
            route_del(route);
        }
        crate::log_error!("[TUN] Снято аплинк-маршрутов: {}", routes.len());
    }
    if DEVICE.lock().unwrap().take().is_some() {
        crate::log_error!("[TUN] Интерфейс опущен (быстрое закрытие)");
    }
}

/// Мгновенное опускание интерфейса при закрытии — закрыть fd.
pub fn drop_interface_now() {
    teardown();
}
