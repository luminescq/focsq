// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use std::sync::{
    LazyLock,
    atomic::{AtomicU64, Ordering},
};
use tokio::sync::Notify;

static GENERATION: AtomicU64 = AtomicU64::new(0);
static CHANGED: LazyLock<Notify> = LazyLock::new(Notify::new);

pub fn request() -> u64 {
    let generation = GENERATION
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
            Some(current.wrapping_add(1).max(1))
        })
        .unwrap_or_default()
        .wrapping_add(1)
        .max(1);
    CHANGED.notify_waiters();
    generation
}

pub fn generation() -> u64 {
    GENERATION.load(Ordering::Acquire)
}

pub async fn changed(observed: u64) -> u64 {
    loop {
        let notified = CHANGED.notified();
        let current = generation();
        if current != observed {
            return current;
        }
        notified.await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[tokio::test]
    async fn request_before_wait_is_never_lost() {
        let observed = generation();
        let expected = request();
        let received = tokio::time::timeout(Duration::from_millis(100), changed(observed))
            .await
            .expect("validation generation must be observed");
        assert!(received >= expected);
    }

    #[tokio::test]
    async fn concurrent_requests_converge_on_newest_generation() {
        let observed = generation();
        let requests = (0..64)
            .map(|_| tokio::spawn(async { request() }))
            .collect::<Vec<_>>();
        for request in requests {
            request.await.unwrap();
        }
        let newest = generation();
        let received = tokio::time::timeout(Duration::from_millis(100), changed(observed))
            .await
            .expect("coalesced validation generation must be observed");
        assert!(received >= newest);
    }

    #[tokio::test]
    async fn waiter_and_request_race_cannot_deadlock() {
        for _ in 0..1_000 {
            let observed = generation();
            let waiter = tokio::spawn(async move { changed(observed).await });
            let requested = request();
            let received = tokio::time::timeout(Duration::from_secs(1), waiter)
                .await
                .expect("waiter must not deadlock")
                .unwrap();
            assert!(received >= requested);
        }
    }
}
