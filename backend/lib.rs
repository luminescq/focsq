// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

// [FOCSQ] Файл заменяет main.rs оригинала csqtt: ядро собрано как библиотека
// csqtt-core для встраивания в хост-приложение (flutter_rust_bridge) —
// CLI/stdin-цикл убран, управление через submit_control_line (mpsc),
// события включаются принудительно (EVENTS_FORCED), отмена — через CancellationToken.

mod auth;
mod captcha;
mod captcha_slider;
mod dispatcher;
mod dns;
mod events;
mod logging;
mod namegen;
mod obfs;
mod packet;
mod profiles;
mod repair;
mod protocol;
#[path = "shared/selective_fec.rs"]
mod selective_fec;
mod session;
mod stats;
#[path = "shared/striped_scheduler.rs"]
mod striped_scheduler;
mod stun_codec;
mod turn_endpoint;
mod turn_stream;
mod udp_batch;
#[cfg(target_os = "linux")]
// [FOCSQ] Нативный Linux TUN (/dev/net/tun + netlink-маршруты)
pub mod tun_linux;
mod turn;
mod turn_core;
 mod vk_js_calls;
#[cfg(windows)]
// [FOCSQ] pub: мост (rust_lib_frontend) вызывает set_dll_path_override
pub mod tun_win;
mod worker;
mod wrap;
// [FOCSQ] Общие модули апстрима v2.1.9 (shared/): кадрирование потоков
// и ревизия wire-протокола нужны новому диспетчеру/сессиям
#[path = "shared/flow_frame.rs"]
mod flow_frame;
#[path = "shared/wire_protocol.rs"]
mod wire_protocol;
mod client_perf;
mod cpu_task;

use anyhow::{Context, Result, bail};
use auth::{VkAuth, VkHashCheck};
use captcha::CaptchaSolver;
use dispatcher::Dispatcher;
use events::Events;
use obfs::ObfsMode;
use packet::{PacketPool, packet_pool_size};
use stats::Stats;
use std::{
    collections::HashSet,
    net::SocketAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio_util::sync::CancellationToken;
use repair::RepairState;
use session::ShutdownCoordinator;
use turn_endpoint::TurnTransportMode;
use worker::{
    GROUPS_PER_CREDENTIAL, GroupContext, PauseGate, RuntimeParams, WORKER_START_INTERVAL,
    WORKERS_PER_GROUP, WorkerStartPacer, parse_hashes, run_groups,
};

const GROUPS_PER_VK_HASH: usize = 3;
const MAX_VK_HASHES: usize = 6;
const MAX_WORKERS: usize = MAX_VK_HASHES * GROUPS_PER_VK_HASH * WORKERS_PER_GROUP;
use wrap::derive_wrap_key;

#[derive(Clone, Debug)]
pub struct ClientConfig {
    pub turn: String,
    pub port: String,
    pub listen: String,
    pub vk: String,
    pub vk_hash_mode: String,
    pub peer: String,
    pub workers: usize,
    pub allow_hash_redistribution: bool,
    pub device_id: String,
    pub password: String,
    pub vk_auth_mode: String,
    pub captcha_mode: String,
    pub fingerprint: String,
    pub client_ids: String,
    pub obfs: String,
    pub generation: u64,
    pub salt: String,
    pub tun_uds: String,
    pub validate_vk_hashes: bool,
    pub vk_js_token: String,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            turn: "".to_string(),
            port: "".to_string(),
            listen: "127.0.0.1:9000".to_string(),
            vk: "".to_string(),
            vk_hash_mode: "manual".to_string(),
            peer: "".to_string(),
            workers: 18,
            allow_hash_redistribution: false,
            device_id: "unknown".to_string(),
            password: "".to_string(),
            vk_auth_mode: "vkcalls".to_string(),
            captcha_mode: "auto".to_string(),
            fingerprint: "chrome".to_string(),
            client_ids: "".to_string(),
            obfs: "audio".to_string(),
            generation: 0,
            salt: "".to_string(),
            tun_uds: "".to_string(),
            validate_vk_hashes: false,
            vk_js_token: "".to_string(),
        }
    }
}

pub fn set_log_callback(cb: Box<dyn Fn(String) + Send + Sync>) {
    logging::set_log_callback(cb);
}

/// Принудительное включение структурированных событий (__CSQTT_EVENT__|...)
/// для embedded-режима, когда ядро живёт в процессе host-приложения
/// и переменную окружения CSQTT_EVENTS задать нельзя.
static EVENTS_FORCED: AtomicBool = AtomicBool::new(false);

pub fn set_events_enabled(enabled: bool) {
    EVENTS_FORCED.store(enabled, Ordering::Release);
}

/// Канал управления активной сессией (замена stdin для встроенного режима)
static CONTROL_TX: Mutex<Option<tokio::sync::mpsc::UnboundedSender<String>>> =
    Mutex::new(None);

fn set_control_channel(tx: tokio::sync::mpsc::UnboundedSender<String>) {
    if let Ok(mut guard) = CONTROL_TX.lock() {
        *guard = Some(tx);
    }
}

fn clear_control_channel() {
    if let Ok(mut guard) = CONTROL_TX.lock() {
        *guard = None;
    }
}

/// Отправить команду управления активной сессии ("PAUSE", "RESUME", "STOP",
/// "PATH_VALIDATE:", "CAPTCHA_RESULT|..."). Возвращает false, если клиент не запущен.
pub fn submit_control_line(line: String) -> bool {
    let guard = match CONTROL_TX.lock() {
        Ok(guard) => guard,
        Err(_) => return false,
    };
    match guard.as_ref() {
        Some(tx) => tx.send(line).is_ok(),
        None => false,
    }
}

/// Активный решатель капчи текущей сессии (получатель CAPTCHA_RESULT)
static ACTIVE_CAPTCHA: Mutex<Option<Arc<CaptchaSolver>>> = Mutex::new(None);

fn set_active_captcha(solver: Arc<CaptchaSolver>) {
    if let Ok(mut guard) = ACTIVE_CAPTCHA.lock() {
        *guard = Some(solver);
    }
}

fn clear_active_captcha() {
    if let Ok(mut guard) = ACTIVE_CAPTCHA.lock() {
        *guard = None;
    }
}

/// Передать результат решения капчи из host-приложения.
/// Возвращает false, если клиент не запущен или ответ устарел (канал занят).
pub fn submit_captcha_result(result: String) -> bool {
    let guard = match ACTIVE_CAPTCHA.lock() {
        Ok(guard) => guard,
        Err(_) => return false,
    };
    match guard.as_ref() {
        Some(solver) => solver.submit_result(result),
        None => false,
    }
}

pub async fn run_client(arguments: ClientConfig, external_cancel: Option<CancellationToken>) -> Result<()> {
    if arguments.validate_vk_hashes {
        return run_vk_hash_validation(&arguments).await;
    }
    let js_hash_mode = arguments.vk_hash_mode == "auto_js";
    let js_auth_mode = arguments.vk_auth_mode == "auto_js";
    if js_auth_mode && !js_hash_mode {
        bail!("[КЛИЕНТ] Режим авторизации Auto JS требует режим хешей Auto JS");
    }
    if arguments.peer.is_empty() || (!js_hash_mode && arguments.vk.is_empty()) {
        bail!("[КЛИЕНТ] Нужны -peer и хеши VK");
    }
    if arguments.password.is_empty() {
        bail!("[КЛИЕНТ] Нужен -password: WRAP ключ выводится из пароля подключения");
    }
    let peer = resolve_peer(&arguments.peer).await?;
    let mode = ObfsMode::parse(&arguments.obfs)?;
    let session_profile = profiles::random_profile(&arguments.fingerprint);
    let wrap_key = derive_wrap_key(&arguments.password)?;
    let mut js_calls = None;
    let mut js_credential_broker = None;
    let hash_source = if js_hash_mode {
        if arguments.vk_js_token.trim().is_empty() {
            bail!("[КЛИЕНТ] Режим Auto JS требует токен VK (vk_js_token из авторизации)");
        }
        let bootstrap = vk_js_calls::Bootstrap {
            token: arguments.vk_js_token.trim().to_owned(),
        };
        let started =
            vk_js_calls::start(bootstrap, &arguments.device_id, js_auth_mode, &session_profile)
                .await?;
        let hashes = started.hashes.join(",");
        js_calls = Some(started.active);
        js_credential_broker = Some(started.credential_broker);
        hashes
    } else {
        arguments.vk.clone()
    };
    let hashes: Vec<_> = parse_hashes(&hash_source)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK");
    }
    let workers = normalize_worker_count_for_hashes(
        arguments.workers,
        hashes.len(),
        arguments.allow_hash_redistribution || js_hash_mode,
    );
    let groups = workers / WORKERS_PER_GROUP;
    let cancel = external_cancel.unwrap_or_else(CancellationToken::new);
    // [FOCSQ] VK API — CDN с ротацией IP: резолвим хосты сразу (пока DNS
    // не перехвачен) и повторяем в течение сессии, добавляя свежие адреса
    // в исключения тюна. Иначе после 0/1+128/1 запросы vkcalls/auto_js
    // уходят в туннель на невыключенный CDN-IP и отваливаются — путь
    // звонков деградирует до капчи.
    #[cfg(any(windows, target_os = "linux"))]
    schedule_vk_endpoint_excludes(cancel.clone());
    let captcha = CaptchaSolver::new(&arguments.captcha_mode, cancel.clone());
    set_active_captcha(captcha.clone());
    let events = if EVENTS_FORCED.load(Ordering::Acquire) {
        Events::new(true)
    } else {
        Events::from_env()
    };
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    let auth = Arc::new(VkAuth::new(
        &arguments.vk_auth_mode,
        session_profile,
        &client_ids,
        captcha.clone(),
        js_credential_broker,
    ));
    let stats = Arc::new(Stats::default());
    let paused = Arc::new(PauseGate::new());
    let finish_js_calls = Arc::new(AtomicBool::new(false));
    let (control_tx, control_rx) = tokio::sync::mpsc::unbounded_channel();
    set_control_channel(control_tx);
    let control_task = start_control_input(
        cancel.clone(),
        paused.clone(),
        captcha.clone(),
        events.clone(),
        finish_js_calls.clone(),
        control_rx,
    );
    let parent_task = start_parent_monitor(cancel.clone());
    events.process(std::process::id());
    let pool = PacketPool::new(packet_pool_size(workers));
    let tun_uds = (!arguments.tun_uds.is_empty()).then_some(arguments.tun_uds.clone());
    // [FOCSQ] Нативный TUN: ядро само создаёт интерфейс и применяет TUNCONF.
    // Windows — «wintun», Linux — «tun» (имя интерфейса по умолчанию csqtt).
    #[cfg(windows)]
    let native_tun = arguments.tun_uds.eq_ignore_ascii_case("wintun");
    #[cfg(target_os = "linux")]
    let native_tun = !arguments.tun_uds.is_empty();
    #[cfg(not(any(windows, target_os = "linux")))]
    let native_tun = false;
    let tun_peer_ip = native_tun.then(|| peer.ip().to_string());
    let dispatcher_result = Dispatcher::start(
        &arguments.listen,
        tun_uds,
        pool.clone(),
        stats.clone(),
        cancel.clone(),
    )
    .await;
    let (dispatcher, local_port) = match dispatcher_result {
        Ok(value) => value,
        Err(error) => {
            if let Some(active) = js_calls.take() {
                active.finish().await;
            }
            return Err(error);
        }
    };
    let local_port: Arc<str> = Arc::from(local_port);
    // [FOCSQ] Десктоп ходит UDP-транспортом; TLS-транспорт (v2.1.9) пока
    // включается только на Android через аргумент CLI
    let turn_transport = TurnTransportMode::parse("udp")?;
    let params = Arc::new(RuntimeParams {
        peer,
        turn_host: (!arguments.turn.is_empty()).then(|| Arc::from(arguments.turn.as_str())),
        turn_port: (!arguments.port.is_empty()).then(|| Arc::from(arguments.port.as_str())),
        turn_transport,
        hashes: hashes.into(),
        wrap_key,
        mode,
        generation: arguments.generation,
        salt: Arc::from(arguments.salt.as_str()),
        local_port: local_port.clone(),
        device_id: Arc::from(arguments.device_id.as_str()),
        password: Arc::from(arguments.password.as_str()),
        workers,
    });
    print_configuration(
        &arguments,
        auth.client_ids(),
        workers,
        groups,
        params.hashes.len(),
        &local_port,
        params.turn_transport,
    );
    let repair = RepairState::new(workers);
    let stats_task = tokio::spawn(stats.clone().run(events.clone(), cancel.clone()));
    let (config_tx, mut config_rx) = tokio::sync::mpsc::channel::<String>(32);
    let config_events = events.clone();
    let config_task = tokio::spawn(async move {
        let mut last_config = None;
        while let Some(config) = config_rx.recv().await {
            if last_config.as_deref() == Some(config.as_str()) {
                continue;
            }
            if let Some(value) = config.strip_prefix("TUNCONF:") {
                dns::mark_tunnel_active();
                let mut fields = value.splitn(3, ':');
                let ip = fields.next().unwrap_or_default();
                let dns = fields.next().unwrap_or_default();
                crate::log_error!("[КЛИЕНТ] Tunnel IP: {ip}/32 | DNS: {dns}");
                #[cfg(windows)]
                if let (true, Some(peer_ip)) = (native_tun, tun_peer_ip.as_deref()) {
                    match crate::tun_win::apply_tunconf(ip, dns, peer_ip).await {
                        Ok(()) => {
                            crate::log_error!("[КЛИЕНТ] TUN-адаптер настроен (IP/DNS/маршруты)")
                        }
                        Err(error) => {
                            crate::log_error!("[ОШИБКА] Настройка TUN не удалась: {error:#}")
                        }
                    }
                }
                #[cfg(target_os = "linux")]
                if let (true, Some(peer_ip)) = (native_tun, tun_peer_ip.as_deref()) {
                    match crate::tun_linux::apply_tunconf(ip, dns, peer_ip).await {
                        Ok(()) => {
                            crate::log_error!("[КЛИЕНТ] TUN-адаптер настроен (IP/DNS/маршруты)")
                        }
                        Err(error) => {
                            crate::log_error!("[ОШИБКА] Настройка TUN не удалась: {error:#}")
                        }
                    }
                }
                #[cfg(not(any(windows, target_os = "linux")))]
                let _ = (&native_tun, &tun_peer_ip, ip, dns);
            }
            config_events.config(&config);
            last_config = Some(config);
        }
    });
    let (ready_credential_tx, ready_credential_rx) =
        if should_leave_js_creator(js_hash_mode, js_auth_mode) {
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            (Some(tx), Some(rx))
        } else {
            (None, None)
        };
    let context = Arc::new(GroupContext {
        params,
        auth,
        dispatcher: dispatcher.clone(),
        pool,
        stats,
        events: events.clone(),
        paused,
        config_tx,
        start_pacer: Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL)),
        credential_pacer: Arc::new(tokio::sync::Mutex::new(())),
        ready_credential_tx,
        config_sent: Arc::new(AtomicBool::new(false)),
        config_in_flight: Arc::new(AtomicBool::new(false)),
        server_stream_repair: Arc::new(AtomicBool::new(false)),
        repair,
        shutdown: Arc::new(ShutdownCoordinator::new()),
        cancel: cancel.clone(),
    });
    let required_ready_bots = required_js_ready_bots(groups);
    if js_auth_mode {
        crate::log_error!("[VK JS] Создатель удерживает звонок");
    }
    let creator_leave_task = match (js_calls.as_ref(), ready_credential_rx) {
        (Some(active), Some(receiver)) => Some(tokio::spawn(leave_js_creator_after_ready_workers(
            active.clone(),
            receiver,
            required_ready_bots,
            cancel.clone(),
        ))),
        _ => None,
    };
    let shutdown_events = events.clone();
    let groups_future = run_groups(groups, context);
    tokio::pin!(groups_future);
    let groups_completed = tokio::select! {
        _ = &mut groups_future => true,
        _ = tokio::signal::ctrl_c() => {
            crate::log_error!("[КЛИЕНТ] Получен сигнал завершения");
            cancel.cancel();
            false
        }
        _ = cancel.cancelled() => false,
    };
    cancel.cancel();
    // [FOCSQ] Роняем интерфейс немедленно: трафик умирает сразу, не дожидаясь
    // остановки воркеров и чистки маршрутов (та догонит в teardown).
    #[cfg(windows)]
    if native_tun {
        tun_win::drop_interface_now();
    }
    #[cfg(target_os = "linux")]
    if native_tun {
        tun_linux::drop_interface_now();
    }
    if !groups_completed {
        groups_future.await;
    }
    dispatcher.shutdown().await;
    clear_control_channel();
    clear_active_captcha();
    stats_task.abort();
    config_task.abort();
    control_task.abort();
    parent_task.abort();
    let _ = stats_task.await;
    let _ = config_task.await;
    let _ = control_task.await;
    let _ = parent_task.await;
    if let Some(mut task) = creator_leave_task
        && tokio::time::timeout(Duration::from_secs(9), &mut task)
            .await
            .is_err()
    {
        task.abort();
        let _ = task.await;
    }
    if let Some(active) = js_calls.take() {
        if finish_js_calls.load(Ordering::Acquire) {
            active.finish().await;
        } else {
            active.leave_creator().await;
        }
    }
    #[cfg(windows)]
    if native_tun {
        tun_win::teardown();
    }
    #[cfg(target_os = "linux")]
    if native_tun {
        tun_linux::teardown();
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    let _ = native_tun;
    shutdown_events.stopped();
    crate::log_error!("[КЛИЕНТ] Все воркеры завершены");
    // НЕ выключаем логгер — он должен жить между перезапусками в Flutter
    // let _ = logging::shutdown(Duration::from_secs(1));
    Ok(())
}

async fn leave_js_creator_after_ready_workers(
    active: vk_js_calls::ActiveCalls,
    receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    required_ready_bots: usize,
    cancel: CancellationToken,
) {
    let all_ready = wait_for_js_credential_readiness(receiver, required_ready_bots, cancel).await;
    if all_ready {
        crate::log_error!("[VK JS] TURN-боты готовы, создатель выходит из звонка");
    }
    let _ = tokio::time::timeout(Duration::from_secs(8), active.leave_creator()).await;
}

async fn wait_for_js_credential_readiness(
    mut receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    expected_credentials: usize,
    cancel: CancellationToken,
) -> bool {
    let mut ready = HashSet::with_capacity(expected_credentials);
    while ready.len() < expected_credentials {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break,
            credential = receiver.recv() => match credential {
                Some(credential) => {
                    ready.insert(credential);
                }
                None => break,
            },
        }
    }
    ready.len() == expected_credentials
}

fn required_js_ready_bots(groups: usize) -> usize {
    groups.div_ceil(GROUPS_PER_CREDENTIAL).clamp(1, 2)
}

fn normalize_worker_count(requested: usize) -> usize {
    requested.clamp(WORKERS_PER_GROUP, MAX_WORKERS) / WORKERS_PER_GROUP * WORKERS_PER_GROUP
}

fn normalize_worker_count_for_hashes(
    requested: usize,
    hash_count: usize,
    allow_hash_redistribution: bool,
) -> usize {
    if allow_hash_redistribution {
        return normalize_worker_count(requested);
    }
    let maximum = hash_count.clamp(1, MAX_VK_HASHES) * GROUPS_PER_VK_HASH * WORKERS_PER_GROUP;
    normalize_worker_count(requested).min(maximum)
}

fn should_leave_js_creator(_js_hash_mode: bool, _js_auth_mode: bool) -> bool {
    false
}

pub async fn run_vk_hash_validation(arguments: &ClientConfig) -> Result<()> {
    let hashes: Vec<_> = parse_hashes(&arguments.vk)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK для проверки");
    }
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    for (hash, result) in auth::check_vk_hashes(&arguments.fingerprint, &client_ids, &hashes).await
    {
        let payload = match result {
            VkHashCheck::Valid => serde_json::json!({
                "hash": hash,
                "status": "valid"
            }),
            VkHashCheck::Invalid { code, message } => serde_json::json!({
                "hash": hash,
                "status": "invalid",
                "code": code,
                "message": message
            }),
            VkHashCheck::Unavailable { message } => serde_json::json!({
                "hash": hash,
                "status": "unavailable",
                "message": message
            }),
        };
        println!("HASH_CHECK:{payload}");
    }
    Ok(())
}

/// [FOCSQ] Хосты VK API, которые ядро посещает само: авторизация
/// (vkcalls/auto_js/legacy), капча, проверка хешей. Резолвятся заранее,
/// пока системные DNS ещё не перехвачены, и переодически обновляются:
/// CDN VK ротирует IP, поэтому одноразового резолва мало. IP уходят
/// в отложенные exclude-маршруты (до TUNCONF) либо добавляются сразу.
#[cfg(any(windows, target_os = "linux"))]
fn schedule_vk_endpoint_excludes(cancel: tokio_util::sync::CancellationToken) {
    const HOSTS: &[&str] = &[
        "api.vk.me",
        "api.vk.com",
        "login.vk.ru",
        "login.vk.com",
        "id.vk.ru",
        "id.vk.com",
        "oauth.vk.ru",
        "oauth.vk.com",
        "vk.com",
    ];
    tokio::spawn(async move {
        let mut ticker = tokio::time::interval(Duration::from_secs(15));
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                _ = ticker.tick() => {
                    for host in HOSTS {
                        let Ok(addrs) =
                            tokio::net::lookup_host((host.to_string(), 443u16)).await
                        else {
                            crate::log_error!(
                                "[TUN] VK-хост {host} не резолвится — исключение пропущено"
                            );
                            continue;
                        };
                        for addr in addrs {
                            #[cfg(windows)]
                            crate::tun_win::exclude_host_ip(addr.ip());
                            #[cfg(target_os = "linux")]
                            crate::tun_linux::exclude_host_ip(addr.ip());
                        }
                    }
                }
            }
        }
    });
}

async fn resolve_peer(peer: &str) -> Result<SocketAddr> {
    let mut last_error = None;
    for _ in 0..15 {
        match dns::resolve_socket(peer).await {
            Ok(address) => return Ok(address),
            Err(error) => last_error = Some(error),
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("пустой DNS-ответ для пира")))
        .context("ошибка разбора пира")
}

fn start_control_input(
    cancel: CancellationToken,
    paused: Arc<PauseGate>,
    captcha: Arc<CaptchaSolver>,
    events: Events,
    finish_js_calls: Arc<AtomicBool>,
    mut commands: tokio::sync::mpsc::UnboundedReceiver<String>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let control_required = events.enabled();
        loop {
            let line = tokio::select! {
                _ = cancel.cancelled() => return,
                command = commands.recv() => match command {
                    Some(line) => line,
                    None => {
                        if control_required {
                            crate::log_error!("[КЛИЕНТ] Канал управления закрыт");
                            cancel.cancel();
                        }
                        return;
                    }
                },
            };
            let line = line.trim();
            if !line.contains("error:tunnel stopped") {
                crate::log_error!("[УПР] {line}");
            }
            match line {
                "PAUSE" => paused.set_paused(true),
                "RESUME" => paused.set_paused(false),
                "FINISH_VK_CALLS" => finish_js_calls.store(true, Ordering::Release),
                "STOP" => {
                    crate::log_error!("[КЛИЕНТ] Получена команда STOP");
                    cancel.cancel();
                    return;
                }
                _ => {
                    if let Some(result) = line.strip_prefix("CAPTCHA_RESULT|") {
                        if captcha.submit_result(result.to_owned()) {
                            crate::log_error!("[КАПЧА] Результат от Kotlin записан в канал");
                        } else {
                            crate::log_error!(
                                "[КАПЧА] Канал результата уже заполнен, устаревший ответ отклонён"
                            );
                        }
                    }
                }
            }
        }
    })
}

fn start_parent_monitor(cancel: CancellationToken) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        #[cfg(unix)]
        {
            let parent = unsafe { libc::getppid() };
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_secs(2)) => {}
                }
                if unsafe { libc::getppid() } != parent {
                    cancel.cancel();
                    return;
                }
            }
        }
        #[cfg(not(unix))]
        cancel.cancelled().await;
    })
}

fn print_configuration(
    arguments: &ClientConfig,
    client_ids: String,
    workers: usize,
    groups: usize,
    hashes: usize,
    local_port: &str,
    turn_transport: TurnTransportMode,
) {
    let captcha = match arguments.captcha_mode.as_str() {
        "wv" => "WBV selected in Android",
        "rjs" => "RJS Rust v2 with WBV Auto fallback",
        _ => "AUTO: Rust v2 x2 -> WBV Auto x2 -> Rust v2 x1 -> Manual WBV",
    };
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
    crate::log_error!("[КЛИЕНТ] VK Creds: Client IDs: {client_ids}");
    crate::log_error!("[КЛИЕНТ] VK Auth: {}", arguments.vk_auth_mode);
    crate::log_error!("[КЛИЕНТ] TLS: {} fingerprint", arguments.fingerprint);
    crate::log_error!("[КЛИЕНТ] Воркеров: {workers} (групп: {groups}, по {WORKERS_PER_GROUP})");
    crate::log_error!("[КЛИЕНТ] Хешей: {hashes}");
    crate::log_error!(
        "[КЛИЕНТ] Слушаю: {} (порт {local_port}) | Пир: {}",
        arguments.listen,
        arguments.peer
    );
    crate::log_error!(
        "[КЛИЕНТ] Протокол: TURN {} | WRAP: ON | obfs={}",
        turn_transport.as_str().to_ascii_uppercase(),
        arguments.obfs
    );
    crate::log_error!("[WRAP] WRAP Ключ вычислен ✓");
    crate::log_error!("[КЛИЕНТ] Device ID: {}", arguments.device_id);
    crate::log_error!("[КЛИЕНТ] Captcha: {captcha}");
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
}

#[cfg(test)]
mod worker_count_tests {
    use super::*;

    #[test]
    fn every_supported_total_maps_to_complete_nine_allocation_groups() {
        for groups in 1..=MAX_WORKERS / WORKERS_PER_GROUP {
            let workers = groups * WORKERS_PER_GROUP;
            assert_eq!(normalize_worker_count(workers), workers);
            assert_eq!(workers / WORKERS_PER_GROUP, groups);
        }
        assert_eq!(normalize_worker_count(MAX_WORKERS), 162);
    }

    #[test]
    fn invalid_totals_never_create_partial_or_excess_group() {
        for requested in 0..=1_000 {
            let workers = normalize_worker_count(requested);
            assert!((WORKERS_PER_GROUP..=MAX_WORKERS).contains(&workers));
            assert_eq!(workers % WORKERS_PER_GROUP, 0);
        }
    }

    #[test]
    fn hash_count_caps_native_worker_admission_to_twenty_seven_each() {
        assert_eq!(normalize_worker_count_for_hashes(usize::MAX, 1, false), 27);
        assert_eq!(normalize_worker_count_for_hashes(usize::MAX, 4, false), 108);
        assert_eq!(normalize_worker_count_for_hashes(usize::MAX, 5, false), 135);
        assert_eq!(normalize_worker_count_for_hashes(usize::MAX, 6, false), 162);
        assert_eq!(
            normalize_worker_count_for_hashes(usize::MAX, 100, false),
            162
        );
    }

    #[test]
    fn automatic_call_failure_may_redistribute_complete_groups() {
        assert_eq!(normalize_worker_count_for_hashes(162, 5, true), 162);
        assert_eq!(normalize_worker_count_for_hashes(54, 1, true), 54);
        assert_eq!(normalize_worker_count_for_hashes(50, 1, true), 45);
    }

    #[test]
    fn auto_js_account_auth_supports_nine_credentials_in_one_call() {
        assert_eq!(normalize_worker_count_for_hashes(162, 1, true), 162);
        assert_eq!(
            MAX_WORKERS.div_ceil(worker::WORKERS_PER_CREDENTIAL),
            vk_js_calls::MAX_ACCOUNT_CREDENTIALS
        );
    }

    #[test]
    fn auto_js_always_keeps_creator_while_running() {
        assert!(!should_leave_js_creator(true, false));
        assert!(!should_leave_js_creator(true, true));
        assert!(!should_leave_js_creator(false, false));
        assert!(!should_leave_js_creator(false, true));
    }

    #[test]
    fn auto_js_waits_for_at_most_two_independent_turn_bots() {
        assert_eq!(required_js_ready_bots(1), 1);
        assert_eq!(required_js_ready_bots(2), 1);
        assert_eq!(required_js_ready_bots(3), 2);
        assert_eq!(required_js_ready_bots(18), 2);
    }



    #[tokio::test]
    async fn js_creator_waits_for_every_distinct_ready_credential() {
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        sender.send(1).unwrap();
        sender.send(1).unwrap();
        sender.send(2).unwrap();
        assert!(wait_for_js_credential_readiness(receiver, 2, CancellationToken::new()).await);
    }

    #[tokio::test]
    async fn js_creator_wait_is_cancel_safe() {
        let (_sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(!wait_for_js_credential_readiness(receiver, 1, cancel).await);
    }
}
