// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crate::{
    client_perf::{self, Stage as PerfStage},
    events::Events,
};
use std::sync::{
    Arc,
    atomic::{AtomicI32, AtomicI64, Ordering},
};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

// [PORT 2.5.2] STATS читает приложение (число потоков, строка трафика, ретрай
// старта VPN через 10 с); повтор последнего значения ничего ему не сообщает.
// Снапшот уходит на тике после изменения, поэтому изменение доходит за 500 мс,
// иначе — раз в 3 с (как в 2.1.9: ретрай может прийти позже 2.5 с) вместо
// двух раз в секунду на всём протяжении простоя, каждый раз будя ридер и
// главный поток приложения.
const TICK: Duration = Duration::from_millis(500);
const HEARTBEAT_TICKS: u32 = 6;

type Snapshot = (i32, i64, i64);

fn should_emit(last: Option<Snapshot>, now: Snapshot, ticks_since_emit: u32) -> bool {
    last != Some(now) || ticks_since_emit >= HEARTBEAT_TICKS
}

#[derive(Default)]
pub struct Stats {
    pub total_bytes_up: AtomicI64,
    pub total_bytes_down: AtomicI64,
    pub active_connections: AtomicI32,
}

impl Stats {
    pub async fn run(self: Arc<Self>, events: Events, cancel: CancellationToken) {
        let mut interval = tokio::time::interval(TICK);
        interval.tick().await;
        let mut log_counter = 0u32;
        let mut last_emitted: Option<Snapshot> = None;
        let mut ticks_since_emit = 0u32;
        loop {
            tokio::select! {
                _ = cancel.cancelled() => return,
                _ = interval.tick() => {
                    let active = self.active_connections.load(Ordering::Relaxed);
                    let up = self.total_bytes_up.load(Ordering::Relaxed);
                    let down = self.total_bytes_down.load(Ordering::Relaxed);
                    log_counter += 1;
                    ticks_since_emit += 1;
                    let now = (active, up, down);
                    if should_emit(last_emitted, now, ticks_since_emit) {
                        client_perf::measure(PerfStage::StatsEmit, || events.stats(active, up, down));
                        last_emitted = Some(now);
                        ticks_since_emit = 0;
                        // строка лога (stderr) едет на тике STATS: на простое
                        // оба идут раз в 3 с, одно пробуждение ридера вместо двух
                        if log_counter >= HEARTBEAT_TICKS {
                            log_counter = 0;
                            let total_mb = (up + down) as f64 / (1024.0 * 1024.0);
                            crate::log_error!(
                                "[СТАТИСТИКА] Активных: {active} | Трафик: {total_mb:.2} МБ"
                            );
                        }
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stats_sent_on_change_and_every_3s_otherwise() {
        // The first tick always goes out.
        assert!(should_emit(None, (0, 0, 0), 1));
        // Unchanged: held until the 6th tick (3 s), then sent.
        for ticks in 1..HEARTBEAT_TICKS {
            assert!(!should_emit(Some((10, 5, 7)), (10, 5, 7), ticks));
        }
        assert!(should_emit(Some((10, 5, 7)), (10, 5, 7), HEARTBEAT_TICKS));
        // Any change goes out on the next tick: the worker count, either byte counter.
        assert!(should_emit(Some((10, 5, 7)), (9, 5, 7), 1));
        assert!(should_emit(Some((10, 5, 7)), (10, 6, 7), 1));
        assert!(should_emit(Some((10, 5, 7)), (10, 5, 8), 1));
        assert_eq!(TICK.as_millis() * HEARTBEAT_TICKS as u128, 3_000);
    }
}
