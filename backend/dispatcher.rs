// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crate::{
    client_perf::{self, Stage as PerfStage},
    packet::{PacketBuf, PacketPool},
    stats::Stats,
    striped_scheduler::{DispatchTicket, PacketClass, packet_class},
    udp_batch,
    wire_protocol::{MAX_FRAG_PAYLOAD, MAX_PACKET_LEN, ShardedReassembler, fragment_packet},
};
use anyhow::Result;
use arc_swap::{ArcSwap, ArcSwapOption};
use crossbeam_queue::ArrayQueue;
use socket2::SockRef;
#[cfg(unix)]
use std::fs::File;
use std::{
    collections::{HashMap, HashSet},
    future::Future,
    net::{IpAddr, SocketAddr},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU16, AtomicU64, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio::sync::mpsc;
use tokio::{net::UdpSocket, sync::Notify, task::JoinHandle};
use tokio_util::sync::CancellationToken;

#[cfg(test)]
use tokio::time::Instant;

const RETURN_CAPACITY: usize = 1024;
/// [FOCSQ] Предел батча чтения аплинка из wintun: после блокирующего чтения
/// ring дренируется try_receive до этого предела, затем — снова блокирующее
/// чтение (при непустом ring оно возвращается мгновенно).
#[cfg(windows)]
const WINTUN_READ_BURST: usize = 64;
// [FOCSQ→апстрим 2.5.0] Порог шардинга аплинка: пакеты не длиннее этого
// идут в канал как есть, длиннее — нарезаются фрагментами 0xCF
// (shared/wire_protocol.rs), как у апстримного клиента.
const DIRECT_PACKET_MAX_LEN: usize = 1280;
// [PORT 2.5.0] Байты обвязки (SRTP + UDP/IP) сверх IP-пакета: апстрим
// учитывает их в token bucket, чтобы скорость потока совпадала с проводной.
const WRAP_OVERHEAD: usize = 66;

const QUEUE_ACTIVE: u64 = 1;

#[cfg(unix)]
enum TunWriteState {
    Complete,
    Continue,
    Wait,
    Backoff,
    Yield,
    Closed,
    /// [FOCSQ] Пакет отвергнут ядром (например, не является IP-пакетом) —
    /// он отбрасывается, задача записи TUN продолжает работать.
    Dropped,
}

#[inline(always)]
fn is_ip_packet(packet: &[u8]) -> bool {
    matches!(packet.first().copied().map(|byte| byte >> 4), Some(4 | 6))
}

/// [FOCSQ→апстрим 2.5.0] Даунлинк апстримных серверов ≤2.1.x мог приходить
/// с 24-байтовым префиксом кадра (наш родной CQF1); сервер 2.5.0 шлёт
/// голые IP-пакеты. Если голова не IP, но после 24 байт IP-пакет —
/// снимаем префикс, как это делает апстримный клиент.
#[inline(always)]
fn strip_legacy_downlink_prefix(packet: &mut PacketBuf) {
    const LEGACY_PREFIX_LEN: usize = 24;
    let bytes = packet.as_slice();
    if !is_ip_packet(bytes)
        && bytes.len() > LEGACY_PREFIX_LEN
        && is_ip_packet(&bytes[LEGACY_PREFIX_LEN..])
    {
        let _ = packet.trim_front(LEGACY_PREFIX_LEN);
    }
}

/// [PORT 2.5.0] Token-bucket планировщик аплинка апстримного клиента:
/// per-flow привязка (игровой UDP остаётся на одном потоке), дефицитный
/// waterfall и адаптивная скорость на поток. Congestion берётся с
/// переполнения lane-очереди выбранного воркера.
pub const TB_RATE_BYTES_PER_SEC: f64 = 247_000.0;
pub const TB_CAPACITY_BYTES: f64 = 10_500.0;
const MAX_DEFICIT: f64 = -4_500.0;

const ADAPTIVE_MIN_RATE_BYTES_PER_SEC: f64 = 150_000.0;
const ADAPTIVE_MAX_RATE_BYTES_PER_SEC: f64 = TB_RATE_BYTES_PER_SEC;
const ADAPTIVE_DECREASE_FACTOR: f64 = 0.85;
const ADAPTIVE_INCREASE_BYTES_PER_SEC: f64 = 5_000.0;
const ADAPTIVE_ADJUST_INTERVAL: Duration = Duration::from_millis(100);

const FLOW_BURST_BYTES: usize = 16 * 1024;
const FLOW_TABLE_MAX: usize = 1024;
const FLOW_EVICT_EVERY: u32 = 1024;
const FLOW_IDLE_TTL: Duration = Duration::from_secs(30);
const SMALL_UDP_MAX_LEN: usize = 300;
const SMALL_UDP_STICKY: Duration = Duration::from_secs(1);
// SanityProtocol 2.5.1: маленький UDP держит флоу на ОДНОМ потоке, а сервер
// ограничивает аллокацию примерно скоростью одного потока: залипший объёмный
// флоу (QUIC-upload, вложенный туннель) теряет около половины скорости. Флоу
// сохраняет pin, пока укладывается в один поток (3/4 скорости); выше — bulk
// на BULK_HOLD и переезды пачками.
const PIN_WINDOW: Duration = Duration::from_millis(250);
const PIN_WINDOW_MAX_BYTES: usize = (TB_RATE_BYTES_PER_SEC as usize) * 3 / 4 / 4;
const BULK_HOLD: Duration = Duration::from_secs(2);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum FlowKey {
    V4 {
        src: [u8; 4],
        dst: [u8; 4],
        proto: u8,
        sport: u16,
        dport: u16,
    },
    V6 {
        src: [u8; 16],
        dst: [u8; 16],
        proto: u8,
        sport: u16,
        dport: u16,
    },
}

#[derive(Clone, Copy, Debug)]
struct FlowState {
    idx: usize,
    last_seen: std::time::Instant,
    burst_left: usize,
    sticky_until: Option<std::time::Instant>,
    window_start: std::time::Instant,
    window_bytes: usize,
    bulk_until: Option<std::time::Instant>,
}

impl FlowState {
    fn new(idx: usize, now: std::time::Instant, len: usize, small_udp: bool) -> Self {
        Self {
            idx,
            last_seen: now,
            burst_left: FLOW_BURST_BYTES.saturating_sub(len),
            sticky_until: small_udp.then(|| now + SMALL_UDP_STICKY),
            window_start: now,
            window_bytes: len,
            bulk_until: None,
        }
    }

    /// Учитывает пакет и решает, может ли флоу остаться прицепленным:
    /// маленький UDP продлевает pin, только пока флоу влезает в один поток.
    fn keeps_pin(&mut self, now: std::time::Instant, len: usize, small_udp: bool) -> bool {
        if now.saturating_duration_since(self.window_start) >= PIN_WINDOW {
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.window_bytes += len;
        if self.window_bytes > PIN_WINDOW_MAX_BYTES {
            self.bulk_until = Some(now + BULK_HOLD);
            self.sticky_until = None;
        }
        let bulk = self.bulk_until.is_some_and(|until| now < until);
        if small_udp && !bulk {
            self.sticky_until = Some(now + SMALL_UDP_STICKY);
        }
        self.sticky_until.is_some_and(|until| now < until)
    }
}

fn transport_ports(packet: &[u8], offset: usize, proto: u8) -> (u16, u16) {
    if (proto == 6 || proto == 17) && packet.len() >= offset + 4 {
        (
            u16::from_be_bytes([packet[offset], packet[offset + 1]]),
            u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]),
        )
    } else {
        (0, 0)
    }
}

fn flow_key(packet: &[u8]) -> Option<FlowKey> {
    let version = packet.first()? >> 4;
    match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || packet.len() < ihl {
                return None;
            }
            let proto = packet[9];
            let src = [packet[12], packet[13], packet[14], packet[15]];
            let dst = [packet[16], packet[17], packet[18], packet[19]];
            let fragment_offset = u16::from_be_bytes([packet[6], packet[7]]) & 0x1fff;
            let (sport, dport) = if fragment_offset == 0 {
                transport_ports(packet, ihl, proto)
            } else {
                (0, 0)
            };
            Some(FlowKey::V4 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let proto = packet[6];
            let src: [u8; 16] = packet[8..24].try_into().ok()?;
            let dst: [u8; 16] = packet[24..40].try_into().ok()?;
            let (sport, dport) = transport_ports(packet, 40, proto);
            Some(FlowKey::V6 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        _ => None,
    }
}

fn is_small_udp(key: &FlowKey, packet_len: usize) -> bool {
    packet_len <= SMALL_UDP_MAX_LEN
        && matches!(
            key,
            FlowKey::V4 { proto: 17, .. } | FlowKey::V6 { proto: 17, .. }
        )
}

#[derive(Clone, Copy, Debug)]
struct RateController {
    rate: f64,
    congested: bool,
    last_adjust: Option<std::time::Instant>,
}

impl RateController {
    fn new() -> Self {
        Self {
            rate: TB_RATE_BYTES_PER_SEC,
            congested: false,
            last_adjust: None,
        }
    }

    fn mark_congested(&mut self) {
        self.congested = true;
    }

    fn tick(&mut self, now: std::time::Instant) {
        let Some(last) = self.last_adjust else {
            self.last_adjust = Some(now);
            return;
        };
        if now.saturating_duration_since(last) < ADAPTIVE_ADJUST_INTERVAL {
            return;
        }
        if self.congested {
            self.rate = (self.rate * ADAPTIVE_DECREASE_FACTOR).max(ADAPTIVE_MIN_RATE_BYTES_PER_SEC);
            self.congested = false;
        } else {
            self.rate =
                (self.rate + ADAPTIVE_INCREASE_BYTES_PER_SEC).min(ADAPTIVE_MAX_RATE_BYTES_PER_SEC);
        }
        self.last_adjust = Some(now);
    }
}

#[derive(Default)]
struct UplinkState {
    last_update: Option<std::time::Instant>,
    tokens: Vec<f64>,
    rates: Vec<RateController>,
    cursor: usize,
    flows: HashMap<FlowKey, FlowState>,
    flow_evict_counter: u32,
}

impl UplinkState {
    fn new() -> Self {
        Self::default()
    }

    fn update_tokens(&mut self, count: usize, now: std::time::Instant) {
        if count <= 1 {
            return;
        }

        if self.tokens.len() != count {
            self.tokens.resize(count, TB_CAPACITY_BYTES);
            self.rates.resize_with(count, RateController::new);
            self.last_update = Some(now);
            // Смена количества потоков сбрасывает flowlet-привязки ради
            // согласованности.
            self.flows.clear();
            self.flow_evict_counter = 0;
        }

        for controller in self.rates.iter_mut() {
            controller.tick(now);
        }

        let start = match self.last_update {
            Some(s) => s,
            None => {
                self.last_update = Some(now);
                now
            }
        };

        let elapsed = now.saturating_duration_since(start).as_secs_f64();
        if elapsed >= 0.0001 {
            self.last_update = Some(now);
            for (t, controller) in self.tokens.iter_mut().zip(self.rates.iter()) {
                *t = (*t + elapsed * controller.rate).min(TB_CAPACITY_BYTES);
            }
        }
    }

    fn evict_flows(&mut self, now: std::time::Instant) {
        self.flow_evict_counter = self.flow_evict_counter.wrapping_add(1);
        if self.flow_evict_counter % FLOW_EVICT_EVERY != 0 && self.flows.len() < FLOW_TABLE_MAX {
            return;
        }
        self.flows
            .retain(|_, state| now.saturating_duration_since(state.last_seen) < FLOW_IDLE_TTL);
        if self.flows.len() >= FLOW_TABLE_MAX {
            self.flows.clear();
        }
    }

    /// Выбирает поток с наибольшим остатком токенов начиная с курсора.
    /// Очереди lane-архитектуры принимают всегда, поэтому sendable-фильтр
    /// апстрима здесь не нужен.
    fn choose_stream(&mut self, count: usize) -> Option<usize> {
        if count == 0 {
            return None;
        }
        let start = self.cursor.min(count - 1);
        let mut best_idx = None;
        let mut best_tokens = f64::NEG_INFINITY;
        for offset in 0..count {
            let idx = (start + offset) % count;
            if self.tokens[idx] > best_tokens {
                best_tokens = self.tokens[idx];
                best_idx = Some(idx);
            }
        }
        if let Some(idx) = best_idx {
            self.cursor = (idx + 1) % count;
        }
        best_idx
    }

    /// Распределяет пакеты по потокам с per-flow привязкой: маленькие
    /// UDP-потоки (игры/голос) остаются на одном потоке, а TCP/крупный UDP
    /// переезжает после 16 КБ или при перегрузке выбранного потока. Такой
    /// порядок предотвращает per-packet reordering для TCP.
    fn select_deficit_waterfall(
        &mut self,
        worker_count: usize,
        total_len: usize,
        packet_len: usize,
        flow: Option<FlowKey>,
        now: std::time::Instant,
    ) -> usize {
        if worker_count <= 1 {
            return 0;
        }
        self.update_tokens(worker_count, now);
        let len_f = total_len as f64;

        let Some(key) = flow else {
            let Some(idx) = self.choose_stream(worker_count) else {
                return 0;
            };
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return idx;
        };

        self.evict_flows(now);
        let small_udp = is_small_udp(&key, packet_len);

        if let Some(mut state) = self.flows.get(&key).copied() {
            state.last_seen = now;
            let sticky = state.keeps_pin(now, total_len, small_udp);
            let assigned_ok = state.idx < worker_count;
            if assigned_ok && (sticky || state.burst_left >= total_len) {
                let idx = state.idx;
                state.burst_left = state.burst_left.saturating_sub(total_len);
                self.flows.insert(key, state);
                self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
                return idx;
            }

            let Some(idx) = self.choose_stream(worker_count) else {
                return 0;
            };
            state.idx = idx;
            state.burst_left = FLOW_BURST_BYTES.saturating_sub(total_len);
            self.flows.insert(key, state);
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return idx;
        }

        let Some(idx) = self.choose_stream(worker_count) else {
            return 0;
        };
        self.flows
            .insert(key, FlowState::new(idx, now, total_len, small_udp));
        self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
        idx
    }
}

struct QueuedPacket {
    packet: PacketBuf,
    epoch: u64,
}

struct PacketQueue {
    queue: ArrayQueue<QueuedPacket>,
    notify: Notify,
    state: AtomicU64,
    senders: AtomicUsize,
    receiver_open: AtomicBool,
}

pub struct PacketSender {
    shared: Arc<PacketQueue>,
}

pub struct PacketReceiver {
    shared: Arc<PacketQueue>,
}

impl Clone for PacketSender {
    fn clone(&self) -> Self {
        self.shared.senders.fetch_add(1, Ordering::Relaxed);
        Self {
            shared: self.shared.clone(),
        }
    }
}

impl Drop for PacketSender {
    fn drop(&mut self) {
        if self.shared.senders.fetch_sub(1, Ordering::AcqRel) == 1 {
            self.shared.notify.notify_waiters();
        }
    }
}

impl PacketSender {
    pub fn try_send(&self, packet: PacketBuf) -> std::result::Result<(), PacketBuf> {
        self.send(packet, false)
    }

    pub fn force_send(&self, packet: PacketBuf) -> std::result::Result<(), PacketBuf> {
        self.send(packet, true)
    }

    fn send(&self, packet: PacketBuf, force: bool) -> std::result::Result<(), PacketBuf> {
        client_perf::measure_sampled(PerfStage::PacketQueue, 128, || {
            self.send_inner(packet, force)
        })
    }

    fn send_inner(&self, packet: PacketBuf, force: bool) -> std::result::Result<(), PacketBuf> {
        if !self.shared.receiver_open.load(Ordering::Acquire) {
            return Err(packet);
        }
        let state = self.shared.state.load(Ordering::Acquire);
        if state & QUEUE_ACTIVE == 0 {
            return Err(packet);
        }
        let queued = QueuedPacket {
            packet,
            epoch: state >> 1,
        };
        if force {
            drop(self.shared.queue.force_push(queued));
        } else if let Err(queued) = self.shared.queue.push(queued) {
            return Err(queued.packet);
        }
        self.shared.notify.notify_one();
        Ok(())
    }
}

impl PacketReceiver {
    pub fn try_recv(&self) -> Option<PacketBuf> {
        client_perf::measure_sampled(PerfStage::PacketQueue, 128, || self.try_recv_inner())
    }

    fn try_recv_inner(&self) -> Option<PacketBuf> {
        loop {
            let queued = self.shared.queue.pop()?;
            let state = self.shared.state.load(Ordering::Acquire);
            if state & QUEUE_ACTIVE != 0 && queued.epoch == state >> 1 {
                return Some(queued.packet);
            }
        }
    }

    pub async fn recv(&self, cancel: &CancellationToken) -> Option<PacketBuf> {
        loop {
            if cancel.is_cancelled() {
                return None;
            }
            if let Some(packet) = self.try_recv() {
                return Some(packet);
            }
            if self.shared.senders.load(Ordering::Acquire) == 0 {
                return None;
            }
            let notified = self.shared.notify.notified();
            if let Some(packet) = self.try_recv() {
                return Some(packet);
            }
            if self.shared.senders.load(Ordering::Acquire) == 0 {
                return None;
            }
            tokio::select! {
                biased;
                _ = cancel.cancelled() => return None,
                _ = notified => {}
            }
        }
    }

    fn resume(&self) {
        let previous = self.shared.state.load(Ordering::Acquire) >> 1;
        let epoch = previous.saturating_add(1);
        self.shared.state.store(epoch << 1, Ordering::Release);
        self.purge();
        self.shared
            .state
            .store((epoch << 1) | QUEUE_ACTIVE, Ordering::Release);
        self.shared.notify.notify_waiters();
    }

    #[cfg(test)]
    fn suspend(&self) {
        let state = self.shared.state.load(Ordering::Acquire);
        self.shared
            .state
            .store(state & !QUEUE_ACTIVE, Ordering::Release);
        self.purge();
        self.shared.notify.notify_waiters();
    }

    fn purge(&self) {
        while self.shared.queue.pop().is_some() {}
    }

    pub(crate) fn is_closed(&self) -> bool {
        self.shared.senders.load(Ordering::Acquire) == 0 && self.shared.queue.is_empty()
    }

    pub(crate) fn has_queued_packet(&self) -> bool {
        !self.shared.queue.is_empty()
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.shared.queue.len()
    }
}

impl Drop for PacketReceiver {
    fn drop(&mut self) {
        self.shared.receiver_open.store(false, Ordering::Release);
        self.purge();
        self.shared.notify.notify_waiters();
    }
}

pub fn packet_channel(capacity: usize, active: bool) -> (PacketSender, PacketReceiver) {
    let state = u64::from(active) * QUEUE_ACTIVE;
    let shared = Arc::new(PacketQueue {
        queue: ArrayQueue::new(capacity.max(1)),
        notify: Notify::new(),
        state: AtomicU64::new(state),
        senders: AtomicUsize::new(1),
        receiver_open: AtomicBool::new(true),
    });
    (
        PacketSender {
            shared: shared.clone(),
        },
        PacketReceiver { shared },
    )
}

#[derive(Clone)]
pub struct WorkerChannels {
    pub id: usize,
    pub incarnation_id: u64,
    pub turn_path: Arc<str>,
    pub latency: PacketSender,
    pub priority: PacketSender,
    pub bulk: PacketSender,
}

fn interleave_turn_paths(workers: &mut Vec<WorkerChannels>) {
    workers.sort_unstable_by(|left, right| {
        left.turn_path
            .cmp(&right.turn_path)
            .then_with(|| left.id.cmp(&right.id))
    });
    let sorted = std::mem::take(workers);
    let mut groups = Vec::<Vec<WorkerChannels>>::new();
    for worker in sorted {
        if let Some(group) = groups.last_mut()
            && group
                .first()
                .is_some_and(|first| first.turn_path == worker.turn_path)
        {
            group.push(worker);
        } else {
            groups.push(vec![worker]);
        }
    }
    for offset in 0usize.. {
        let mut added = false;
        for group in &groups {
            if let Some(worker) = group.get(offset) {
                workers.push(worker.clone());
                added = true;
            }
        }
        if !added {
            return;
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct DomainRoutingConfig {
    pub enabled: bool,
    pub exclude_ru_zones: bool,
    pub template_domains: bool,
    pub direct_domains: Arc<Vec<String>>,
    pub is_whitelist: bool,
    pub ip_routing_enabled: bool,
    pub ip_is_whitelist: bool,
    pub ip_rules: Arc<Vec<crate::sni::IpRule>>,
}

pub struct Dispatcher {
    workers: ArcSwap<Vec<WorkerChannels>>,
    return_tx: PacketSender,
    pool: Arc<PacketPool>,
    reassembler: ShardedReassembler,
    next_frag_id: AtomicU16,
    cancel: CancellationToken,
    tasks: tokio::sync::Mutex<Vec<JoinHandle<()>>>,
    domain_routing: Arc<ArcSwap<DomainRoutingConfig>>,
    direct_ips: Arc<ArcSwap<HashSet<IpAddr>>>,
    dns_domain_map: Arc<crate::sni::DnsDomainMap>,
    direct_relay: Option<Arc<crate::direct_relay::DirectRelay>>,
    uplink_state: Mutex<UplinkState>,
}

impl Dispatcher {
    #[cfg(all(test, unix))]
    pub async fn start_test_tun(
        file: File,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
    ) -> Arc<Self> {
        let (return_tx, mut return_rx) = packet_channel(RETURN_CAPACITY, true);
        let dispatcher = Arc::new(Self {
            workers: ArcSwap::from_pointee(Vec::new()),
            return_tx,
            pool: pool.clone(),
            reassembler: ShardedReassembler::new(),
            next_frag_id: AtomicU16::new(1),
            cancel: cancel.clone(),
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
        });
        let io_dispatcher = dispatcher.clone();
        let task = spawn_critical("test TUN dispatcher", cancel, async move {
            let (fd_tx, mut fd_rx) = mpsc::channel(1);
            io_dispatcher
                .run_tun(file, &mut return_rx, pool, stats, &mut fd_rx)
                .await;
            drop(fd_tx);
        });
        dispatcher.tasks.lock().await.push(task);
        dispatcher
    }

    pub async fn start(
        listen: &str,
        tun_uds: Option<String>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
    ) -> Result<(Arc<Self>, String)> {
        Self::start_with_domain_routing(
            listen,
            tun_uds,
            pool,
            stats,
            cancel,
            DomainRoutingConfig::default(),
        )
        .await
    }

    pub async fn start_with_domain_routing(
        listen: &str,
        tun_uds: Option<String>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
        domain_routing: DomainRoutingConfig,
    ) -> Result<(Arc<Self>, String)> {
        let tun_mode = tun_uds.is_some();
        let (return_tx, return_rx) = packet_channel(RETURN_CAPACITY, !tun_mode);
        let routing_active = domain_routing.enabled || domain_routing.ip_routing_enabled;
        let domain_routing = Arc::new(ArcSwap::from_pointee(domain_routing));
        let direct_ips = Arc::new(ArcSwap::from_pointee(HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let (direct_inbound_tx, mut direct_inbound_rx) =
            mpsc::channel(crate::direct_relay::DIRECT_RELAY_INBOUND_CAPACITY);
        let direct_relay = routing_active
            .then(|| {
                crate::direct_relay::DirectRelay::new(
                    domain_routing.clone(),
                    direct_ips.clone(),
                    dns_domain_map.clone(),
                    pool.clone(),
                    direct_inbound_tx,
                )
                .map(Arc::new)
            })
            .transpose()?;
        let dispatcher = Arc::new(Self {
            workers: ArcSwap::from_pointee(Vec::new()),
            return_tx,
            pool: pool.clone(),
            reassembler: ShardedReassembler::new(),
            next_frag_id: AtomicU16::new(1),
            cancel: cancel.clone(),
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing,
            direct_ips,
            dns_domain_map,
            direct_relay,
            uplink_state: Mutex::new(UplinkState::new()),
        });
        if dispatcher.direct_relay.is_some() {
            let relay_dispatcher = dispatcher.clone();
            let relay_cancel = dispatcher.cancel.clone();
            let task = spawn_critical("direct relay inbound", relay_cancel.clone(), async move {
                // [FOCSQ] Цикл обязан слушать cancel: отправитель канала живёт
                // в DirectRelay внутри диспетчера и на остановку не дропается.
                // Раньше recv() без select на cancel висел вечно, и
                // dispatcher.shutdown() ждал эту задачу до бесконечности —
                // STOPPED не приходил, «Отключение…» не завершалось.
                loop {
                    tokio::select! {
                        biased;
                        _ = relay_cancel.cancelled() => return,
                        packet = direct_inbound_rx.recv() => {
                            let Some(packet) = packet else { return };
                            relay_dispatcher.return_packet(packet);
                        }
                    }
                }
            });
            dispatcher.tasks.lock().await.push(task);
        }
        if let Some(name) = tun_uds {
            // [FOCSQ] Десктоп-порт: TUN открываем САМИ (Android-модель
            // с получением FD по UDS отсюда вырезана — ей нужен хост-приложение
            // с FdReceiver, у нас его нет). Windows — wintun-адаптер, Linux —
            // /dev/net/tun через tun_linux. Дальше — общий run_tun апстрима.
            // Канал замен FD нужен только Linux-ветке ниже; на Windows он
            // оставался бы неиспользованным.
            #[cfg(target_os = "linux")]
            let (fd_tx, mut fd_rx) = mpsc::channel::<std::fs::File>(4);
            // [FOCSQ] fd_tx НЕ дропаем: закрытый канал будил бы
            // replacements.recv() в run_tun мгновенно (None), и select!
            // гасил бы весь цикл до первого чтения. Отправитель живёт
            // внутри задачи — recv() блокируется, замен не приходит.
            #[cfg(windows)]
            if name.eq_ignore_ascii_case("wintun") {
                Self::start_wintun(dispatcher.clone(), return_rx, pool, stats).await?;
                return Ok((dispatcher, "0".to_owned()));
            }
            #[cfg(windows)]
            anyhow::bail!(
                "TUN-режим {name:?} не поддерживается на Windows (доступен только wintun)"
            );
            #[cfg(target_os = "linux")]
            {
                let interface = if name == "tun" {
                    "csqtt".to_owned()
                } else {
                    name
                };
                let device = crate::tun_linux::TunDevice::open(&interface)?;
                let file = device.nonblocking_file()?;
                crate::log_error!("[КЛИЕНТ] TUN-интерфейс {interface} открыт нативно");
                return_rx.resume();
                let io_dispatcher = dispatcher.clone();
                let task_cancel = dispatcher.cancel.clone();
                let io_task = spawn_critical("TUN dispatcher", task_cancel.clone(), async move {
                    let _keep_channel_open = fd_tx;
                    let mut return_rx = return_rx;
                    io_dispatcher
                        .run_tun(file, &mut return_rx, pool, stats, &mut fd_rx)
                        .await;
                });
                dispatcher.tasks.lock().await.push(io_task);
                return Ok((dispatcher, "0".to_owned()));
            }
        } else {
            let socket = bind_udp(listen).await?;
            let local_port = socket.local_addr()?.port().to_string();
            let socket = Arc::new(socket);
            let client = Arc::new(ArcSwapOption::<SocketAddr>::from_pointee(None));
            let read_dispatcher = dispatcher.clone();
            let read_socket = socket.clone();
            let read_client = client.clone();
            let read_pool = pool.clone();
            let read_stats = stats.clone();
            let read_cancel = dispatcher.cancel.clone();
            let read_task = spawn_critical("UDP reader", read_cancel, async move {
                read_dispatcher
                    .read_udp(read_socket, read_client, read_pool, read_stats)
                    .await;
            });
            let write_dispatcher = dispatcher.clone();
            let write_cancel = dispatcher.cancel.clone();
            let write_task = spawn_critical("UDP writer", write_cancel, async move {
                write_dispatcher
                    .write_udp(socket, client, return_rx, stats)
                    .await;
            });
            dispatcher
                .tasks
                .lock()
                .await
                .extend([read_task, write_task]);
            Ok((dispatcher, local_port))
        }
    }

    pub fn register(&self, channels: WorkerChannels) {
        let id = channels.id;
        self.workers.rcu(|workers| {
            let mut updated = (**workers).clone();
            updated.retain(|worker| worker.id != id);
            updated.push(channels.clone());
            interleave_turn_paths(&mut updated);
            Arc::new(updated)
        });
    }

    pub fn unregister(&self, id: usize, incarnation_id: u64) {
        self.workers.rcu(|workers| {
            let mut updated = (**workers).clone();
            updated.retain(|worker| worker.id != id || worker.incarnation_id != incarnation_id);
            interleave_turn_paths(&mut updated);
            Arc::new(updated)
        });
    }

    #[cfg(test)]
    pub fn active_count(&self) -> usize {
        self.workers.load().len()
    }

    #[cfg(test)]
    pub fn worker(&self, id: usize) -> Option<WorkerChannels> {
        self.workers
            .load()
            .iter()
            .find(|worker| worker.id == id)
            .cloned()
    }

    pub fn update_domain_routing(&self, payload: &str) {
        let mut enabled = false;
        let mut ru = false;
        let mut template = false;
        let mut is_whitelist = false;
        let mut ip_routing_enabled = false;
        let mut ip_is_whitelist = false;
        let mut domains = Vec::new();
        let mut ip_rules = Vec::new();
        for part in payload.split(';') {
            if let Some((key, value)) = part.split_once('=') {
                let flag = || {
                    let value = value.trim();
                    value == "1" || value.eq_ignore_ascii_case("true")
                };
                match key.trim() {
                    "enabled" => enabled = flag(),
                    "ru" => ru = flag(),
                    "template" => template = flag(),
                    "whitelist" | "is_whitelist" => is_whitelist = flag(),
                    "ip_enabled" => ip_routing_enabled = flag(),
                    "ip_whitelist" | "ip_is_whitelist" => ip_is_whitelist = flag(),
                    "domains" => domains = crate::sni::parse_direct_domains(value.trim()),
                    "ips" => ip_rules = crate::sni::parse_ip_rules(value.trim()),
                    _ => {}
                }
            }
        }
        domains.truncate(1024);
        let new_config = DomainRoutingConfig {
            enabled,
            exclude_ru_zones: ru,
            template_domains: template,
            direct_domains: Arc::new(domains),
            is_whitelist,
            ip_routing_enabled,
            ip_is_whitelist,
            ip_rules: Arc::new(ip_rules),
        };
        self.domain_routing.store(Arc::new(new_config.clone()));
        if let Some(relay) = &self.direct_relay {
            relay.sync_domain_routing(&new_config);
        }
    }

    #[inline(always)]
    fn inspect_domain_routing(&self, packet: &PacketBuf) {
        let config = self.domain_routing.load();
        if !config.enabled && !config.ip_routing_enabled {
            return;
        }
        let slice = packet.as_slice();
        if let Some((domain, _is_resp, ips)) = crate::sni::parse_ip_packet_dns(slice) {
            if !ips.is_empty() {
                self.dns_domain_map.insert_multiple(&ips, &domain);
            }
            if config.enabled
                && !ips.is_empty()
                && crate::sni::is_direct_domain_mode(
                    &domain,
                    config.is_whitelist,
                    config.exclude_ru_zones,
                    config.template_domains,
                    &config.direct_domains,
                )
            {
                self.direct_ips.rcu(|current| {
                    let mut updated = (**current).clone();
                    for &ip in &ips {
                        updated.insert(ip);
                    }
                    Arc::new(updated)
                });
            }
        }
        if let Some((sni, _port)) = crate::sni::parse_ip_packet_sni(slice)
            && let Some(dst) = crate::sni::extract_dst_addr(slice)
        {
            let ip = dst.ip();
            self.dns_domain_map.insert(ip, sni.to_string());
            if config.enabled
                && crate::sni::is_direct_domain_mode(
                    sni,
                    config.is_whitelist,
                    config.exclude_ru_zones,
                    config.template_domains,
                    &config.direct_domains,
                )
                && !self.direct_ips.load().contains(&ip)
            {
                self.direct_ips.rcu(|current| {
                    let mut updated = (**current).clone();
                    updated.insert(ip);
                    Arc::new(updated)
                });
            }
        }
    }

    pub fn return_packet(&self, mut packet: PacketBuf) {
        client_perf::measure_sampled(PerfStage::ReaderReturn, 64, || {
            strip_legacy_downlink_prefix(&mut packet);
            self.inspect_domain_routing(&packet);
            let _ = self.return_tx.force_send(packet);
        });
    }

    /// [FOCSQ→апстрим 2.5.0] Фрагмент даунлинка (магия 0xCF,
    /// shared/wire_protocol.rs). ShardedReassembler собирает пакет и
    /// отдаёт его в обычный return-путь.
    pub fn handle_incoming_fragment(&self, slice: &[u8]) {
        let now = std::time::Instant::now();
        let pool = &self.pool;
        self.reassembler
            .process_fragment_from(0, slice, now, |assembled| {
                if let Some(mut packet) = pool.try_acquire() {
                    let area = packet.read_area();
                    if assembled.len() <= area.len() {
                        area[..assembled.len()].copy_from_slice(assembled);
                        if packet.set_read_len(assembled.len()).is_ok() {
                            self.return_packet(packet);
                        }
                    }
                }
            });
    }

    pub async fn shutdown(&self) {
        self.cancel.cancel();
        // [FOCSQ] Сначала аборт, потом ожидание: задача, забывшая про cancel,
        // больше не может задержать остановку (страховка поверх cancel-aware
        // циклов задач).
        let tasks: Vec<_> = self.tasks.lock().await.drain(..).collect();
        for task in &tasks {
            task.abort();
        }
        for task in tasks {
            let _ = task.await;
        }
    }

    #[cfg(unix)]
    async fn run_tun(
        self: &Arc<Self>,
        initial_file: File,
        return_receiver: &mut PacketReceiver,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        replacements: &mut mpsc::Receiver<File>,
    ) {
        use tokio::io::unix::AsyncFd;

        let mut file = initial_file;
        loop {
            let device = match AsyncFd::new(file) {
                Ok(device) => Arc::new(device),
                Err(error) => {
                    crate::log_error!("[ОШИБКА] Не удалось зарегистрировать TUN FD: {error}");
                    return;
                }
            };
            let received = tokio::select! {
                _ = self.cancel.cancelled() => return,
                replacement = replacements.recv() => replacement,
                _ = self.clone().read_tun(device.clone(), pool.clone(), stats.clone()) => None,
                _ = self.write_tun(device, return_receiver, stats.clone()) => None,
            };
            let next = match received {
                Some(file) => file,
                None => match tokio::select! {
                    _ = self.cancel.cancelled() => return,
                    replacement = replacements.recv() => replacement,
                } {
                    Some(file) => file,
                    None => return,
                },
            };
            crate::log_error!("[КЛИЕНТ] TUN FD заменён без перезапуска потоков");
            file = next;
        }
    }

    #[cfg(unix)]
    async fn read_tun(
        self: Arc<Self>,
        device: Arc<tokio::io::unix::AsyncFd<File>>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
    ) {
        use std::os::fd::AsRawFd;

        loop {
            let readiness = tokio::select! {
                _ = self.cancel.cancelled() => return,
                result = device.readable() => result,
            };
            let mut guard = match readiness {
                Ok(guard) => guard,
                Err(error) => {
                    crate::log_error!("[ОШИБКА] Ожидание чтения TUN завершено: {error}");
                    return;
                }
            };

            let mut burst = 0usize;
            while burst < 32 {
                let Some(mut packet) = pool.try_acquire() else {
                    break;
                };
                let result = client_perf::measure_sampled(PerfStage::TunRx, 64, || {
                    guard.try_io(|inner| {
                        let area = packet.read_area();
                        let length = unsafe {
                            libc::read(
                                inner.get_ref().as_raw_fd(),
                                area.as_mut_ptr().cast(),
                                area.len(),
                            )
                        };
                        if length < 0 {
                            Err(std::io::Error::last_os_error())
                        } else {
                            Ok(length as usize)
                        }
                    })
                });
                match result {
                    Ok(Ok(0)) => return,
                    Ok(Ok(length)) => {
                        burst += 1;
                        if packet.set_read_len(length).is_err() {
                            return;
                        }
                        if !Self::is_forwardable_packet(packet.as_slice()) {
                            continue;
                        }
                        stats
                            .total_bytes_up
                            .fetch_add(length as i64, Ordering::Relaxed);
                        self.dispatch_incoming(packet).await;
                    }
                    Ok(Err(error)) if is_retryable_tun_error(&error) => {
                        break;
                    }
                    Ok(Err(error)) if is_closed_tun_error(&error) => {
                        crate::log_error!("[TUN] Интерфейс закрыт, ожидаем новый FD");
                        return;
                    }
                    Ok(Err(error)) => {
                        crate::log_error!("[ОШИБКА] Чтение TUN завершено: {error}");
                        return;
                    }
                    Err(_) => break,
                }
            }
        }
    }

    async fn read_udp(
        self: Arc<Self>,
        socket: Arc<UdpSocket>,
        client: Arc<ArcSwapOption<SocketAddr>>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
    ) {
        let mut receive_batch = Vec::with_capacity(udp_batch::MAX_DATAGRAMS);
        let mut sources = [SocketAddr::from(([0, 0, 0, 0], 0)); udp_batch::MAX_DATAGRAMS];
        loop {
            let readiness = tokio::select! {
                _ = self.cancel.cancelled() => return,
                result = socket.readable() => result,
            };
            if readiness.is_err() {
                tokio::select! {
                    _ = self.cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_millis(10)) => {}
                }
                continue;
            }

            receive_batch.clear();
            while receive_batch.len() < udp_batch::MAX_DATAGRAMS {
                let Some(packet) = pool.try_acquire() else {
                    break;
                };
                receive_batch.push(packet);
            }
            if receive_batch.is_empty() {
                tokio::select! {
                    _ = self.cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_millis(1)) => {}
                }
                continue;
            }

            let batch_len = receive_batch.len();
            let result = client_perf::measure_sampled(PerfStage::UdpRx, 64, || {
                udp_batch::try_recv_from(
                    &socket,
                    receive_batch.as_mut_slice(),
                    &mut sources[..batch_len],
                )
            });
            match result {
                Ok(received) => {
                    for (index, packet) in receive_batch.drain(..received).enumerate() {
                        let address = sources[index];
                        let previous = client.load();
                        if previous.as_deref() != Some(&address) {
                            client.store(Some(Arc::new(address)));
                        }
                        client_perf::observe(PerfStage::UdpRx);
                        stats
                            .total_bytes_up
                            .fetch_add(packet.len() as i64, Ordering::Relaxed);
                        self.dispatch_incoming(packet).await;
                    }
                    receive_batch.clear();
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    receive_batch.clear();
                }
                Err(_) => {
                    receive_batch.clear();
                    tokio::select! {
                        _ = self.cancel.cancelled() => return,
                        _ = tokio::time::sleep(Duration::from_millis(10)) => {}
                    }
                }
            }
        }
    }

    async fn dispatch_incoming(&self, packet: PacketBuf) {
        self.inspect_domain_routing(&packet);
        if let Some(relay) = &self.direct_relay
            && relay.try_handle(&packet).await
        {
            return;
        }
        self.dispatch_now(packet);
    }

    /// [FOCSQ] Синхронный uplink-путь reader-потока wintun: без direct relay
    /// await-точек нет, block_on на каждый пакет не нужен. С relay читатель
    /// зовёт async-вариант dispatch_incoming выше (try_handle реально
    /// await'ит bind direct-UDP сокета).
    #[cfg(windows)]
    fn dispatch_incoming_sync(&self, packet: PacketBuf) {
        self.inspect_domain_routing(&packet);
        self.dispatch_now(packet);
    }

    fn dispatch(&self, packet: PacketBuf) {
        self.dispatch_now(packet);
    }
    #[inline(always)]
    fn is_forwardable_packet(slice: &[u8]) -> bool {
        if slice.len() < 20 {
            return false;
        }
        let version = slice[0] >> 4;
        match version {
            4 => {
                let ihl = (slice[0] & 0x0f) as usize * 4;
                if ihl < 20 || slice.len() < ihl {
                    return false;
                }
                let total_len = u16::from_be_bytes([slice[2], slice[3]]) as usize;
                if total_len < ihl || total_len > slice.len() {
                    return false;
                }
                let dst_ip = [slice[16], slice[17], slice[18], slice[19]];
                if dst_ip[0] == 0 || dst_ip[0] == 127 || dst_ip[0] >= 224 {
                    return false;
                }
                if dst_ip[0] == 169 && dst_ip[1] == 254 {
                    return false;
                }
                true
            }
            6 => {
                if slice.len() < 40 {
                    return false;
                }
                let payload_len = u16::from_be_bytes([slice[4], slice[5]]) as usize;
                if payload_len + 40 > slice.len() {
                    return false;
                }
                let dst = &slice[24..40];
                if dst.iter().all(|&b| b == 0) {
                    return false;
                }
                if dst[..15].iter().all(|&b| b == 0) && dst[15] == 1 {
                    return false;
                }
                if dst[0] == 0xff {
                    return false;
                }
                if dst[0] == 0xfe && (dst[1] & 0xc0) == 0x80 {
                    return false;
                }
                true
            }
            _ => false,
        }
    }

    /// [PORT 2.5.0] Выбор воркера token-bucket планировщиком апстрима:
    /// дефицитный waterfall по остаткам токенов + per-flow привязка.
    fn select_uplink_worker(
        &self,
        worker_count: usize,
        total_len: usize,
        packet_len: usize,
        flow: Option<FlowKey>,
    ) -> usize {
        client_perf::measure_sampled(PerfStage::Scheduler, 64, || {
            let mut state = match self.uplink_state.lock() {
                Ok(guard) => guard,
                Err(poisoned) => poisoned.into_inner(),
            };
            state.select_deficit_waterfall(
                worker_count,
                total_len,
                packet_len,
                flow,
                std::time::Instant::now(),
            )
        })
    }

    /// Congestion выбранного потока: переполнение его lane-очереди.
    fn mark_uplink_congested(&self, idx: usize) {
        let mut state = match self.uplink_state.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(controller) = state.rates.get_mut(idx) {
            controller.mark_congested();
        }
    }

    fn dispatch_now(&self, packet: PacketBuf) {
        let workers = self.workers.load();
        let count = workers.len();
        if count == 0 {
            return;
        }
        if packet.len() <= DIRECT_PACKET_MAX_LEN {
            let packet_len = packet.len();
            let flow = flow_key(packet.as_slice());
            let idx = self.select_uplink_worker(
                count,
                packet_len.saturating_add(WRAP_OVERHEAD),
                packet_len,
                flow,
            );
            let ticket = DispatchTicket {
                start_slot: idx,
                class: packet_class(packet.as_slice()),
            };
            if let Err(packet) = enqueue_selected_worker(workers.as_slice(), ticket, packet) {
                self.mark_uplink_congested(idx);
                let _ = replace_oldest_in_selected_queue(workers.as_slice(), ticket, packet);
            }
            return;
        }
        self.send_fragmented(workers.as_slice(), packet);
    }

    /// [FOCSQ→апстрим 2.5.0] Uplink-пакет длиннее DIRECT_PACKET_MAX_LEN
    /// шардируется фрагментами 0xCF (shared/wire_protocol.rs), как это
    /// делает апстримный клиент: server 2.5.0 принимает только голые
    /// IP-пакеты и такие фрагменты. Все фрагменты одного пакета уходят
    /// в один выбранный воркер — иначе они попадут в разные сессии.
    fn send_fragmented(&self, workers: &[WorkerChannels], packet: PacketBuf) {
        let packet_len = packet.len();
        if packet_len > MAX_PACKET_LEN {
            drop(packet);
            return;
        }
        let flow = flow_key(packet.as_slice());
        let expected_frags = packet_len.div_ceil(MAX_FRAG_PAYLOAD);
        let packet_id = self.next_frag_id.fetch_add(1, Ordering::Relaxed);
        let mut frags = Vec::with_capacity(expected_frags);
        {
            let slice = packet.as_slice();
            let pool = &self.pool;
            fragment_packet(packet_id, slice, |frag| {
                if let Some(mut buffer) = pool.try_acquire() {
                    let area = buffer.read_area();
                    if frag.len() <= area.len() {
                        area[..frag.len()].copy_from_slice(frag);
                        if buffer.set_read_len(frag.len()).is_ok() {
                            frags.push(buffer);
                        }
                    }
                }
            });
        }
        drop(packet);
        if frags.len() != expected_frags {
            crate::log_error!(
                "[ОШИБКА] Dispatcher: фрагмент целиком потерян: {} из {} (буферы)",
                frags.len(),
                expected_frags
            );
            return;
        }
        let total_eff: usize = frags
            .iter()
            .map(|frag| frag.len().saturating_add(WRAP_OVERHEAD))
            .sum();
        let idx = self.select_uplink_worker(workers.len(), total_eff, packet_len, flow);
        let ticket = DispatchTicket {
            start_slot: idx,
            class: packet_class(frags[0].as_slice()),
        };
        let mut failed = false;
        for frag in frags {
            if let Err(frag) = enqueue_selected_worker(workers, ticket, frag) {
                let _ = replace_oldest_in_selected_queue(workers, ticket, frag);
                failed = true;
            }
        }
        if failed {
            self.mark_uplink_congested(idx);
        }
    }

    #[cfg(unix)]
    async fn write_tun(
        &self,
        device: Arc<tokio::io::unix::AsyncFd<File>>,
        return_receiver: &mut PacketReceiver,
        stats: Arc<Stats>,
    ) {
        let mut pending: Option<(PacketBuf, usize)> = None;
        // [FOCSQ] Счётчик отброшенных пакетов для троттлинга лога.
        let mut dropped = 0u64;
        loop {
            if pending.is_none() {
                let packet = tokio::select! {
                    biased;
                    _ = self.cancel.cancelled() => return,
                    packet = return_receiver.recv(&self.cancel) => packet,
                };
                let Some(mut packet) = packet else {
                    return;
                };
                strip_legacy_downlink_prefix(&mut packet);
                if packet.len() >= 20 && Self::is_forwardable_packet(packet.as_slice()) {
                    pending = Some((packet, 0));
                } else {
                    continue;
                }
            }

            let readiness = tokio::select! {
                _ = self.cancel.cancelled() => return,
                result = device.writable() => result,
            };
            let mut guard = match readiness {
                Ok(guard) => guard,
                Err(error) => {
                    crate::log_error!("[ОШИБКА] Ожидание записи TUN завершено: {error}");
                    return;
                }
            };
            let mut burst = 0usize;
            let state = loop {
                let state = {
                    let (packet, written) = pending.as_mut().expect("TUN packet is pending");
                    self.try_write_tun_packet(&mut guard, &stats, packet, written, &mut dropped)
                };
                match state {
                    TunWriteState::Complete => {
                        burst += 1;
                        if burst == 64 {
                            pending = None;
                            break TunWriteState::Complete;
                        }
                        pending = return_receiver
                            .try_recv()
                            .map(|mut packet| {
                                strip_legacy_downlink_prefix(&mut packet);
                                packet
                            })
                            .filter(|packet| Self::is_forwardable_packet(packet.as_slice()))
                            .map(|packet| (packet, 0));
                        if pending.is_none() {
                            break TunWriteState::Complete;
                        }
                    }
                    TunWriteState::Continue => {}
                    state => break state,
                }
            };
            drop(guard);
            match state {
                TunWriteState::Complete | TunWriteState::Wait => {}
                TunWriteState::Backoff => {
                    tokio::select! {
                        _ = self.cancel.cancelled() => return,
                        _ = tokio::time::sleep(Duration::from_millis(1)) => {}
                    }
                }
                TunWriteState::Yield => tokio::task::yield_now().await,
                // [FOCSQ] Неудачная запись одного пакета больше не убивает
                // задачу записи: пакет отбрасываем и берём следующий.
                TunWriteState::Dropped => {
                    pending = None;
                }
                TunWriteState::Closed => return,
                TunWriteState::Continue => unreachable!(),
            }
        }
    }

    #[cfg(unix)]
    fn try_write_tun_packet(
        &self,
        guard: &mut tokio::io::unix::AsyncFdReadyGuard<'_, File>,
        stats: &Stats,
        packet: &PacketBuf,
        written: &mut usize,
        dropped: &mut u64,
    ) -> TunWriteState {
        use std::os::fd::AsRawFd;

        let result = client_perf::measure_sampled(PerfStage::TunTx, 64, || {
            guard.try_io(|inner| {
                let remaining = &packet.as_slice()[*written..];
                let length = unsafe {
                    libc::write(
                        inner.get_ref().as_raw_fd(),
                        remaining.as_ptr().cast(),
                        remaining.len(),
                    )
                };
                if length < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(length as usize)
                }
            })
        });
        match result {
            Ok(Ok(0)) => {
                // [FOCSQ] Запись ничего не записала: пакет отбрасываем,
                // задача записи продолжает работать (раньше — смерть writer'а).
                log_dropped_packet(dropped, "запись вернула 0 байт");
                TunWriteState::Dropped
            }
            Ok(Ok(length)) => {
                *dropped = 0;
                *written += length;
                if *written == packet.len() {
                    stats
                        .total_bytes_down
                        .fetch_add(packet.len() as i64, Ordering::Relaxed);
                    TunWriteState::Complete
                } else {
                    TunWriteState::Continue
                }
            }
            Ok(Err(error)) if is_retryable_tun_error(&error) => {
                if matches!(
                    error.raw_os_error(),
                    Some(libc::ENOBUFS) | Some(libc::ENOMEM)
                ) {
                    TunWriteState::Backoff
                } else {
                    TunWriteState::Yield
                }
            }
            Ok(Err(error)) if is_closed_tun_error(&error) => {
                crate::log_error!("[TUN] Интерфейс закрыт, ожидаем новый FD");
                TunWriteState::Closed
            }
            // [FOCSQ] Ядро отвергло конкретный пакет (EINVAL/EMSGSIZE/...):
            // раньше это убивало запись TUN навсегда, туннель «зависал».
            Ok(Err(error)) => {
                log_dropped_packet(dropped, &error.to_string());
                TunWriteState::Dropped
            }
            Err(_) => TunWriteState::Wait,
        }
    }

    async fn write_udp(
        &self,
        socket: Arc<UdpSocket>,
        client: Arc<ArcSwapOption<SocketAddr>>,
        return_receiver: PacketReceiver,
        stats: Arc<Stats>,
    ) {
        let mut send_batch = Vec::with_capacity(udp_batch::MAX_DATAGRAMS);
        loop {
            let Some(packet) = return_receiver.recv(&self.cancel).await else {
                return;
            };
            send_batch.clear();
            let first_class = packet_class(packet.as_slice());
            send_batch.push(packet);
            while send_batch.len() < first_class.datagram_batch() {
                let Some(next) = return_receiver.try_recv() else {
                    break;
                };
                send_batch.push(next);
            }

            let address = client.load().as_deref().copied();
            self.write_udp_batch(&socket, address, &stats, &send_batch)
                .await;
        }
    }

    async fn write_udp_batch(
        &self,
        socket: &UdpSocket,
        address: Option<SocketAddr>,
        stats: &Stats,
        packets: &[PacketBuf],
    ) {
        if let Some(address) = address {
            let mut datagrams: [&[u8]; udp_batch::MAX_DATAGRAMS] = [&[]; udp_batch::MAX_DATAGRAMS];
            for (index, packet) in packets.iter().enumerate() {
                datagrams[index] = packet.as_slice();
            }
            let mut sent = 0usize;
            while sent < packets.len() {
                let readiness = tokio::select! {
                    biased;
                    _ = self.cancel.cancelled() => return,
                    result = socket.writable() => result,
                };
                if readiness.is_err() {
                    return;
                }

                match client_perf::measure_sampled(PerfStage::UdpTx, 64, || {
                    udp_batch::try_send_to(socket, address, &datagrams[sent..packets.len()])
                }) {
                    Ok(0) => tokio::task::yield_now().await,
                    Ok(count) => {
                        for packet in &packets[sent..sent + count] {
                            stats
                                .total_bytes_down
                                .fetch_add(packet.len() as i64, Ordering::Relaxed);
                        }
                        sent += count;
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(_) => return,
                }
            }
        }
    }

    /// [FOCSQ] Нативный Windows TUN (wintun): адаптер создаётся ядром,
    /// пакеты читает блокирующий OS-поток, пишет асинхронная задача.
    /// Порядок возврата — одна FIFO return-очередь, как write_tun апстрима.
    #[cfg(windows)]
    async fn start_wintun(
        self: Arc<Self>,
        return_receiver: PacketReceiver,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
    ) -> Result<()> {
        use anyhow::Context as _;

        let device = match crate::tun_win::WinTunDevice::new() {
            Ok(device) => device,
            Err(error) => {
                crate::log_error!("[ОШИБКА] Wintun: {error:#}");
                return Err(error);
            }
        };
        crate::log_error!("[КЛИЕНТ] Wintun-адаптер создан");
        return_receiver.resume();

        let writer_dispatcher = self.clone();
        let writer_cancel = self.cancel.clone();
        let writer_device = device.clone();
        let write_stats = stats.clone();
        let write_task = spawn_critical("Wintun writer", writer_cancel, async move {
            writer_dispatcher
                .write_wintun(writer_device, return_receiver, write_stats)
                .await;
        });
        self.tasks.lock().await.push(write_task);

        // При отмене будим блокирующий receive_blocking читателя
        {
            let interrupt_device = device.clone();
            let interrupt_cancel = self.cancel.clone();
            tokio::spawn(async move {
                interrupt_cancel.cancelled().await;
                interrupt_device.interrupt();
            });
        }

        // Читатель: receive_blocking() не асинхронный, живёт в своём потоке
        let reader_dispatcher = self.clone();
        let reader_cancel = self.cancel.clone();
        let read_stats = stats.clone();
        let reader_runtime = tokio::runtime::Handle::current();
        // [FOCSQ] Режим фиксируется на старте: direct_relay не меняется за
        // жизнь диспетчера (строится в start_with_domain_routing).
        let uses_relay = reader_dispatcher.direct_relay.is_some();
        std::thread::Builder::new()
            .name("csqtt-wintun-reader".to_owned())
            .spawn(move || {
                // Копирует пакет из wintun в буфер пула и dispatch'ит.
                // false — reader надо остановить (set_read_len не прошёл).
                let dispatch_packet = |bytes: Vec<u8>| -> bool {
                    let Some(mut packet) = pool.try_acquire() else {
                        return true; // пул исчерпан: пакет отброшен
                    };
                    {
                        let area = packet.read_area();
                        if bytes.len() > area.len() {
                            return true;
                        }
                        area[..bytes.len()].copy_from_slice(&bytes);
                    }
                    if packet.set_read_len(bytes.len()).is_err() {
                        return false;
                    }
                    if !Self::is_forwardable_packet(packet.as_slice()) {
                        return true;
                    }
                    read_stats
                        .total_bytes_up
                        .fetch_add(bytes.len() as i64, Ordering::Relaxed);
                    if uses_relay {
                        reader_runtime.block_on(reader_dispatcher.dispatch_incoming(packet));
                    } else {
                        reader_dispatcher.dispatch_incoming_sync(packet);
                    }
                    true
                };
                // [FOCSQ] Батч-чтение: первое чтение блокирующее, затем ring
                // вычитывается try_receive, пока есть пакеты (Ok(None) —
                // пусто). Каждый пакет аплинка больше не стоит отдельного
                // блокирующего системного вызова; выключение сессии
                // (drop_interface_now → interrupt) роняет и try_receive.
                'read: loop {
                    if reader_cancel.is_cancelled() {
                        break;
                    }
                    let bytes = match device.receive_blocking() {
                        Ok(bytes) => bytes,
                        Err(error) => {
                            if !reader_cancel.is_cancelled() {
                                crate::log_error!("[ОШИБКА] Чтение wintun прервано: {error}");
                            }
                            break;
                        }
                    };
                    let mut next = Some(bytes);
                    let mut burst = 0usize;
                    while let Some(bytes) = next.take() {
                        if !dispatch_packet(bytes) {
                            break 'read;
                        }
                        burst += 1;
                        if burst >= WINTUN_READ_BURST {
                            break;
                        }
                        next = match device.try_receive() {
                            Ok(Some(bytes)) => Some(bytes),
                            Ok(None) => break,
                            Err(error) => {
                                if !reader_cancel.is_cancelled() {
                                    crate::log_error!("[ОШИБКА] Чтение wintun прервано: {error}");
                                }
                                break 'read;
                            }
                        };
                    }
                }
                crate::log_error!("[КЛИЕНТ] Wintun reader остановлен");
            })
            .context("не удалось запустить поток чтения wintun")?;
        Ok(())
    }

    /// Запись пакетов из ядра в wintun-адаптер (трафик "вниз", к приложениям)
    #[cfg(windows)]
    async fn write_wintun(
        self: Arc<Self>,
        device: Arc<crate::tun_win::WinTunDevice>,
        return_receiver: PacketReceiver,
        stats: Arc<Stats>,
    ) {
        let mut dropped = 0u64;
        loop {
            let packet = tokio::select! {
                biased;
                _ = self.cancel.cancelled() => return,
                packet = return_receiver.recv(&self.cancel) => match packet {
                    Some(packet) => packet,
                    None => return,
                },
            };
            let mut packet = packet;
            strip_legacy_downlink_prefix(&mut packet);
            if Self::is_forwardable_packet(packet.as_slice()) {
                write_wintun_packet(&device, &stats, &packet, &mut dropped);
            }
            // Burst write: последовательно отдаём до 64 пакетов
            let mut burst = 0usize;
            while burst < 64 {
                let Some(mut next) = return_receiver.try_recv() else {
                    break;
                };
                strip_legacy_downlink_prefix(&mut next);
                if !Self::is_forwardable_packet(next.as_slice()) {
                    continue;
                }
                write_wintun_packet(&device, &stats, &next, &mut dropped);
                burst += 1;
            }
        }
    }
}

// [FOCSQ] Запись одного пакета в wintun (Windows-путь write_wintun выше)
#[cfg(windows)]
fn write_wintun_packet(
    device: &Arc<crate::tun_win::WinTunDevice>,
    stats: &Stats,
    packet: &PacketBuf,
    dropped: &mut u64,
) {
    match device.send(packet.as_slice()) {
        Ok(()) => {
            stats
                .total_bytes_down
                .fetch_add(packet.len() as i64, Ordering::Relaxed);
            *dropped = 0;
        }
        // [FOCSQ] Неудачная запись одного пакета (мусор, переполнение ring,
        // отвалившийся адаптер) больше не убивает задачу записи: пакет
        // отбрасывается, writer продолжает. Смерть адаптера видна читателю —
        // соединение разбирается по своей цепочке.
        Err(error) => {
            log_dropped_packet(dropped, &error.to_string());
        }
    }
}

fn spawn_critical<F>(name: &'static str, cancel: CancellationToken, future: F) -> JoinHandle<()>
where
    F: Future<Output = ()> + Send + 'static,
{
    tokio::spawn(async move {
        if let Err(error) = tokio::spawn(future).await {
            crate::log_error!("[СУПЕРВИЗОР] {name} завершился аварийно: {error}");
            cancel.cancel();
        }
    })
}

#[cfg(unix)]
fn is_closed_tun_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == libc::EIO || code == libc::EBADF || code == libc::ENODEV
    )
}

/// [FOCSQ] Лог отброшенных в TUN пакетов с троттлингом: первые 8 сообщений
/// и далее каждое 1000-е, чтобы мусорный поток не забивал журнал.
fn log_dropped_packet(counter: &mut u64, reason: &str) {
    *counter += 1;
    if *counter <= 8 || counter.is_multiple_of(1000) {
        crate::log_error!("[ОШИБКА] Запись TUN: пакет #{counter} отброшен ({reason})");
    }
}

#[cfg(unix)]
fn is_retryable_tun_error(error: &std::io::Error) -> bool {
    error.kind() == std::io::ErrorKind::Interrupted
        || error.kind() == std::io::ErrorKind::WouldBlock
        || matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENOBUFS || code == libc::ENOMEM
        )
}

fn enqueue_selected_worker(
    workers: &[WorkerChannels],
    ticket: DispatchTicket,
    packet: PacketBuf,
) -> Result<(), PacketBuf> {
    let Some(worker) = workers.get(ticket.start_slot) else {
        return Err(packet);
    };
    match ticket.class {
        PacketClass::Small => worker.latency.try_send(packet),
        PacketClass::Medium => worker.priority.try_send(packet),
        PacketClass::Bulk => worker.bulk.try_send(packet),
    }
}

fn replace_oldest_in_selected_queue(
    workers: &[WorkerChannels],
    ticket: DispatchTicket,
    packet: PacketBuf,
) -> Result<(), PacketBuf> {
    let Some(worker) = workers.get(ticket.start_slot) else {
        return Err(packet);
    };
    match ticket.class {
        PacketClass::Small => worker.latency.force_send(packet),
        PacketClass::Medium => worker.priority.force_send(packet),
        PacketClass::Bulk => worker.bulk.force_send(packet),
    }
}

async fn bind_udp(address: &str) -> Result<UdpSocket> {
    for attempt in 1..=5 {
        match UdpSocket::bind(address).await {
            Ok(socket) => {
                SockRef::from(&socket).set_recv_buffer_size(625 * 1024)?;
                SockRef::from(&socket).set_send_buffer_size(625 * 1024)?;
                return Ok(socket);
            }
            Err(error) if attempt < 5 => {
                crate::log_error!("[ОЖИДАНИЕ] Порт {address} занят. Жду... ({attempt}/5): {error}");
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Err(error) => crate::log_error!("[АВТО-ПОРТ] Порт {address} всё ещё занят: {error}"),
        }
    }
    UdpSocket::bind("127.0.0.1:0").await.map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use std::collections::VecDeque;
    #[cfg(unix)]
    use std::{
        fs::File,
        io::{IoSlice, Write},
        os::{
            fd::{AsRawFd, FromRawFd, IntoRawFd},
            unix::net::UnixStream,
        },
    };

    #[cfg(unix)]
    fn send_uds_fd(name: &str, file: &File) {
        use nix::sys::socket::{
            AddressFamily, ControlMessage, MsgFlags, SockFlag, SockType, UnixAddr, connect,
            sendmsg, socket,
        };

        let socket = socket(
            AddressFamily::Unix,
            SockType::Stream,
            SockFlag::SOCK_CLOEXEC,
            None,
        )
        .unwrap();
        connect(
            socket.as_raw_fd(),
            &UnixAddr::new_abstract(name.as_bytes()).unwrap(),
        )
        .unwrap();
        let payload = [1u8];
        let slices = [IoSlice::new(&payload)];
        let controls = [ControlMessage::ScmRights(&[file.as_raw_fd()])];
        sendmsg::<UnixAddr>(
            socket.as_raw_fd(),
            &slices,
            &controls,
            MsgFlags::empty(),
            None,
        )
        .unwrap();
    }

    #[derive(Clone, Copy, Default)]
    struct QueueCoverage {
        full_rejection: usize,
        forced_replacement: usize,
        inactive_rejection: usize,
        suspended: usize,
        resumed: usize,
        purged: usize,
    }

    impl QueueCoverage {
        fn complete(self) -> bool {
            self.full_rejection > 0
                && self.forced_replacement > 0
                && self.inactive_rejection > 0
                && self.suspended > 0
                && self.resumed > 0
                && self.purged > 0
        }
    }

    fn test_dispatcher() -> (Arc<Dispatcher>, PacketReceiver) {
        let (return_tx, return_rx) = packet_channel(64, true);
        (
            Arc::new(Dispatcher {
                workers: ArcSwap::from_pointee(Vec::new()),
                return_tx,
                pool: PacketPool::new(64),
                reassembler: ShardedReassembler::new(),
                next_frag_id: AtomicU16::new(1),
                cancel: CancellationToken::new(),
                tasks: tokio::sync::Mutex::new(Vec::new()),
                domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
                direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
                dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
                direct_relay: None,
                uplink_state: Mutex::new(UplinkState::new()),
            }),
            return_rx,
        )
    }

    fn channels(
        id: usize,
        capacity: usize,
    ) -> (
        WorkerChannels,
        PacketReceiver,
        PacketReceiver,
        PacketReceiver,
    ) {
        let (latency, latency_rx) = packet_channel(capacity, true);
        let (priority, priority_rx) = packet_channel(capacity, true);
        let (bulk, bulk_rx) = packet_channel(capacity, true);
        (
            WorkerChannels {
                id,
                incarnation_id: id as u64 + 1,
                turn_path: Arc::from("test"),
                latency,
                priority,
                bulk,
            },
            latency_rx,
            priority_rx,
            bulk_rx,
        )
    }

    #[test]
    fn registered_workers_interleave_turn_paths() {
        let (dispatcher, _return_rx) = test_dispatcher();
        for (id, turn_path) in [
            (1, "turn-a"),
            (2, "turn-a"),
            (3, "turn-b"),
            (4, "turn-b"),
            (5, "turn-c"),
            (6, "turn-c"),
        ] {
            let (mut worker, _latency, _priority, _bulk) = channels(id, 1);
            worker.turn_path = Arc::from(turn_path);
            dispatcher.register(worker);
        }
        let workers = dispatcher.workers.load();
        let order: Vec<_> = workers
            .iter()
            .map(|worker| worker.turn_path.as_ref())
            .collect();
        assert_eq!(
            order,
            ["turn-a", "turn-b", "turn-c", "turn-a", "turn-b", "turn-c"]
        );
    }

    fn queue_trace_outcome(actions: &[(u8, u8)], replace_oldest: bool) -> (bool, QueueCoverage) {
        let capacity = 7;
        let pool = PacketPool::new(32);
        let (sender, receiver) = packet_channel(capacity, true);
        let mut model = VecDeque::new();
        let mut active = true;
        let mut coverage = QueueCoverage::default();
        for &(operation, value) in actions {
            match operation % 6 {
                0 => {
                    let mut packet = pool.acquire();
                    packet.set_read_len(1).unwrap();
                    packet.as_mut_slice()[0] = value;
                    let accepted = sender.try_send(packet).is_ok();
                    let expected = active && model.len() < capacity;
                    if accepted != expected {
                        return (false, coverage);
                    }
                    if expected {
                        model.push_back(value);
                    } else if active {
                        coverage.full_rejection += 1;
                    } else {
                        coverage.inactive_rejection += 1;
                    }
                }
                1 => {
                    let mut packet = pool.acquire();
                    packet.set_read_len(1).unwrap();
                    packet.as_mut_slice()[0] = value;
                    let accepted = sender.force_send(packet).is_ok();
                    if accepted != active {
                        return (false, coverage);
                    }
                    if active {
                        if model.len() == capacity {
                            coverage.forced_replacement += 1;
                            if replace_oldest {
                                model.pop_front();
                            } else {
                                model.pop_back();
                            }
                        }
                        model.push_back(value);
                    } else {
                        coverage.inactive_rejection += 1;
                    }
                }
                2 => {
                    let actual = receiver.try_recv().map(|packet| packet.as_slice()[0]);
                    if actual != model.pop_front() {
                        return (false, coverage);
                    }
                }
                3 => {
                    receiver.suspend();
                    active = false;
                    model.clear();
                    coverage.suspended += 1;
                }
                4 => {
                    receiver.resume();
                    active = true;
                    model.clear();
                    coverage.resumed += 1;
                }
                _ => {
                    receiver.purge();
                    model.clear();
                    coverage.purged += 1;
                }
            }
        }
        while let Some(expected) = model.pop_front() {
            if receiver.try_recv().map(|packet| packet.as_slice()[0]) != Some(expected) {
                return (false, coverage);
            }
        }
        (
            receiver.try_recv().is_none() && pool.available() == pool.capacity(),
            coverage,
        )
    }

    fn queue_trace_matches(actions: &[(u8, u8)], replace_oldest: bool) -> bool {
        queue_trace_outcome(actions, replace_oldest).0
    }

    fn mix64(mut value: u64) -> u64 {
        value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }

    fn deterministic_queue_actions(seed: u64, length: usize) -> Vec<(u8, u8)> {
        let mut state = seed;
        let mut actions = Vec::with_capacity(length);
        let prefix = [
            (0, 1),
            (0, 2),
            (0, 3),
            (0, 4),
            (0, 5),
            (0, 6),
            (0, 7),
            (0, 8),
            (1, 9),
            (3, 0),
            (0, 10),
            (4, 0),
            (1, 11),
            (5, 0),
        ];
        actions.extend(prefix.into_iter().take(length));
        for _ in actions.len()..length {
            state = mix64(state);
            actions.push(((state % 6) as u8, (state >> 24) as u8));
        }
        actions
    }

    fn tcp_packet(pool: &Arc<PacketPool>, source_port: u16, sequence: u32) -> PacketBuf {
        tcp_packet_len(pool, source_port, sequence, 1_200)
    }

    fn tcp_packet_len(
        pool: &Arc<PacketPool>,
        source_port: u16,
        sequence: u32,
        len: usize,
    ) -> PacketBuf {
        assert!(len >= 40);
        let mut packet = pool.acquire();
        packet.set_read_len(len).unwrap();
        let bytes = packet.as_mut_slice();
        bytes.fill(0);
        bytes[0] = 0x45;
        bytes[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        bytes[8] = 64;
        bytes[9] = 6;
        bytes[12..16].copy_from_slice(&[10, 66, 67, 2]);
        bytes[16..20].copy_from_slice(&[1, 1, 1, 1]);
        bytes[20..22].copy_from_slice(&source_port.to_be_bytes());
        bytes[22..24].copy_from_slice(&443u16.to_be_bytes());
        bytes[24..28].copy_from_slice(&sequence.to_be_bytes());
        bytes[32] = 5 << 4;
        bytes[33] = 0x18;
        packet
    }

    /// [FOCSQ→апстрим 2.5.0] Uplink-пакеты длиннее 1280 байт уходят на
    /// сервер в виде фрагментов 0xCF (shared/wire_protocol.rs), как у
    /// апстримного клиента; сервер v2.5.0 их собирает на своей стороне.
    #[test]
    fn outbound_large_packet_is_split_into_wire_fragments() {
        let (dispatcher, _return_rx) = test_dispatcher();
        let slab_pool = Arc::new(PacketPool::new(2));
        let original = tcp_packet_len(&slab_pool, 50_000, 77, 1_500);
        let original_bytes = original.as_slice().to_vec();
        drop(original);
        assert_eq!(original_bytes.len(), 1_500);

        let pool = PacketPool::new(4);
        let (worker, _latency, _priority, bulk) = channels(0, 4);
        dispatcher.workers.store(Arc::new(vec![worker]));
        dispatcher.dispatch(tcp_packet_len(&pool, 50_000, 77, 1_500));

        let first = bulk.try_recv().unwrap();
        let second = bulk.try_recv().unwrap();
        assert!(bulk.try_recv().is_none());

        let first_header = crate::wire_protocol::FragHeader::decode(first.as_slice()).unwrap();
        assert_eq!(first_header.packet_id, 1);
        assert_eq!(first_header.frag_idx, 0);
        assert_eq!(first_header.total_frags, 2);
        assert_eq!(first_header.total_len as usize, 1_500);

        let second_header = crate::wire_protocol::FragHeader::decode(second.as_slice()).unwrap();
        assert_eq!(second_header.frag_idx, 1);

        let mut assembled = Vec::new();
        assembled.extend_from_slice(&first.as_slice()[crate::wire_protocol::FRAG_HEADER_LEN..]);
        assembled.extend_from_slice(&second.as_slice()[crate::wire_protocol::FRAG_HEADER_LEN..]);
        assert_eq!(assembled.len(), 1_500);
        assert_eq!(assembled, original_bytes);
        assert_eq!(pool.available(), pool.capacity());
    }

    /// [FOCSQ→апстрим 2.5.0] Даунлинк-фрагменты 0xCF собираются в диспетчере
    /// при любом порядке прихода и уходят в обычную return-очередь.
    #[tokio::test(start_paused = true)]
    async fn return_fragment_reassembly_restores_out_of_order_packets() {
        use crate::wire_protocol::fragment_packet;
        let (dispatcher, return_rx) = test_dispatcher();
        let pool = PacketPool::new(6);
        let packet = tcp_packet_len(&pool, 50_000, 77, 1_500);
        let mut wire = Vec::new();
        fragment_packet(5, packet.as_slice(), |frag| wire.push(frag.to_vec()));
        assert_eq!(wire.len(), 2);

        dispatcher.handle_incoming_fragment(&wire[1]);
        dispatcher.handle_incoming_fragment(&wire[0]);
        let assembled = return_rx.try_recv().unwrap();
        assert_eq!(assembled.as_slice().len(), packet.len());
        assert_eq!(packet_sequence(&assembled), 77);
        assert!(return_rx.try_recv().is_none());
        drop(packet);
        drop(assembled);
        assert_eq!(pool.available(), pool.capacity());
    }

    #[test]
    fn bulk_udp_with_small_packets_loses_the_pin() {
        // A QUIC-like upload: 1300-byte packets at ~20 Mbit/s and a small one every 200 ms.
        let start = std::time::Instant::now();
        let mut state = FlowState::new(0, start, 100, true);
        let mut pinned_late = 0;
        for i in 1..4000u64 {
            let now = start + Duration::from_micros(i * 520);
            let small = i % 385 == 0;
            if state.keeps_pin(now, if small { 100 } else { 1300 }, small) && i > 200 {
                pinned_late += 1;
            }
        }
        assert_eq!(pinned_late, 0, "a bulk flow must not stay pinned");
    }

    #[test]
    fn game_flow_keeps_the_pin() {
        // 64 packets/s, small and 1200-byte alternating: fits in one stream, stays pinned throughout.
        let start = std::time::Instant::now();
        let mut state = FlowState::new(0, start, 120, true);
        for i in 1..320u64 {
            let now = start + Duration::from_micros(i * 15_625);
            let small = i % 2 == 0;
            assert!(
                state.keeps_pin(now, if small { 120 } else { 1200 }, small),
                "packet {i}"
            );
        }
    }

    #[test]
    fn adaptive_rate_backs_off_on_congestion_and_recovers() {
        let mut controller = RateController::new();
        let mut now = std::time::Instant::now();
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
        controller.tick(now);

        controller.mark_congested();
        now += ADAPTIVE_ADJUST_INTERVAL;
        controller.tick(now);
        assert!(controller.rate < TB_RATE_BYTES_PER_SEC);
        let after_first = controller.rate;

        for _ in 0..10 {
            controller.mark_congested();
            now += ADAPTIVE_ADJUST_INTERVAL;
            controller.tick(now);
        }
        assert_eq!(controller.rate, ADAPTIVE_MIN_RATE_BYTES_PER_SEC);
        assert!(controller.rate <= after_first);

        for _ in 0..40 {
            now += ADAPTIVE_ADJUST_INTERVAL;
            controller.tick(now);
        }
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
    }

    #[test]
    fn adaptive_rate_adjusts_at_most_once_per_interval() {
        let mut controller = RateController::new();
        let mut now = std::time::Instant::now();
        controller.tick(now);
        controller.mark_congested();
        now += Duration::from_millis(50);
        controller.tick(now);
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
        now += Duration::from_millis(50);
        controller.tick(now);
        assert!(controller.rate < TB_RATE_BYTES_PER_SEC);
        assert!(!controller.congested);
    }

    #[test]
    fn adaptive_token_refill_uses_per_stream_rate() {
        let mut state = UplinkState::new();
        let mut now = std::time::Instant::now();
        state.update_tokens(2, now);
        assert_eq!(state.rates.len(), 2);

        state.tokens[0] = 0.0;
        state.tokens[1] = 0.0;
        state.rates[0].rate = ADAPTIVE_MIN_RATE_BYTES_PER_SEC;
        state.rates[1].rate = ADAPTIVE_MAX_RATE_BYTES_PER_SEC;
        state.rates[0].last_adjust = Some(now);
        state.rates[1].last_adjust = Some(now);
        state.last_update = Some(now);

        now += Duration::from_millis(10);
        state.update_tokens(2, now);
        let low = state.tokens[0];
        let high = state.tokens[1];
        assert!(high > low);
        let expected = (ADAPTIVE_MAX_RATE_BYTES_PER_SEC - ADAPTIVE_MIN_RATE_BYTES_PER_SEC) * 0.010;
        assert!((high - low - expected).abs() < 1.0);
    }

    #[test]
    fn dispatcher_keeps_a_flow_on_one_worker_until_its_burst_is_spent() {
        let (dispatcher, _return_rx) = test_dispatcher();
        let pool = PacketPool::new(16);
        let mut workers = Vec::new();
        let mut receivers = Vec::new();
        for id in 0..3 {
            let (worker, latency, priority, bulk) = channels(id, 16);
            workers.push(worker);
            receivers.push((latency, priority, bulk));
        }
        dispatcher.workers.store(Arc::new(workers));

        // FLOW_BURST_BYTES / (1200 + WRAP_OVERHEAD) = 12 packets fit in one burst.
        let burst = FLOW_BURST_BYTES / (1_200 + WRAP_OVERHEAD);
        assert_eq!(burst, 12);
        for sequence in 0..=burst {
            dispatcher.dispatch(tcp_packet(&pool, 50_000, sequence as u32));
        }

        let (first_latency, first_priority, first_bulk) = &receivers[0];
        assert_eq!(first_latency.len(), 0);
        assert_eq!(first_priority.len(), 0);
        assert_eq!(first_bulk.len(), burst);
        for sequence in 0..burst {
            assert_eq!(
                packet_sequence(&first_bulk.try_recv().unwrap()),
                sequence as u32
            );
        }
        assert_eq!(receivers[1].0.len(), 0);
        assert_eq!(receivers[1].1.len(), 0);
        assert_eq!(receivers[1].2.len(), 1);
        assert_eq!(
            packet_sequence(&receivers[1].2.try_recv().unwrap()),
            burst as u32
        );
        assert_eq!(receivers[2].0.len(), 0);
        assert_eq!(receivers[2].1.len(), 0);
        assert_eq!(receivers[2].2.len(), 0);
        assert_eq!(pool.available(), pool.capacity());
    }

    #[test]
    fn saturated_selected_queue_never_redirects_a_packet_to_another_worker() {
        let (dispatcher, _return_rx) = test_dispatcher();
        let pool = PacketPool::new(2);
        let (first, _first_latency, _first_priority, first_bulk) = channels(0, 1);
        let (second, second_latency, second_priority, second_bulk) = channels(1, 1);
        dispatcher.workers.store(Arc::new(vec![first, second]));

        dispatcher.dispatch(tcp_packet(&pool, 50_000, 1));
        dispatcher.dispatch(tcp_packet(&pool, 50_000, 2));

        assert_eq!(packet_sequence(&first_bulk.try_recv().unwrap()), 2);
        assert!(second_latency.try_recv().is_none());
        assert!(second_priority.try_recv().is_none());
        assert!(second_bulk.try_recv().is_none());
        assert_eq!(pool.available(), pool.capacity());
    }

    fn packet_sequence(packet: &PacketBuf) -> u32 {
        u32::from_be_bytes(packet.as_slice()[24..28].try_into().unwrap_or_default())
    }

    #[tokio::test(start_paused = true)]
    async fn direct_downlink_preserves_late_tcp_retransmit() {
        let (dispatcher, return_rx) = test_dispatcher();
        let pool = PacketPool::new(4);
        dispatcher.return_packet(tcp_packet(&pool, 50_000, 0));
        dispatcher.return_packet(tcp_packet(&pool, 50_000, 2_320));
        assert_eq!(packet_sequence(&return_rx.try_recv().unwrap()), 0);
        assert_eq!(packet_sequence(&return_rx.try_recv().unwrap()), 2_320);
        tokio::time::advance(Duration::from_millis(81)).await;
        dispatcher.return_packet(tcp_packet(&pool, 50_000, 1_160));
        assert_eq!(packet_sequence(&return_rx.try_recv().unwrap()), 1_160);
        assert_eq!(pool.available(), pool.capacity());
    }

    #[test]
    fn unregister_and_recover_one_worker_never_changes_its_siblings() {
        let (dispatcher, _return_rx) = test_dispatcher();
        for id in 0..9 {
            dispatcher.register(channels(id, 1).0);
        }
        for cycle in 0..10_000 {
            let id = cycle % 9;
            dispatcher.unregister(id, id as u64 + 1);
            assert_eq!(dispatcher.active_count(), 8);
            for sibling in 0..9 {
                assert_eq!(dispatcher.worker(sibling).is_some(), sibling != id);
            }
            dispatcher.register(channels(id, 1).0);
            assert_eq!(dispatcher.active_count(), 9);
        }
    }

    #[test]
    fn stale_registration_drop_cannot_unregister_replacement() {
        let (dispatcher, _return_rx) = test_dispatcher();
        let mut old = channels(4, 1).0;
        old.incarnation_id = 40;
        dispatcher.register(old);
        let mut replacement = channels(4, 1).0;
        replacement.incarnation_id = 41;
        dispatcher.register(replacement);

        dispatcher.unregister(4, 40);

        assert_eq!(dispatcher.active_count(), 1);
        assert_eq!(dispatcher.worker(4).unwrap().incarnation_id, 41);
    }

    #[test]
    fn overload_keeps_queues_and_packet_memory_strictly_bounded() {
        let (dispatcher, _return_rx) = test_dispatcher();
        let pool = PacketPool::new(64);
        let mut workers = Vec::new();
        let mut receivers = Vec::new();
        for id in 0..9 {
            let (worker, latency, priority, bulk) = channels(id, 1);
            workers.push(worker);
            receivers.push((latency, priority, bulk));
        }
        dispatcher.workers.store(Arc::new(workers));
        for sequence in 0..100_000 {
            let mut packet = pool.try_acquire().unwrap();
            packet
                .set_read_len(if sequence % 2 == 0 { 100 } else { 1_000 })
                .unwrap();
            dispatcher.dispatch(packet);
        }
        let mut queued = 0;
        for (latency, priority, bulk) in &receivers {
            queued += latency.len() + priority.len() + bulk.len();
        }
        assert!(queued <= 18);
        assert!(queued > 0);
        assert_eq!(pool.available() + queued, pool.capacity());
        for (latency, priority, bulk) in &receivers {
            while latency.try_recv().is_some() {}
            while priority.try_recv().is_some() {}
            while bulk.try_recv().is_some() {}
        }
        assert_eq!(pool.available(), pool.capacity());
    }

    #[test]
    fn saturated_queue_replaces_oldest_with_newest() {
        let pool = PacketPool::new(4);
        let (sender, receiver) = packet_channel(2, true);
        for value in 1..=3 {
            let mut packet = pool.acquire();
            packet.set_read_len(1).unwrap();
            packet.as_mut_slice()[0] = value;
            assert!(sender.force_send(packet).is_ok());
        }
        assert_eq!(receiver.try_recv().unwrap().as_slice(), [2]);
        assert_eq!(receiver.try_recv().unwrap().as_slice(), [3]);
        assert!(receiver.try_recv().is_none());
    }

    #[test]
    fn queued_packets_remain_available_until_the_queue_is_purged() {
        let pool = PacketPool::new(2);
        let (sender, receiver) = packet_channel(2, true);
        assert!(sender.force_send(pool.acquire()).is_ok());
        assert!(receiver.try_recv().is_some());
        assert_eq!(pool.available(), pool.capacity());
    }

    #[test]
    fn suspend_and_resume_purge_previous_network_epoch() {
        let pool = PacketPool::new(3);
        let (sender, receiver) = packet_channel(3, true);
        assert!(sender.force_send(pool.acquire()).is_ok());
        receiver.suspend();
        assert_eq!(pool.available(), pool.capacity());
        assert!(sender.force_send(pool.acquire()).is_err());
        receiver.resume();
        assert!(sender.force_send(pool.acquire()).is_ok());
        assert!(receiver.try_recv().is_some());
        assert!(receiver.try_recv().is_none());
    }

    proptest! {
        #[test]
        fn packet_queue_matches_bounded_reference_model(
            actions in proptest::collection::vec((any::<u8>(), any::<u8>()), 1..=2_000)
        ) {
            prop_assert!(queue_trace_matches(&actions, true));
        }
    }

    #[test]
    fn queue_oracle_detects_keep_oldest_mutation() {
        let actions = [
            (1, 1),
            (1, 2),
            (1, 3),
            (1, 4),
            (1, 5),
            (1, 6),
            (1, 7),
            (1, 8),
            (2, 0),
        ];
        assert!(queue_trace_matches(&actions, true));
        assert!(!queue_trace_matches(&actions, false));
    }

    #[test]
    fn deterministic_queue_fault_generator_is_reproducible_and_complete() {
        let first = deterministic_queue_actions(0x1234_5678_9abc_def0, 4_096);
        let second = deterministic_queue_actions(0x1234_5678_9abc_def0, 4_096);
        let different = deterministic_queue_actions(0x1234_5678_9abc_def1, 4_096);
        assert_eq!(first, second);
        assert_ne!(first, different);
        let mut covered = [false; 6];
        for &(operation, _) in &first {
            covered[usize::from(operation % 6)] = true;
        }
        assert!(covered.into_iter().all(|value| value));
        let (matches, coverage) = queue_trace_outcome(&first, true);
        assert!(matches);
        assert!(coverage.complete());
    }

    #[test]
    fn queue_coverage_oracle_rejects_each_missing_state_transition() {
        let complete = QueueCoverage {
            full_rejection: 1,
            forced_replacement: 1,
            inactive_rejection: 1,
            suspended: 1,
            resumed: 1,
            purged: 1,
        };
        assert!(complete.complete());
        for index in 0..6 {
            let mut mutated = complete;
            match index {
                0 => mutated.full_rejection = 0,
                1 => mutated.forced_replacement = 0,
                2 => mutated.inactive_rejection = 0,
                3 => mutated.suspended = 0,
                4 => mutated.resumed = 0,
                _ => mutated.purged = 0,
            }
            assert!(!mutated.complete());
        }
    }

    #[test]
    #[ignore = "explicit deterministic stability soak"]
    fn deterministic_queue_chaos_soak() {
        let seconds = std::env::var("CSQTT_SOAK_SECONDS")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(120)
            .max(1);
        let first_seed = std::env::var("CSQTT_SOAK_SEED")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0);
        let actions_per_seed = std::env::var("CSQTT_QUEUE_SOAK_ACTIONS")
            .ok()
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or(4_096)
            .max(14);
        let started = Instant::now();
        let mut offset = 0u64;
        loop {
            let seed = first_seed.wrapping_add(offset);
            let actions = deterministic_queue_actions(seed, actions_per_seed);
            let (matches, coverage) = queue_trace_outcome(&actions, true);
            assert!(
                matches && coverage.complete(),
                "packet queue diverged at reproducible seed {seed}"
            );
            offset = offset.wrapping_add(1);
            if started.elapsed() >= Duration::from_secs(seconds) {
                break;
            }
        }
    }

    #[test]
    fn concurrent_send_suspend_resume_storm_never_replays_previous_epoch() {
        let pool = PacketPool::new(4_096);
        let (sender, receiver) = packet_channel(64, true);
        let barrier = Arc::new(std::sync::Barrier::new(9));
        let mut threads = Vec::new();
        for thread in 0..8u8 {
            let sender = sender.clone();
            let pool = pool.clone();
            let barrier = barrier.clone();
            threads.push(std::thread::spawn(move || {
                barrier.wait();
                for sequence in 0..25_000u32 {
                    let mut packet = pool.acquire();
                    packet.set_read_len(2).unwrap();
                    packet.as_mut_slice()[0] = thread;
                    packet.as_mut_slice()[1] = sequence as u8;
                    drop(sender.force_send(packet));
                }
            }));
        }
        barrier.wait();
        for cycle in 0..10_000 {
            if cycle % 3 == 0 {
                receiver.suspend();
            } else if cycle % 3 == 1 {
                receiver.resume();
            } else {
                while receiver.try_recv().is_some() {}
            }
        }
        for thread in threads {
            thread.join().unwrap();
        }
        receiver.suspend();
        receiver.resume();
        let mut marker = pool.acquire();
        marker.set_read_len(1).unwrap();
        marker.as_mut_slice()[0] = 0xa5;
        assert!(sender.force_send(marker).is_ok());
        assert_eq!(receiver.try_recv().unwrap().as_slice(), [0xa5]);
        assert!(receiver.try_recv().is_none());
        drop(sender);
        drop(receiver);
        assert_eq!(pool.available(), pool.capacity());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn receiver_notification_race_has_no_lost_wakeup() {
        let pool = PacketPool::new(1);
        for sequence in 0..2_000 {
            let (sender, receiver) = packet_channel(1, true);
            let cancel = CancellationToken::new();
            let waiter_cancel = cancel.clone();
            let waiter = tokio::spawn(async move { receiver.recv(&waiter_cancel).await });
            if sequence % 2 == 0 {
                tokio::task::yield_now().await;
            }
            assert!(sender.force_send(pool.acquire()).is_ok());
            let packet = tokio::time::timeout(Duration::from_millis(1000), waiter)
                .await
                .unwrap()
                .unwrap();
            assert!(packet.is_some());
            drop(packet);
            drop(sender);
            assert_eq!(pool.available(), pool.capacity());
        }
    }

    #[test]
    fn receiver_drop_race_releases_every_buffer() {
        for _ in 0..100 {
            let pool = PacketPool::new(32);
            let (sender, receiver) = packet_channel(8, true);
            let barrier = Arc::new(std::sync::Barrier::new(9));
            let mut threads = Vec::new();
            for _ in 0..8 {
                let sender = sender.clone();
                let pool = pool.clone();
                let barrier = barrier.clone();
                threads.push(std::thread::spawn(move || {
                    barrier.wait();
                    drop(sender.force_send(pool.acquire()));
                }));
            }
            barrier.wait();
            drop(receiver);
            for thread in threads {
                thread.join().unwrap();
            }
            drop(sender);
            assert_eq!(pool.available(), pool.capacity());
        }
    }

    #[tokio::test]
    async fn critical_task_panic_is_contained_and_cancels_runtime() {
        let cancel = CancellationToken::new();
        let task = spawn_critical("injected", cancel.clone(), async {
            panic!("injected dispatcher panic");
        });
        tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap();
        assert!(cancel.is_cancelled());
    }

    #[tokio::test]
    async fn successful_critical_task_does_not_cancel_runtime() {
        let cancel = CancellationToken::new();
        spawn_critical("completed", cancel.clone(), async {})
            .await
            .unwrap();
        assert!(!cancel.is_cancelled());
    }

    #[tokio::test]
    async fn dispatcher_shutdown_completes_during_total_packet_deficit() {
        let pool = PacketPool::new(1);
        let held = pool.acquire();
        let cancel = CancellationToken::new();
        let (dispatcher, _) = Dispatcher::start(
            "127.0.0.1:0",
            None,
            pool.clone(),
            Arc::new(Stats::default()),
            cancel,
        )
        .await
        .unwrap();
        tokio::time::timeout(Duration::from_millis(100), dispatcher.shutdown())
            .await
            .unwrap();
        drop(held);
        assert_eq!(pool.available(), pool.capacity());
    }

    #[tokio::test]
    async fn direct_udp_batches_preserve_idle_pool_and_fifo_order() {
        let pool = PacketPool::new(128);
        let stats = Arc::new(Stats::default());
        let cancel = CancellationToken::new();
        let (dispatcher, port) =
            Dispatcher::start("127.0.0.1:0", None, pool.clone(), stats, cancel)
                .await
                .unwrap();
        let (worker, latency_rx, _priority_rx, _bulk_rx) = channels(0, 128);
        dispatcher.register(worker);

        tokio::task::yield_now().await;
        tokio::time::sleep(Duration::from_millis(10)).await;
        assert_eq!(pool.available(), pool.capacity());

        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let destination: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
        let payloads: Vec<[u8; 1]> = (0..udp_batch::MAX_DATAGRAMS)
            .map(|index| [index as u8])
            .collect();
        let datagrams: Vec<&[u8]> = payloads.iter().map(|payload| payload.as_slice()).collect();
        udp_batch::send_to(&peer, destination, &datagrams)
            .await
            .unwrap();

        let receive_cancel = CancellationToken::new();
        for expected in 0..udp_batch::MAX_DATAGRAMS {
            let packet =
                tokio::time::timeout(Duration::from_secs(1), latency_rx.recv(&receive_cancel))
                    .await
                    .expect("direct UDP packet did not reach its worker")
                    .expect("worker input closed unexpectedly");
            assert_eq!(packet.as_slice(), [expected as u8]);
        }

        for expected in 0..udp_batch::MAX_DATAGRAMS {
            let mut packet = pool.acquire();
            packet.set_read_len(1).unwrap();
            packet.as_mut_slice()[0] = expected as u8 + 0x80;
            dispatcher.return_packet(packet);
        }
        let mut buffer = [0u8; 8];
        for expected in 0..udp_batch::MAX_DATAGRAMS {
            let (length, source) =
                tokio::time::timeout(Duration::from_secs(1), peer.recv_from(&mut buffer))
                    .await
                    .expect("direct UDP return packet timed out")
                    .unwrap();
            assert_eq!(source, destination);
            assert_eq!(&buffer[..length], &[expected as u8 + 0x80]);
        }

        dispatcher.shutdown().await;
    }
}

#[cfg(test)]
mod throughput_soak_tests;
