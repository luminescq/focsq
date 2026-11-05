// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

//! [FOCSQ] Правила split tunnelling выбирают путь отдельного соединения.
//! Публичные маршруты остаются прежними: IP общего CDN не исключается для всех приложений.

use anyhow::{Result, bail};
use serde::Deserialize;
use std::net::{Ipv4Addr, SocketAddrV4};

const MAX_RULES: usize = 1024;
const MAX_CONFIG_BYTES: usize = 512 * 1024;

// [FOCSQ] Выключенная категория сохраняет оба списка и не влияет на маршрутизацию.
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    #[default]
    Off,
    Include,
    Exclude,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Rules {
    pub mode: Mode,
    pub include: Vec<String>,
    pub exclude: Vec<String>,
}

impl Rules {
    fn selected(&self) -> &[String] {
        match self.mode {
            Mode::Include => &self.include,
            Mode::Exclude => &self.exclude,
            Mode::Off => &[],
        }
    }
}

// [FOCSQ] JSON имеет явную версию, чтобы новая сборка не переосмыслила старые правила.
#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub version: u32,
    pub apps: Rules,
    pub domains: Rules,
    pub ips: Rules,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            version: 1,
            apps: Rules::default(),
            domains: Rules::default(),
            ips: Rules::default(),
        }
    }
}

impl Config {
    pub fn parse(json: &str) -> Result<Self> {
        if json.len() > MAX_CONFIG_BYTES {
            bail!("Split tunnelling: настройки больше 512 КиБ");
        }
        let mut config: Self = if json.trim().is_empty() {
            Self::default()
        } else {
            serde_json::from_str(json)
                .map_err(|_| anyhow::anyhow!("Split tunnelling: повреждён JSON настроек"))?
        };
        if config.version != 1 {
            bail!("Split tunnelling: неподдерживаемая версия настроек");
        }
        for (name, rules) in [
            ("приложения", &config.apps),
            ("домены", &config.domains),
            ("IP", &config.ips),
        ] {
            if rules.include.len() > MAX_RULES || rules.exclude.len() > MAX_RULES {
                bail!("Split tunnelling: {name}, максимум {MAX_RULES} правил в списке");
            }
            if rules.mode == Mode::Include && rules.include.is_empty() {
                bail!("Split tunnelling: {name}, заполните список «Только выбранное»");
            }
        }
        // [FOCSQ] Проверяем и неактивные списки: переключение режима не активирует мусор.
        for value in config
            .apps
            .include
            .iter_mut()
            .chain(config.apps.exclude.iter_mut())
        {
            *value = normalize_app(value)?;
        }
        for value in config
            .domains
            .include
            .iter_mut()
            .chain(config.domains.exclude.iter_mut())
        {
            *value = normalize_domain(value)?;
        }
        for value in config.ips.include.iter().chain(&config.ips.exclude) {
            IpRange::parse(value)?;
        }
        Ok(config)
    }

    pub fn enabled(&self) -> bool {
        [self.apps.mode, self.domains.mode, self.ips.mode]
            .iter()
            .any(|mode| *mode != Mode::Off)
    }

    // [FOCSQ] Android сначала ограничивает приложения, затем применяет доменные/IP правила.
    pub fn decide(
        &self,
        app: Option<&str>,
        ip: Ipv4Addr,
        domains: &[String],
        dns_query: bool,
    ) -> Decision {
        if !dns_query && self.apps.mode != Mode::Off {
            let Some(app) = app else {
                return if self.apps.mode == Mode::Include {
                    Decision::Block
                } else {
                    Decision::Tunnel
                };
            };
            let app = app.replace('/', "\\").to_lowercase();
            let selected = self.apps.selected().iter().any(|value| *value == app);
            if (self.apps.mode == Mode::Include && !selected)
                || (self.apps.mode == Mode::Exclude && selected)
            {
                return Decision::Direct;
            }
        }
        let domain_match = domains.iter().any(|domain| {
            self.domains
                .selected()
                .iter()
                .any(|rule| domain_matches(domain, rule))
        });
        let ip_match = self
            .ips
            .selected()
            .iter()
            .any(|value| IpRange::parse(value).is_ok_and(|range| range.contains(ip)));
        if (self.domains.mode == Mode::Exclude && domain_match)
            || (self.ips.mode == Mode::Exclude && ip_match)
        {
            return Decision::Direct;
        }
        if (self.domains.mode == Mode::Include && domain_match)
            || (self.ips.mode == Mode::Include && ip_match)
        {
            return Decision::Tunnel;
        }
        if self.domains.mode == Mode::Include || self.ips.mode == Mode::Include {
            Decision::Direct
        } else {
            Decision::Tunnel
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    Tunnel,
    Direct,
    Block,
}

fn normalize_app(value: &str) -> Result<String> {
    let value = value.trim().replace('/', "\\");
    let bytes = value.as_bytes();
    let absolute = value.starts_with("\\\\")
        || (bytes.len() >= 3
            && bytes[0].is_ascii_alphabetic()
            && bytes[1] == b':'
            && bytes[2] == b'\\');
    if !absolute
        || !value.to_lowercase().ends_with(".exe")
        || value.chars().any(|ch| ch.is_control() || ch == '"')
    {
        bail!("Split tunnelling: укажите полный путь к EXE приложения");
    }
    Ok(value.to_lowercase())
}

fn normalize_domain(value: &str) -> Result<String> {
    let value = value.trim().trim_end_matches('.').trim_start_matches("*.");
    if value.is_empty() || value.contains(['/', ':', '@', '*', ' ']) {
        bail!("Split tunnelling: требуется домен без URL и порта");
    }
    let domain = match url::Host::parse(value) {
        Ok(url::Host::Domain(domain)) => domain.to_lowercase(),
        _ => bail!("Split tunnelling: некорректный домен"),
    };
    if domain.len() > 253
        || domain.split('.').any(|label| {
            label.is_empty()
                || label.len() > 63
                || label.starts_with('-')
                || label.ends_with('-')
                || !label
                    .bytes()
                    .all(|ch| ch.is_ascii_alphanumeric() || ch == b'-')
        })
    {
        bail!("Split tunnelling: некорректный домен");
    }
    Ok(domain)
}

fn domain_matches(domain: &str, rule: &str) -> bool {
    domain == rule
        || domain
            .strip_suffix(rule)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

// [FOCSQ] Диапазоны храним численно; /0 и /32 обрабатываются без сдвига на 32 бита.
#[derive(Clone, Copy, Debug)]
struct IpRange {
    first: u32,
    last: u32,
}
impl IpRange {
    fn parse(value: &str) -> Result<Self> {
        let address = |text: &str| {
            text.trim()
                .parse::<Ipv4Addr>()
                .map(u32::from)
                .map_err(|_| anyhow::anyhow!("Split tunnelling: некорректный IPv4"))
        };
        if let Some((first, last)) = value.split_once('-') {
            let (first, last) = (address(first)?, address(last)?);
            if first > last {
                bail!("Split tunnelling: начало диапазона IP больше конца");
            }
            return Ok(Self { first, last });
        }
        if let Some((ip, prefix)) = value.split_once('/') {
            let ip = address(ip)?;
            let prefix = prefix
                .trim()
                .parse::<u32>()
                .ok()
                .filter(|prefix| *prefix <= 32)
                .ok_or_else(|| anyhow::anyhow!("Split tunnelling: маска IPv4 должна быть 0–32"))?;
            let mask = if prefix == 0 {
                0
            } else {
                u32::MAX << (32 - prefix)
            };
            return Ok(Self {
                first: ip & mask,
                last: ip | !mask,
            });
        }
        let ip = address(value)?;
        Ok(Self {
            first: ip,
            last: ip,
        })
    }
    fn contains(self, ip: Ipv4Addr) -> bool {
        (self.first..=self.last).contains(&u32::from(ip))
    }
}

// [FOCSQ] Проверка длины и фрагментов предшествует чтению портов из недоверенного пакета.
#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq)]
pub struct Flow {
    pub protocol: u8,
    pub source: SocketAddrV4,
    pub destination: SocketAddrV4,
}
pub struct PacketView<'a> {
    pub flow: Flow,
    pub payload: &'a [u8],
    pub syn: bool,
    pub closing: bool,
}
impl<'a> PacketView<'a> {
    pub fn parse(packet: &'a [u8]) -> Option<Self> {
        if packet.len() < 20 || packet[0] >> 4 != 4 {
            return None;
        }
        let ihl = usize::from(packet[0] & 15) * 4;
        let end = usize::from(u16::from_be_bytes([packet[2], packet[3]]));
        if ihl < 20 || end < ihl || end > packet.len() || packet[6] & 0x3f != 0 || packet[7] != 0 {
            return None;
        }
        let transport = packet.get(ihl..end)?;
        if transport.len() < 8 {
            return None;
        }
        let source = SocketAddrV4::new(
            Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]),
            u16::from_be_bytes([transport[0], transport[1]]),
        );
        let destination = SocketAddrV4::new(
            Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]),
            u16::from_be_bytes([transport[2], transport[3]]),
        );
        let (payload, syn, closing) = match packet[9] {
            6 if transport.len() >= 20 => {
                let offset = usize::from(transport[12] >> 4) * 4;
                if offset < 20 {
                    return None;
                }
                (
                    transport.get(offset..)?,
                    transport[13] & 0x12 == 0x02,
                    transport[13] & 0x05 != 0,
                )
            }
            17 => {
                let length = usize::from(u16::from_be_bytes([transport[4], transport[5]]));
                if length < 8 {
                    return None;
                }
                (transport.get(8..length)?, false, false)
            }
            _ => return None,
        };
        Some(Self {
            flow: Flow {
                protocol: packet[9],
                source,
                destination,
            },
            payload,
            syn,
            closing,
        })
    }
}

// [FOCSQ] DNS parser следует compression pointers с лимитом, не доверяя длинам ответа.
fn dns_name(bytes: &[u8], cursor: &mut usize) -> Option<String> {
    let mut position = *cursor;
    let mut jumped = false;
    let mut labels = Vec::new();
    for _ in 0..128 {
        let length = *bytes.get(position)?;
        if length & 0xc0 == 0xc0 {
            let next = *bytes.get(position + 1)?;
            if !jumped {
                *cursor = position + 2;
                jumped = true;
            }
            let target = (usize::from(length & 63) << 8) | usize::from(next);
            if target >= position {
                return None;
            }
            position = target;
        } else if length == 0 {
            if !jumped {
                *cursor = position + 1;
            }
            let name = labels.join(".");
            return (name.len() <= 253 && !name.is_empty()).then_some(name);
        } else {
            if length > 63 {
                return None;
            }
            position += 1;
            let label =
                std::str::from_utf8(bytes.get(position..position + usize::from(length))?).ok()?;
            if !label
                .bytes()
                .all(|ch| ch.is_ascii_alphanumeric() || ch == b'-' || ch == b'_')
            {
                return None;
            }
            labels.push(label.to_ascii_lowercase());
            position += usize::from(length);
        }
    }
    None
}

struct DnsInfo {
    domain: String,
    response: bool,
    addresses: Vec<(Ipv4Addr, u32)>,
}
fn dns_info(bytes: &[u8]) -> Option<DnsInfo> {
    if bytes.len() < 12 || u16::from_be_bytes([bytes[4], bytes[5]]) != 1 {
        return None;
    }
    let flags = u16::from_be_bytes([bytes[2], bytes[3]]);
    if flags & 0x020f != 0 {
        return None;
    }
    let mut cursor = 12;
    let domain = dns_name(bytes, &mut cursor)?;
    cursor = cursor.checked_add(4)?;
    if cursor > bytes.len() {
        return None;
    }
    let response = flags & 0x8000 != 0;
    let mut addresses = Vec::new();
    if response {
        let answers = u16::from_be_bytes([bytes[6], bytes[7]]);
        for _ in 0..answers.min(256) {
            dns_name(bytes, &mut cursor)?;
            let header = bytes.get(cursor..cursor + 10)?;
            let kind = u16::from_be_bytes([header[0], header[1]]);
            let class = u16::from_be_bytes([header[2], header[3]]);
            let ttl = u32::from_be_bytes(header[4..8].try_into().ok()?);
            let length = usize::from(u16::from_be_bytes([header[8], header[9]]));
            cursor += 10;
            let data = bytes.get(cursor..cursor + length)?;
            if kind == 1 && class == 1 && length == 4 && ttl > 0 {
                addresses.push((
                    Ipv4Addr::new(data[0], data[1], data[2], data[3]),
                    ttl.min(3600),
                ));
            }
            cursor += length;
        }
    }
    Some(DnsInfo {
        domain,
        response,
        addresses,
    })
}

#[cfg(windows)]
mod runtime {
    use super::*;
    use crate::{
        packet::{PacketBuf, PacketPool},
        split_relay::DirectRelay,
    };
    use std::{
        collections::HashMap,
        sync::{
            Arc, Mutex,
            atomic::{AtomicU64, Ordering},
        },
        time::{Duration, Instant},
    };
    use tokio::sync::mpsc;
    use tokio_util::sync::CancellationToken;

    const MAX_FLOWS: usize = 8192;
    const MAX_DNS_IPS: usize = 8192;

    // [FOCSQ] Закрытые TCP быстро освобождают бюджет; UDP не наследует путь чужого EXE.
    struct CachedFlow {
        decision: Decision,
        expiry: Instant,
        initial: Option<u32>,
        app: Option<String>,
        closing: bool,
    }
    impl CachedFlow {
        fn refresh(
            &mut self,
            now: Instant,
            initial: Option<u32>,
            app: Option<&str>,
            verify_app: bool,
            closing: bool,
            lifetime: Duration,
        ) -> Option<Decision> {
            if self.expiry <= now
                || (initial.is_some() && self.initial != initial)
                || (verify_app && self.app.as_deref() != app)
            {
                return None;
            }
            self.closing |= closing;
            self.expiry = now
                + if self.closing {
                    Duration::from_secs(30)
                } else {
                    lifetime
                };
            Some(self.decision)
        }
    }

    // [FOCSQ] Кеш принадлежит сессии. Путь TCP закреплён до конца потока, даже при смене DNS.
    pub struct Router {
        config: Config,
        flows: Mutex<HashMap<Flow, CachedFlow>>,
        names: Mutex<HashMap<Ipv4Addr, Vec<(String, Instant)>>>,
        relay: DirectRelay,
        system_dns: Vec<Ipv4Addr>,
        decisions: [AtomicU64; 3],
        cancel: CancellationToken,
    }

    impl Router {
        pub fn new(
            config: Config,
            pool: Arc<PacketPool>,
            cancel: CancellationToken,
        ) -> std::io::Result<(Arc<Self>, mpsc::Receiver<PacketBuf>)> {
            let cancel = cancel.child_token();
            let (inbound, receiver) = mpsc::channel(128);
            let relay = DirectRelay::new(pool, inbound, cancel.clone())?;
            let router = Arc::new(Self {
                config,
                flows: Mutex::new(HashMap::new()),
                names: Mutex::new(HashMap::new()),
                relay,
                system_dns: crate::split_windows::direct_dns_servers(),
                decisions: std::array::from_fn(|_| AtomicU64::new(0)),
                cancel,
            });
            crate::log_error!(
                "[SPLIT] IPv4 TCP/UDP: apps={:?}, domains={:?}, ips={:?}; правила применены к новой сессии",
                router.config.apps.mode,
                router.config.domains.mode,
                router.config.ips.mode
            );
            Ok((router, receiver))
        }

        // [FOCSQ] Повторный lookup обновляет соответствия даже при долгом кеше DNS Windows.
        pub fn start_dns_refresh(self: &Arc<Self>) {
            if self.config.domains.mode == Mode::Off {
                return;
            }
            let weak = Arc::downgrade(self);
            let cancel = self.cancel.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        biased;
                        _ = cancel.cancelled() => break,
                        _ = tokio::time::sleep(Duration::from_secs(30)) => {},
                    }
                    let Some(router) = weak.upgrade() else {
                        break;
                    };
                    router.warm_dns(&cancel).await;
                }
            });
        }

        // [FOCSQ] Прогреваем выбранные домены до TUN: кеш Windows может не повторить DNS-запрос.
        // Общий лимит времени и параллелизма не дают большому списку задержать подключение.
        pub async fn warm_dns(&self, cancel: &CancellationToken) {
            if self.config.domains.mode == Mode::Off {
                return;
            }
            let mut pending = self.config.domains.selected().iter();
            let mut tasks = tokio::task::JoinSet::new();
            let deadline = tokio::time::sleep(Duration::from_secs(3));
            tokio::pin!(deadline);
            loop {
                while tasks.len() < 16 {
                    let Some(domain) = pending.next() else {
                        break;
                    };
                    let domain = domain.clone();
                    tasks.spawn(async move {
                        let ips = tokio::net::lookup_host((domain.as_str(), 443))
                            .await
                            .map(|addrs| {
                                addrs
                                    .filter_map(|addr| match addr.ip() {
                                        std::net::IpAddr::V4(ip) => Some(ip),
                                        _ => None,
                                    })
                                    .collect::<Vec<_>>()
                            })
                            .unwrap_or_default();
                        (domain, ips)
                    });
                }
                if tasks.is_empty() {
                    break;
                }
                let next = tokio::select! {
                    biased;
                    _ = cancel.cancelled() => break,
                    _ = &mut deadline => break,
                    next = tasks.join_next() => next,
                };
                if let Some(Ok((domain, ips))) = next {
                    let mut names = self.names.lock().unwrap();
                    for ip in ips {
                        if names.len() >= MAX_DNS_IPS && !names.contains_key(&ip) {
                            continue;
                        }
                        let values = names.entry(ip).or_default();
                        if values.len() < 8 {
                            values.push((domain.clone(), Instant::now() + Duration::from_secs(60)));
                        }
                    }
                }
            }
        }

        pub fn observe_inbound(&self, packet: &[u8]) {
            let Some(view) = PacketView::parse(packet) else {
                return;
            };
            if view.closing {
                let reversed = Flow {
                    protocol: view.flow.protocol,
                    source: view.flow.destination,
                    destination: view.flow.source,
                };
                if let Some(flow) = self.flows.lock().unwrap().get_mut(&reversed) {
                    flow.closing = true;
                    flow.expiry = Instant::now() + Duration::from_secs(30);
                }
            }
            if view.flow.source.port() != 53 {
                return;
            }
            // [FOCSQ] TCP DNS с целым length-prefixed сообщением тоже наполняет доменный кеш.
            let dns = if view.flow.protocol == 6 {
                let Some(length) = view.payload.get(..2) else {
                    return;
                };
                let length = usize::from(u16::from_be_bytes([length[0], length[1]]));
                let Some(message) = view.payload.get(2..2 + length) else {
                    return;
                };
                message
            } else {
                view.payload
            };
            let Some(info) = dns_info(dns).filter(|info| info.response) else {
                return;
            };
            let now = Instant::now();
            let mut names = self.names.lock().unwrap();
            // [FOCSQ] TTL и общий лимит не позволяют DNS-трафику бесконечно наращивать кеш.
            names.retain(|_, values| {
                values.retain(|(_, expiry)| *expiry > now);
                !values.is_empty()
            });
            for (ip, ttl) in info.addresses {
                if !names.contains_key(&ip) && names.len() >= MAX_DNS_IPS {
                    continue;
                }
                let values = names.entry(ip).or_default();
                values.retain(|(domain, _)| *domain != info.domain);
                if values.len() >= 8 {
                    values.remove(0);
                }
                values.push((
                    info.domain.clone(),
                    now + Duration::from_secs(u64::from(ttl)),
                ));
            }
        }

        pub async fn route_outbound(&self, packet: &[u8]) -> bool {
            let Some(view) = PacketView::parse(packet) else {
                // [FOCSQ] Фрагмент нельзя приписать приложению по портам: не отправляем его неверным путём.
                return packet.len() >= 20
                    && packet[0] >> 4 == 4
                    && (packet[6] & 0x3f != 0 || packet[7] != 0);
            };
            let query = (view.flow.protocol == 17 && view.flow.destination.port() == 53)
                .then(|| dns_info(view.payload))
                .flatten()
                .filter(|info| !info.response);
            let now = Instant::now();
            // [FOCSQ] Новый SYN с другим ISN переиспользует tuple, но является новым соединением.
            let syn_sequence = view.syn.then(|| {
                u32::from_be_bytes(
                    packet
                        [usize::from(packet[0] & 15) * 4 + 4..usize::from(packet[0] & 15) * 4 + 8]
                        .try_into()
                        .unwrap(),
                )
            });
            let lifetime = Duration::from_secs(if view.flow.protocol == 6 {
                24 * 3600
            } else {
                120
            });
            let verify_udp_app =
                query.is_none() && view.flow.protocol == 17 && self.config.apps.mode != Mode::Off;
            // [FOCSQ] UDP не имеет SYN: проверка владельца каждого пакета закрывает повторное использование порта другим процессом.
            let udp_app = if verify_udp_app {
                crate::split_windows::socket_owner(
                    view.flow.protocol,
                    view.flow.source,
                    view.flow.destination,
                )
            } else {
                None
            };
            let cached = if query.is_none() {
                let mut flows = self.flows.lock().unwrap();
                flows.get_mut(&view.flow).and_then(|flow| {
                    flow.refresh(
                        now,
                        syn_sequence,
                        udp_app.as_deref(),
                        verify_udp_app,
                        view.closing,
                        lifetime,
                    )
                })
            } else {
                None
            };
            let decision = if let Some(decision) = cached {
                decision
            } else {
                let domains = if let Some(info) = &query {
                    vec![info.domain.clone()]
                } else {
                    self.names
                        .lock()
                        .unwrap()
                        .get(view.flow.destination.ip())
                        .map(|values| {
                            values
                                .iter()
                                .filter(|(_, expiry)| *expiry > now)
                                .map(|(domain, _)| domain.clone())
                                .collect()
                        })
                        .unwrap_or_default()
                };
                let app = if verify_udp_app {
                    udp_app
                } else if query.is_none() && self.config.apps.mode != Mode::Off {
                    crate::split_windows::socket_owner(
                        view.flow.protocol,
                        view.flow.source,
                        view.flow.destination,
                    )
                } else {
                    None
                };
                let decision = self.config.decide(
                    app.as_deref(),
                    *view.flow.destination.ip(),
                    &domains,
                    query.is_some(),
                );
                if query.is_none() && decision != Decision::Block {
                    let mut flows = self.flows.lock().unwrap();
                    flows.retain(|_, flow| flow.expiry > now);
                    if flows.len() >= MAX_FLOWS && !flows.contains_key(&view.flow) {
                        if let Some(closed) = flows
                            .iter()
                            .filter(|(_, flow)| flow.closing)
                            .min_by_key(|(_, flow)| flow.expiry)
                            .map(|(key, _)| *key)
                        {
                            flows.remove(&closed);
                        } else {
                            return true;
                        }
                    }
                    flows.insert(
                        view.flow,
                        CachedFlow {
                            decision,
                            expiry: now
                                + if view.closing {
                                    Duration::from_secs(30)
                                } else {
                                    lifetime
                                },
                            initial: syn_sequence,
                            app,
                            closing: view.closing,
                        },
                    );
                }
                let slot = match decision {
                    Decision::Tunnel => 0,
                    Decision::Direct => 1,
                    Decision::Block => 2,
                };
                let count = self.decisions[slot].fetch_add(1, Ordering::Relaxed) + 1;
                // [FOCSQ] Только технические счётчики, без ФИО, DNS-запросов и содержимого пакета.
                if count == 1 || count.is_multiple_of(1000) {
                    if decision == Decision::Block {
                        crate::log_error!(
                            "[SPLIT] path=Block flows={count}: владелец сокета недоступен; повтор определения при следующем пакете"
                        );
                    } else {
                        crate::log_error!("[SPLIT] path={decision:?} flows={count}");
                    }
                }
                decision
            };
            match decision {
                Decision::Tunnel => false,
                Decision::Block => true,
                Decision::Direct => {
                    if query.is_some() {
                        if let Some(upstream) = self.system_dns.first() {
                            let _ = self.relay.forward_dns(packet, *upstream).await;
                            return true;
                        }
                    }
                    // [FOCSQ] Ошибка прямого подключения не переключает поток в туннель.
                    let _ = self.relay.forward(packet).await;
                    true
                }
            }
        }
    }

    impl Drop for Router {
        fn drop(&mut self) {
            self.cancel.cancel();
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn closing_flow_expires_and_new_syn_does_not_inherit_old_route() {
            let now = Instant::now();
            let mut flow = CachedFlow {
                decision: Decision::Direct,
                expiry: now + Duration::from_secs(100),
                initial: Some(1),
                app: None,
                closing: false,
            };
            assert_eq!(
                flow.refresh(now, Some(2), None, false, false, Duration::from_secs(86400)),
                None
            );
            assert_eq!(
                flow.refresh(now, None, None, false, true, Duration::from_secs(86400)),
                Some(Decision::Direct)
            );
            assert!(flow.expiry <= now + Duration::from_secs(30));
            assert_eq!(
                flow.refresh(
                    now + Duration::from_secs(31),
                    None,
                    None,
                    false,
                    false,
                    Duration::from_secs(86400)
                ),
                None
            );
        }
        #[test]
        fn reused_udp_tuple_requires_same_executable() {
            let now = Instant::now();
            let mut flow = CachedFlow {
                decision: Decision::Direct,
                expiry: now + Duration::from_secs(100),
                initial: None,
                app: Some("one.exe".into()),
                closing: false,
            };
            assert_eq!(
                flow.refresh(
                    now,
                    None,
                    Some("two.exe"),
                    true,
                    false,
                    Duration::from_secs(120)
                ),
                None
            );
            assert_eq!(
                flow.refresh(now, None, None, true, false, Duration::from_secs(120)),
                None
            );
            assert_eq!(
                flow.refresh(
                    now,
                    None,
                    Some("one.exe"),
                    true,
                    false,
                    Duration::from_secs(120)
                ),
                Some(Decision::Direct)
            );
        }
    }
}

#[cfg(windows)]
pub use runtime::Router;

#[cfg(test)]
mod tests {
    use super::*;
    fn ip(value: &str) -> Ipv4Addr {
        value.parse().unwrap()
    }
    fn config(json: &str) -> Config {
        Config::parse(json).unwrap()
    }

    #[test]
    fn defaults_preserve_full_tunnel() {
        let cfg = config("");
        assert!(!cfg.enabled());
        assert_eq!(
            cfg.decide(None, ip("1.2.3.4"), &[], false),
            Decision::Tunnel
        );
    }

    #[test]
    fn app_rules_do_not_change_other_apps_at_same_ip() {
        let cfg = config(r#"{"apps":{"mode":"exclude","exclude":["C:\\Apps\\browser.exe"]}}"#);
        assert_eq!(
            cfg.decide(Some(r"c:\APPS\browser.exe"), ip("1.2.3.4"), &[], false),
            Decision::Direct
        );
        assert_eq!(
            cfg.decide(Some(r"C:\Apps\other.exe"), ip("1.2.3.4"), &[], false),
            Decision::Tunnel
        );
        assert_eq!(
            cfg.decide(None, ip("1.2.3.4"), &[], false),
            Decision::Tunnel
        );
    }

    #[test]
    fn app_include_unknown_owner_blocks_and_dns_remains_available() {
        let cfg = config(r#"{"apps":{"mode":"include","include":["C:\\Apps\\browser.exe"]}}"#);
        assert_eq!(cfg.decide(None, ip("1.1.1.1"), &[], false), Decision::Block);
        assert_eq!(
            cfg.decide(None, ip("1.1.1.1"), &["example.org".into()], true),
            Decision::Tunnel
        );
        assert_eq!(
            cfg.decide(Some(r"C:\Apps\other.exe"), ip("1.2.3.4"), &[], false),
            Decision::Direct
        );
    }

    #[test]
    fn domain_suffix_and_exclusion_precedence() {
        let cfg = config(
            r#"{"domains":{"mode":"exclude","exclude":["Example.ORG."]},"ips":{"mode":"include","include":["1.2.3.0/24"]}}"#,
        );
        assert_eq!(
            cfg.decide(None, ip("1.2.3.4"), &["cdn.example.org".into()], false),
            Decision::Direct
        );
        assert_eq!(
            cfg.decide(None, ip("1.2.3.4"), &["badexample.org".into()], false),
            Decision::Tunnel
        );
        assert_eq!(
            cfg.decide(None, ip("2.3.4.5"), &[], false),
            Decision::Direct
        );
    }

    #[test]
    fn ip_ranges_and_includes_union() {
        let cfg = config(
            r#"{"domains":{"mode":"include","include":["example.org"]},"ips":{"mode":"include","include":["10.2.3.10-10.2.3.20"]}}"#,
        );
        assert_eq!(
            cfg.decide(None, ip("10.2.3.15"), &[], false),
            Decision::Tunnel
        );
        assert_eq!(
            cfg.decide(None, ip("10.2.3.21"), &["example.org".into()], false),
            Decision::Tunnel
        );
        assert_eq!(
            cfg.decide(None, ip("10.2.3.21"), &[], false),
            Decision::Direct
        );
        assert!(
            IpRange::parse("0.0.0.0/0")
                .unwrap()
                .contains(ip("255.255.255.255"))
        );
        assert!(
            !IpRange::parse("1.2.3.4/32")
                .unwrap()
                .contains(ip("1.2.3.5"))
        );
    }

    #[test]
    fn rejects_invalid_and_future_config() {
        for json in [
            r#"{"version":2}"#,
            r#"{"apps":{"mode":"include"}}"#,
            r#"{"ips":{"exclude":["::1"]}}"#,
            r#"{"domains":{"exclude":["https://example.org"]}}"#,
            r#"{"apps":{"exclude":["browser.exe"]}}"#,
            r#"{"ips":{"include":["1.2.3.4/33"]}}"#,
        ] {
            assert!(Config::parse(json).is_err(), "{json}");
        }
    }

    #[test]
    fn dns_parser_handles_compression_ttl_and_truncation() {
        let mut query = vec![0, 1, 0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0];
        query.extend_from_slice(b"\x07example\x03org\x00\x00\x01\x00\x01");
        query
            .extend_from_slice(b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\x01\x02\x03\x04");
        let info = dns_info(&query).unwrap();
        assert!(info.response);
        assert_eq!(info.domain, "example.org");
        assert_eq!(info.addresses, vec![(ip("1.2.3.4"), 60)]);
        for length in 0..query.len() {
            assert!(dns_info(&query[..length]).is_none());
        }
        let mut looped = query.clone();
        looped[12] = 0xc0;
        looped[13] = 12;
        assert!(dns_info(&looped).is_none());
    }
}
