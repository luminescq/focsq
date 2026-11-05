// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

//! [FOCSQ] Прямой TCP/UDP для пакетов, уже выбранных split-политикой.
//! TCP-стек и сборка IPv4 ответов адаптированы из CSQTT rust-client/direct_relay.rs.
//! Windows-сокеты привязываются к исходному маршруту до connect/send, иначе
//! собственное соединение реле снова попадёт в Wintun и образует петлю.

use crate::packet::{PacketBuf, PacketPool};
use futures::{SinkExt, StreamExt};
use std::{
    collections::HashMap,
    io,
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    os::windows::io::AsRawSocket,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU16, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    net::{TcpSocket, UdpSocket},
    sync::mpsc,
};
use tokio_util::sync::CancellationToken;

// [FOCSQ] Все очереди и таблицы ограничены независимо от скорости приложений.
const TCP_FLOWS: usize = 256;
const UDP_FLOWS: usize = 512;
const STACK_QUEUE: usize = 128;
const TCP_IDLE: Duration = Duration::from_secs(300);
const UDP_IDLE: Duration = Duration::from_secs(120);
const MTU: usize = 1300;
type FlowKey = (SocketAddrV4, SocketAddrV4);
type TcpFlows = Arc<Mutex<HashMap<FlowKey, Arc<TcpFlow>>>>;
type UdpFlows = Arc<Mutex<HashMap<UdpKey, Arc<UdpFlow>>>>;

// [FOCSQ] Диагностика прямого пути ограничена по частоте и не содержит полезной нагрузки.
fn log_failure(stage: &str, destination: SocketAddrV4, error: impl std::fmt::Display) {
    static FAILURES: AtomicU64 = AtomicU64::new(0);
    let count = FAILURES.fetch_add(1, Ordering::Relaxed) + 1;
    if count <= 20 || count.is_multiple_of(1000) {
        crate::log_error!("[SPLIT] stage={stage} destination={destination} failures={count}: {error}");
    }
}

// [FOCSQ] DNS сохраняет логический адрес ответа, но может идти к исходному DNS.
#[derive(Clone, Copy, Hash, PartialEq, Eq)]
struct UdpKey {
    source: SocketAddrV4,
    destination: SocketAddrV4,
    upstream: SocketAddrV4,
}

struct TcpFlow {
    upstream: SocketAddrV4,
    last_seen: Mutex<Instant>,
    accepted: AtomicBool,
    cancel: CancellationToken,
}

struct UdpFlow {
    socket: UdpSocket,
    last_seen: Mutex<Instant>,
    cancel: CancellationToken,
}

pub struct DirectRelay {
    pool: Arc<PacketPool>,
    inbound: mpsc::Sender<PacketBuf>,
    tcp_packets: mpsc::Sender<Vec<u8>>,
    tcp_flows: TcpFlows,
    udp_flows: UdpFlows,
    packet_id: Arc<AtomicU16>,
    cancel: CancellationToken,
}

impl Drop for DirectRelay {
    fn drop(&mut self) {
        // [FOCSQ] Дочерний токен закрывает только реле, не отменяя всё ядро.
        self.cancel.cancel();
    }
}

impl DirectRelay {
    pub fn new(
        pool: Arc<PacketPool>,
        inbound: mpsc::Sender<PacketBuf>,
        cancel: CancellationToken,
    ) -> io::Result<Self> {
        let cancel = cancel.child_token();
        // [FOCSQ] Берём тот же TCP-стек, что в мобильном клиенте, с меньшими буферами.
        let (stack, runner, _, listener) = netstack_smoltcp::StackBuilder::default()
            .enable_tcp(true)
            .enable_udp(false)
            .enable_icmp(false)
            .mtu(MTU)
            .tcp_recv_buffer_size(32768)
            .tcp_send_buffer_size(32768)
            .stack_buffer_size(STACK_QUEUE)
            .tcp_buffer_size(STACK_QUEUE)
            .build()?;
        let (mut sink, mut stream) = stack.split();
        let (tcp_packets, mut packets) = mpsc::channel::<Vec<u8>>(STACK_QUEUE);
        let tcp_flows: TcpFlows = Arc::new(Mutex::new(HashMap::new()));

        // [FOCSQ] Runner завершается после закрытия обеих половин stack. Его нельзя
        // бросать по select(cancel): внутри библиотеки живут ещё два дочерних task.
        if let Some(runner) = runner {
            let failed = cancel.clone();
            tokio::spawn(async move {
                let _ = runner.await;
                failed.cancel();
            });
        }
        let ingress_cancel = cancel.clone();
        tokio::spawn(async move {
            loop {
                let packet = tokio::select! {
                    biased;
                    _ = ingress_cancel.cancelled() => break,
                    packet = packets.recv() => match packet { Some(packet) => packet, None => break },
                };
                tokio::select! {
                    biased;
                    _ = ingress_cancel.cancelled() => break,
                    result = sink.send(packet) => if result.is_err() { ingress_cancel.cancel(); break; },
                }
            }
        });
        let egress_cancel = cancel.clone();
        let egress_pool = pool.clone();
        let egress_inbound = inbound.clone();
        tokio::spawn(async move {
            loop {
                let frame = tokio::select! {
                    biased;
                    _ = egress_cancel.cancelled() => break,
                    frame = stream.next() => match frame { Some(Ok(frame)) => frame, _ => break },
                };
                let Some(mut packet) = egress_pool.try_acquire() else {
                    continue;
                };
                let Ok(buffer) = packet.extend_tail(frame.len()) else {
                    continue;
                };
                buffer.copy_from_slice(&frame);
                // [FOCSQ] Медленный TUN не задерживает остановку клиента.
                tokio::select! {
                    biased;
                    _ = egress_cancel.cancelled() => break,
                    result = egress_inbound.send(packet) => if result.is_err() { egress_cancel.cancel(); break; },
                }
            }
        });

        if let Some(mut listener) = listener {
            let flows = tcp_flows.clone();
            let listener_cancel = cancel.clone();
            tokio::spawn(async move {
                loop {
                    let accepted = tokio::select! {
                        biased;
                        _ = listener_cancel.cancelled() => break,
                        accepted = listener.next() => accepted,
                    };
                    let Some((mut client, SocketAddr::V4(source), SocketAddr::V4(destination))) =
                        accepted
                    else {
                        break;
                    };
                    let key = (source, destination);
                    let flow = flows
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get(&key)
                        .cloned();
                    let Some(flow) = flow else { continue };
                    if flow.cancel.is_cancelled() || flow.accepted.swap(true, Ordering::AcqRel) {
                        continue;
                    }
                    let flows = flows.clone();
                    tokio::spawn(async move {
                        let _lease = TcpLease {
                            flows,
                            key,
                            flow: flow.clone(),
                        };
                        // [FOCSQ] Интерфейс выбирается до TCP connect, включая корпоративные маршруты.
                        let connect = async {
                            let socket = TcpSocket::new_v4()?;
                            crate::split_windows::bind_direct(
                                socket.as_raw_socket(),
                                *flow.upstream.ip(),
                            )?;
                            socket.connect(SocketAddr::V4(flow.upstream)).await
                        };
                        let server = tokio::select! {
                            biased;
                            _ = flow.cancel.cancelled() => return,
                            server = tokio::time::timeout(Duration::from_secs(10), connect) => server,
                        };
                        let mut server = match server {
                            Ok(Ok(server)) => server,
                            Ok(Err(error)) => { log_failure("tcp_connect", flow.upstream, error); return; }
                            Err(error) => { log_failure("tcp_timeout", flow.upstream, error); return; }
                        };
                        let _ = server.set_nodelay(true);
                        // [FOCSQ] Копирование сохраняет half-close, отмена рвёт обе стороны.
                        tokio::select! {
                            biased;
                            _ = flow.cancel.cancelled() => {},
                            _ = tokio::io::copy_bidirectional_with_sizes(&mut client, &mut server, 16384, 16384) => {},
                        }
                    });
                }
            });
        }

        // [FOCSQ] Неактивные соединения освобождают ограниченный бюджет сессий.
        let idle_flows = tcp_flows.clone();
        let idle_cancel = cancel.clone();
        tokio::spawn(async move {
            let mut timer = tokio::time::interval(Duration::from_secs(30));
            loop {
                tokio::select! {
                    biased;
                    _ = idle_cancel.cancelled() => break,
                    _ = timer.tick() => {},
                }
                let mut flows = idle_flows.lock().unwrap_or_else(|e| e.into_inner());
                flows.retain(|_, flow| {
                    let idle = flow
                        .last_seen
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .elapsed();
                    if idle >= TCP_IDLE {
                        flow.cancel.cancel();
                    }
                    // [FOCSQ] Не принятый стеком SYN не занимает слот бесконечно.
                    flow.accepted.load(Ordering::Acquire) || idle < Duration::from_secs(30)
                });
            }
            for flow in idle_flows
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .values()
            {
                flow.cancel.cancel();
            }
        });
        Ok(Self {
            pool,
            inbound,
            tcp_packets,
            tcp_flows,
            udp_flows: Arc::new(Mutex::new(HashMap::new())),
            packet_id: Arc::new(AtomicU16::new(1)),
            cancel,
        })
    }

    /// [FOCSQ] false означает неподдержанный/повреждённый пакет. После решения
    /// Direct вызывающая сторона всё равно не должна отправлять его в туннель.
    pub async fn forward(&self, packet: &[u8]) -> bool {
        self.forward_to(packet, None).await
    }

    /// [FOCSQ] Системный DNS доступен напрямую; приложения видят исходный адрес DNS.
    pub async fn forward_dns(&self, packet: &[u8], upstream: Ipv4Addr) -> bool {
        let Some(parsed) = ParsedPacket::parse(packet) else {
            return false;
        };
        if parsed.destination.port() != 53 {
            return false;
        }
        self.forward_to(packet, Some(SocketAddrV4::new(upstream, 53)))
            .await
    }

    async fn forward_to(&self, packet: &[u8], upstream: Option<SocketAddrV4>) -> bool {
        if self.cancel.is_cancelled() {
            return false;
        }
        let Some(parsed) = ParsedPacket::parse(packet) else {
            return false;
        };
        let upstream = upstream.unwrap_or(parsed.destination);
        if parsed.protocol == 17 {
            return self
                .forward_udp(
                    UdpKey {
                        source: parsed.source,
                        destination: parsed.destination,
                        upstream,
                    },
                    parsed.payload,
                )
                .await;
        }
        let key = (parsed.source, parsed.destination);
        let mut flows = self.tcp_flows.lock().unwrap_or_else(|e| e.into_inner());
        let syn = parsed.flags & 0x12 == 0x02;
        if let Some(flow) = flows.get(&key) {
            *flow.last_seen.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
            // [FOCSQ] Повторный SYN не создаёт второй smoltcp socket того же потока.
            if syn || flow.cancel.is_cancelled() {
                return true;
            }
        } else {
            if !syn || flows.len() >= TCP_FLOWS {
                if syn { log_failure("tcp_limit", parsed.destination, "достигнут лимит прямых TCP-соединений"); }
                return true;
            }
            flows.insert(
                key,
                Arc::new(TcpFlow {
                    upstream,
                    last_seen: Mutex::new(Instant::now()),
                    accepted: AtomicBool::new(false),
                    cancel: self.cancel.child_token(),
                }),
            );
        }
        let queued = self
            .tcp_packets
            .try_send(packet[..parsed.total_len].to_vec())
            .is_ok();
        if !queued {
            log_failure("tcp_queue", parsed.destination, "очередь прямого TCP заполнена или закрыта");
            if syn { flows.remove(&key); }
        }
        // [FOCSQ] При перегрузке direct-пакет теряется, не утекая на VPN-сервер.
        true
    }

    async fn forward_udp(&self, key: UdpKey, payload: &[u8]) -> bool {
        let flow = {
            let mut flows = self.udp_flows.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(flow) = flows.get(&key) {
                flow.clone()
            } else {
                if flows.len() >= UDP_FLOWS {
                    log_failure("udp_limit", key.upstream, "достигнут лимит прямых UDP-соединений");
                    return true;
                }
                let create = || -> io::Result<UdpSocket> {
                    let socket = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
                    socket.set_nonblocking(true)?;
                    crate::split_windows::bind_direct(socket.as_raw_socket(), *key.upstream.ip())?;
                    // [FOCSQ] Connected UDP принимает ответы только выбранного адресата.
                    socket.connect(key.upstream)?;
                    UdpSocket::from_std(socket)
                };
                let socket = match create() {
                    Ok(socket) => socket,
                    Err(error) => { log_failure("udp_connect", key.upstream, error); return true; }
                };
                let flow = Arc::new(UdpFlow {
                    socket,
                    last_seen: Mutex::new(Instant::now()),
                    cancel: self.cancel.child_token(),
                });
                flows.insert(key, flow.clone());
                self.spawn_udp_reader(key, flow.clone());
                flow
            }
        };
        *flow.last_seen.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
        tokio::select! {
            biased;
            _ = flow.cancel.cancelled() => {},
            result = tokio::time::timeout(Duration::from_secs(2), flow.socket.send(payload)) => {
                match result {
                    Ok(Ok(_)) => {},
                    Ok(Err(error)) => { log_failure("udp_send", key.upstream, error); flow.cancel.cancel(); }
                    Err(error) => { log_failure("udp_timeout", key.upstream, error); flow.cancel.cancel(); }
                }
            },
        }
        true
    }

    fn spawn_udp_reader(&self, key: UdpKey, flow: Arc<UdpFlow>) {
        let flows = self.udp_flows.clone();
        let pool = self.pool.clone();
        let inbound = self.inbound.clone();
        let packet_id = self.packet_id.clone();
        tokio::spawn(async move {
            let _lease = UdpLease {
                flows,
                key,
                flow: flow.clone(),
            };
            // [FOCSQ] Один буфер на сессию ограничивает память даже для больших DNS/UDP ответов.
            let mut bytes = vec![0u8; 65536];
            let mut timer = tokio::time::interval(Duration::from_secs(30));
            loop {
                let length = tokio::select! {
                    biased;
                    _ = flow.cancel.cancelled() => break,
                    _ = timer.tick() => {
                        if flow.last_seen.lock().unwrap_or_else(|e| e.into_inner()).elapsed() >= UDP_IDLE { break }
                        continue;
                    },
                    result = flow.socket.recv(&mut bytes) => match result {
                        Ok(length) => length,
                        Err(error) if error.kind() == io::ErrorKind::Interrupted || error.kind() == io::ErrorKind::ConnectionRefused => continue,
                        Err(_) => break,
                    },
                };
                *flow.last_seen.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
                let id = packet_id.fetch_add(1, Ordering::Relaxed);
                if !return_udp(
                    &pool,
                    &inbound,
                    &flow.cancel,
                    key.destination,
                    key.source,
                    &bytes[..length],
                    id,
                )
                .await
                {
                    break;
                }
            }
        });
    }
}

// [FOCSQ] Lease удаляет только своё поколение потока при ошибке, panic или отмене task.
struct TcpLease {
    flows: TcpFlows,
    key: FlowKey,
    flow: Arc<TcpFlow>,
}
impl Drop for TcpLease {
    fn drop(&mut self) {
        let mut flows = self.flows.lock().unwrap_or_else(|e| e.into_inner());
        if flows
            .get(&self.key)
            .is_some_and(|flow| Arc::ptr_eq(flow, &self.flow))
        {
            flows.remove(&self.key);
        }
    }
}
struct UdpLease {
    flows: UdpFlows,
    key: UdpKey,
    flow: Arc<UdpFlow>,
}
impl Drop for UdpLease {
    fn drop(&mut self) {
        let mut flows = self.flows.lock().unwrap_or_else(|e| e.into_inner());
        if flows
            .get(&self.key)
            .is_some_and(|flow| Arc::ptr_eq(flow, &self.flow))
        {
            flows.remove(&self.key);
        }
    }
}

struct ParsedPacket<'a> {
    source: SocketAddrV4,
    destination: SocketAddrV4,
    protocol: u8,
    flags: u8,
    total_len: usize,
    payload: &'a [u8],
}
impl<'a> ParsedPacket<'a> {
    // [FOCSQ] Проверяем все длины до передачи библиотеке и отбрасываем IP-фрагменты.
    fn parse(packet: &'a [u8]) -> Option<Self> {
        if packet.len() < 20 || packet[0] >> 4 != 4 {
            return None;
        }
        let header = usize::from(packet[0] & 15) * 4;
        let total_len = usize::from(u16::from_be_bytes([packet[2], packet[3]]));
        if header < 20
            || total_len < header
            || total_len > packet.len()
            || u16::from_be_bytes([packet[6], packet[7]]) & 0x3fff != 0
        {
            return None;
        }
        let segment = &packet[header..total_len];
        let protocol = packet[9];
        let (flags, payload) = match protocol {
            6 if segment.len() >= 20 => {
                let tcp_header = usize::from(segment[12] >> 4) * 4;
                if tcp_header < 20 || tcp_header > segment.len() {
                    return None;
                }
                (segment[13], &segment[tcp_header..])
            }
            17 if segment.len() >= 8 => {
                let length = usize::from(u16::from_be_bytes([segment[4], segment[5]]));
                if length < 8 || length > segment.len() {
                    return None;
                }
                (0, &segment[8..length])
            }
            _ => return None,
        };
        let source_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
        let destination_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
        if destination_ip.is_unspecified()
            || destination_ip.is_multicast()
            || destination_ip.is_broadcast()
        {
            return None;
        }
        Some(Self {
            source: SocketAddrV4::new(source_ip, u16::from_be_bytes([segment[0], segment[1]])),
            destination: SocketAddrV4::new(
                destination_ip,
                u16::from_be_bytes([segment[2], segment[3]]),
            ),
            protocol,
            flags,
            total_len,
            payload,
        })
    }
}

async fn return_udp(
    pool: &Arc<PacketPool>,
    inbound: &mpsc::Sender<PacketBuf>,
    cancel: &CancellationToken,
    source: SocketAddrV4,
    destination: SocketAddrV4,
    payload: &[u8],
    id: u16,
) -> bool {
    if payload.len() > 65507 {
        return true;
    }
    // [FOCSQ] Большой UDP ответ фрагментируется по MTU Wintun вместо обрезки данных.
    let mut segment = vec![0u8; payload.len() + 8];
    segment[..2].copy_from_slice(&source.port().to_be_bytes());
    segment[2..4].copy_from_slice(&destination.port().to_be_bytes());
    let length = segment.len() as u16;
    segment[4..6].copy_from_slice(&length.to_be_bytes());
    segment[8..].copy_from_slice(payload);
    let sum = transport_checksum(*source.ip(), *destination.ip(), 17, &segment);
    segment[6..8].copy_from_slice(&(if sum == 0 { 0xffff } else { sum }).to_be_bytes());
    let chunk_size = (MTU - 20) / 8 * 8;
    for (index, chunk) in segment.chunks(chunk_size).enumerate() {
        let Some(mut packet) = pool.try_acquire() else {
            return true;
        };
        let Ok(buffer) = packet.extend_tail(20 + chunk.len()) else {
            return true;
        };
        buffer.fill(0);
        buffer[0] = 0x45;
        buffer[2..4].copy_from_slice(&((20 + chunk.len()) as u16).to_be_bytes());
        buffer[4..6].copy_from_slice(&id.to_be_bytes());
        let offset = index * chunk_size;
        let flags = ((offset / 8) as u16)
            | if offset + chunk.len() < segment.len() {
                0x2000
            } else {
                0
            };
        buffer[6..8].copy_from_slice(&flags.to_be_bytes());
        buffer[8] = 64;
        buffer[9] = 17;
        buffer[12..16].copy_from_slice(&source.ip().octets());
        buffer[16..20].copy_from_slice(&destination.ip().octets());
        let sum = checksum(&buffer[..20]);
        buffer[10..12].copy_from_slice(&sum.to_be_bytes());
        buffer[20..].copy_from_slice(chunk);
        tokio::select! {
            biased;
            _ = cancel.cancelled() => return false,
            result = inbound.send(packet) => if result.is_err() { return false },
        }
    }
    true
}

// [FOCSQ] IPv4 и TCP/UDP checksum взяты из мобильного direct_relay; нечётный байт дополнен нулём.
fn checksum(bytes: &[u8]) -> u16 {
    finish_checksum(sum_words(bytes))
}
fn sum_words(bytes: &[u8]) -> u32 {
    let mut chunks = bytes.chunks_exact(2);
    let mut sum = chunks
        .by_ref()
        .map(|pair| u32::from(u16::from_be_bytes([pair[0], pair[1]])))
        .sum::<u32>();
    if let Some(&byte) = chunks.remainder().first() {
        sum += u32::from(byte) << 8;
    }
    sum
}
fn finish_checksum(mut sum: u32) -> u16 {
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
fn transport_checksum(
    source: Ipv4Addr,
    destination: Ipv4Addr,
    protocol: u8,
    segment: &[u8],
) -> u16 {
    finish_checksum(
        sum_words(&source.octets())
            + sum_words(&destination.octets())
            + u32::from(protocol)
            + segment.len() as u32
            + sum_words(segment),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn udp_packet(source: SocketAddrV4, destination: SocketAddrV4, payload: &[u8]) -> Vec<u8> {
        let mut bytes = vec![0u8; 28 + payload.len()];
        bytes[0] = 0x45;
        let length = bytes.len() as u16;
        bytes[2..4].copy_from_slice(&length.to_be_bytes());
        bytes[8] = 64;
        bytes[9] = 17;
        bytes[12..16].copy_from_slice(&source.ip().octets());
        bytes[16..20].copy_from_slice(&destination.ip().octets());
        bytes[20..22].copy_from_slice(&source.port().to_be_bytes());
        bytes[22..24].copy_from_slice(&destination.port().to_be_bytes());
        bytes[24..26].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
        bytes[28..].copy_from_slice(payload);
        bytes
    }

    fn tcp_packet(
        source: SocketAddrV4,
        destination: SocketAddrV4,
        sequence: u32,
        acknowledgement: u32,
        flags: u8,
        payload: &[u8],
    ) -> Vec<u8> {
        let mut bytes = vec![0u8; 40 + payload.len()];
        bytes[0] = 0x45;
        let length = bytes.len() as u16;
        bytes[2..4].copy_from_slice(&length.to_be_bytes());
        bytes[8] = 64;
        bytes[9] = 6;
        bytes[12..16].copy_from_slice(&source.ip().octets());
        bytes[16..20].copy_from_slice(&destination.ip().octets());
        bytes[20..22].copy_from_slice(&source.port().to_be_bytes());
        bytes[22..24].copy_from_slice(&destination.port().to_be_bytes());
        bytes[24..28].copy_from_slice(&sequence.to_be_bytes());
        bytes[28..32].copy_from_slice(&acknowledgement.to_be_bytes());
        bytes[32] = 0x50;
        bytes[33] = flags;
        bytes[34..36].copy_from_slice(&65535u16.to_be_bytes());
        bytes[40..].copy_from_slice(payload);
        let sum = checksum(&bytes[..20]);
        bytes[10..12].copy_from_slice(&sum.to_be_bytes());
        let sum = transport_checksum(*source.ip(), *destination.ip(), 6, &bytes[20..]);
        bytes[36..38].copy_from_slice(&sum.to_be_bytes());
        bytes
    }

    #[tokio::test]
    async fn tcp_syn_ack_and_payload_reach_direct_echo_server() {
        // [FOCSQ] Синтетический TCP-клиент проверяет весь путь smoltcp ↔ прямой сокет.
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let SocketAddr::V4(destination) = listener.local_addr().unwrap() else {
            unreachable!()
        };
        let echo = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut bytes = [0; 4];
            socket.read_exact(&mut bytes).await.unwrap();
            socket.write_all(&bytes).await.unwrap();
        });
        let source = SocketAddrV4::new(Ipv4Addr::new(10, 66, 67, 4), 23456);
        let (inbound, mut replies) = mpsc::channel(32);
        let cancel = CancellationToken::new();
        let relay = DirectRelay::new(PacketPool::new(32), inbound, cancel.clone()).unwrap();
        assert!(
            relay
                .forward(&tcp_packet(source, destination, 100, 0, 0x02, &[]))
                .await
        );
        let syn_ack = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let packet = replies.recv().await.unwrap();
                let parsed = ParsedPacket::parse(packet.as_slice()).unwrap();
                if parsed.flags & 0x12 == 0x12 {
                    break packet;
                }
            }
        })
        .await
        .unwrap();
        let server_sequence = u32::from_be_bytes(syn_ack.as_slice()[24..28].try_into().unwrap());
        assert_eq!(
            u32::from_be_bytes(syn_ack.as_slice()[28..32].try_into().unwrap()),
            101
        );
        assert!(
            relay
                .forward(&tcp_packet(
                    source,
                    destination,
                    101,
                    server_sequence.wrapping_add(1),
                    0x10,
                    &[]
                ))
                .await
        );
        assert!(
            relay
                .forward(&tcp_packet(
                    source,
                    destination,
                    101,
                    server_sequence.wrapping_add(1),
                    0x18,
                    b"ping"
                ))
                .await
        );
        let reply = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let packet = replies.recv().await.unwrap();
                let parsed = ParsedPacket::parse(packet.as_slice()).unwrap();
                if !parsed.payload.is_empty() {
                    break packet;
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(
            ParsedPacket::parse(reply.as_slice()).unwrap().payload,
            b"ping"
        );
        assert_eq!(checksum(&reply.as_slice()[..20]), 0);
        assert_eq!(
            transport_checksum(*destination.ip(), *source.ip(), 6, &reply.as_slice()[20..]),
            0
        );
        cancel.cancel();
        tokio::time::timeout(Duration::from_secs(2), echo)
            .await
            .unwrap()
            .unwrap();
        tokio::time::timeout(Duration::from_secs(2), async {
            while !relay.tcp_flows.lock().unwrap().is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
    }

    #[test]
    fn malformed_ipv4_and_transport_headers_are_rejected() {
        // [FOCSQ] Некорректные длины/фрагменты не попадают ни в сокеты, ни в smoltcp.
        assert!(ParsedPacket::parse(&[]).is_none());
        let source = SocketAddrV4::new(Ipv4Addr::new(10, 66, 67, 4), 12345);
        let destination = SocketAddrV4::new(Ipv4Addr::LOCALHOST, 53);
        let good = udp_packet(source, destination, b"test");
        assert!(ParsedPacket::parse(&good).is_some());
        for length in 0..good.len() {
            assert!(ParsedPacket::parse(&good[..length]).is_none());
        }
        for (offset, value) in [(0, 0x44), (6, 0x20), (25, 40), (9, 1)] {
            let mut bad = good.clone();
            bad[offset] = value;
            assert!(ParsedPacket::parse(&bad).is_none());
        }
    }

    #[tokio::test]
    async fn udp_reply_keeps_logical_endpoints_and_cancel_releases_sessions() {
        // [FOCSQ] Локальный echo проверяет реальную UDP-передачу без внешней сети.
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let SocketAddr::V4(destination) = server.local_addr().unwrap() else {
            unreachable!()
        };
        let source = SocketAddrV4::new(Ipv4Addr::new(10, 66, 67, 4), 12345);
        let pool = PacketPool::new(16);
        let (inbound, mut replies) = mpsc::channel(16);
        let cancel = CancellationToken::new();
        let relay = DirectRelay::new(pool, inbound, cancel.clone()).unwrap();
        assert!(
            relay
                .forward(&udp_packet(source, destination, b"request"))
                .await
        );
        let mut bytes = [0; 64];
        let (length, peer) =
            tokio::time::timeout(Duration::from_secs(2), server.recv_from(&mut bytes))
                .await
                .unwrap()
                .unwrap();
        assert_eq!(&bytes[..length], b"request");
        server.send_to(b"response", peer).await.unwrap();
        let reply = tokio::time::timeout(Duration::from_secs(2), replies.recv())
            .await
            .unwrap()
            .unwrap();
        let parsed = ParsedPacket::parse(reply.as_slice()).unwrap();
        assert_eq!((parsed.source, parsed.destination), (destination, source));
        assert_eq!(parsed.payload, b"response");
        assert_eq!(checksum(&reply.as_slice()[..20]), 0);
        assert_eq!(
            transport_checksum(*destination.ip(), *source.ip(), 17, &reply.as_slice()[20..]),
            0
        );
        cancel.cancel();
        tokio::time::timeout(Duration::from_secs(2), async {
            while !relay.udp_flows.lock().unwrap().is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(
            !relay
                .forward(&udp_packet(source, destination, b"late"))
                .await
        );
    }

    #[tokio::test]
    async fn dns_upstream_override_preserves_original_reply_address() {
        // [FOCSQ] Замена DNS-сервера не должна менять адрес ожидаемого приложением ответа.
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let SocketAddr::V4(upstream) = server.local_addr().unwrap() else {
            unreachable!()
        };
        let logical = SocketAddrV4::new(Ipv4Addr::new(8, 8, 8, 8), 53);
        let source = SocketAddrV4::new(Ipv4Addr::new(10, 66, 67, 4), 12346);
        let (inbound, mut replies) = mpsc::channel(8);
        let cancel = CancellationToken::new();
        let relay = DirectRelay::new(PacketPool::new(8), inbound, cancel.clone()).unwrap();
        assert!(
            relay
                .forward_to(&udp_packet(source, logical, b"dns request"), Some(upstream))
                .await
        );
        let mut bytes = [0; 64];
        let (length, peer) =
            tokio::time::timeout(Duration::from_secs(2), server.recv_from(&mut bytes))
                .await
                .unwrap()
                .unwrap();
        assert_eq!(&bytes[..length], b"dns request");
        server.send_to(b"dns response", peer).await.unwrap();
        let reply = tokio::time::timeout(Duration::from_secs(2), replies.recv())
            .await
            .unwrap()
            .unwrap();
        let parsed = ParsedPacket::parse(reply.as_slice()).unwrap();
        assert_eq!((parsed.source, parsed.destination), (logical, source));
        assert_eq!(parsed.payload, b"dns response");
        cancel.cancel();
    }

    #[tokio::test]
    async fn large_udp_reply_is_fragmented_without_losing_payload() {
        // [FOCSQ] Ответ больше MTU должен целиком собраться после IPv4 reassembly.
        let pool = PacketPool::new(8);
        let (inbound, mut replies) = mpsc::channel(8);
        let source = SocketAddrV4::new(Ipv4Addr::LOCALHOST, 53);
        let destination = SocketAddrV4::new(Ipv4Addr::new(10, 66, 67, 4), 10000);
        let payload = vec![0x5a; 4001];
        assert!(
            return_udp(
                &pool,
                &inbound,
                &CancellationToken::new(),
                source,
                destination,
                &payload,
                42
            )
            .await
        );
        let mut segment = Vec::new();
        while let Ok(packet) = replies.try_recv() {
            let bytes = packet.as_slice();
            assert!(bytes.len() <= MTU);
            assert_eq!(checksum(&bytes[..20]), 0);
            assert_eq!(u16::from_be_bytes([bytes[4], bytes[5]]), 42);
            assert_eq!(
                usize::from(u16::from_be_bytes([bytes[6], bytes[7]]) & 0x1fff) * 8,
                segment.len()
            );
            segment.extend_from_slice(&bytes[20..]);
        }
        assert_eq!(&segment[8..], &payload);
        assert_eq!(
            transport_checksum(*source.ip(), *destination.ip(), 17, &segment),
            0
        );
    }
}
