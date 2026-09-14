// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
use csqtt_core::ClientConfig;
use std::sync::Mutex;
use std::sync::OnceLock;
use tokio_util::sync::CancellationToken;

#[flutter_rust_bridge::frb(init)]
pub fn init_app() {
    // Default utilities - feel free to customize
    flutter_rust_bridge::setup_default_user_utils();
}

static CANCEL_TOKEN: OnceLock<Mutex<Option<CancellationToken>>> = OnceLock::new();

pub fn setup_log_stream(sink: crate::frb_generated::StreamSink<String>) {
    csqtt_core::set_log_callback(Box::new(move |log_line| {
        let _ = sink.add(log_line);
    }));
}

pub fn stop_csqtt_client() {
    if let Some(guard) = CANCEL_TOKEN.get() {
        if let Some(token) = guard.lock().unwrap().take() {
            token.cancel();
        }
    }
}

/// [FOCSQ] Путь к wintun.dll, распакованному Flutter-стороной
/// в %LOCALAPPDATA%\FOCSQ — паттерн WireGuard/Amnezia.
pub fn set_wintun_dll_path(path: String) {
    #[cfg(windows)]
    csqtt_core::tun_win::set_dll_path_override(path);
    #[cfg(not(windows))]
    let _ = path;
}

/// Команда управления ядром ("PAUSE", "RESUME", "PATH_VALIDATE:", "CAPTCHA_RESULT|...")
pub fn send_control_command(command: String) -> bool {
    csqtt_core::submit_control_line(command)
}

/// Пауза/возобновление обработки трафика воркерами
pub fn set_client_paused(paused: bool) -> bool {
    csqtt_core::submit_control_line(if paused { "PAUSE".to_owned() } else { "RESUME".to_owned() })
}

/// Передать результат решения WebView-капчи в ядро (success_token или "error:...")
pub fn submit_captcha_result(result: String) -> bool {
    csqtt_core::submit_captcha_result(result)
}

pub struct AppClientConfig {
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

impl Into<ClientConfig> for AppClientConfig {
    fn into(self) -> ClientConfig {
        ClientConfig {
            turn: self.turn,
            port: self.port,
            listen: self.listen,
            vk: self.vk,
            vk_hash_mode: self.vk_hash_mode,
            peer: self.peer,
            workers: self.workers,
            allow_hash_redistribution: self.allow_hash_redistribution,
            device_id: self.device_id,
            password: self.password,
            vk_auth_mode: self.vk_auth_mode,
            captcha_mode: self.captcha_mode,
            fingerprint: self.fingerprint,
            client_ids: self.client_ids,
            obfs: self.obfs,
            generation: self.generation,
            salt: self.salt,
            tun_uds: self.tun_uds,
            validate_vk_hashes: self.validate_vk_hashes,
            vk_js_token: self.vk_js_token,
        }
    }
}

pub async fn start_csqtt_client(config: AppClientConfig) -> anyhow::Result<()> {
    // Embedded-режим: включаем структурированные события (__CSQTT_EVENT__|...)
    csqtt_core::set_events_enabled(true);

    let cancel = CancellationToken::new();
    
    {
        let mut guard = CANCEL_TOKEN.get_or_init(|| Mutex::new(None)).lock().unwrap();
        *guard = Some(cancel.clone());
    }
    
    let result = csqtt_core::run_client(config.into(), Some(cancel)).await;
    
    {
        let mut guard = CANCEL_TOKEN.get_or_init(|| Mutex::new(None)).lock().unwrap();
        *guard = None;
    }
    
    result?;
    Ok(())
}
