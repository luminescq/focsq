package com.csqtt.client

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SubscriptionRefreshOutcome(
    val success: Boolean,
    val message: String,
    val title: String = "",
    val nodes: Int = 0,
)

/** Manual and scheduled subscription refreshes share one lock per URL. */
internal object SubscriptionSync {
    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(url: String): Mutex = synchronized(locks) {
        locks.getOrPut(url) { Mutex() }
    }

    suspend fun refresh(store: SettingsStore, rawUrl: String): SubscriptionRefreshOutcome {
        val url = rawUrl.trim()
        if (url.isEmpty()) return SubscriptionRefreshOutcome(false, "Укажите ссылку подписки")
        return lockFor(url).withLock {
            performRefresh(store, url)
        }
    }

    suspend fun refreshDue(store: SettingsStore, now: Long = System.currentTimeMillis()) {
        if (TunnelManager.running.value ||
            TunnelManager.starting.value ||
            TunnelManager.stopping.value ||
            TunnelManager.autoPausedForWifi.value
        ) {
            return
        }
        val due = store.subscriptionMeta.first().values
            .filter { it.needsUpdate(now) }
            .sortedBy { maxOf(it.lastUpdatedAt, it.lastAttemptAt) }
        for (meta in due) {
            lockFor(meta.url).withLock {
                val current = store.subscriptionMeta.first()[meta.url] ?: return@withLock
                if (!current.needsUpdate(System.currentTimeMillis())) return@withLock
                performRefresh(store, meta.url)
            }
        }
    }

    private suspend fun performRefresh(
        store: SettingsStore,
        url: String,
    ): SubscriptionRefreshOutcome {
        val meta = store.subscriptionMeta.first()[url]
        val allowHttp = meta?.allowHttp == true || url.startsWith("http://", ignoreCase = true)
        val normalized = normalizeSubscriptionUrl(url, allowHttp)
        store.markSubscriptionAttempt(normalized)
        return when (val result = SubscriptionImporter.fetch(normalized, allowHttp)) {
            is SubscriptionFetchResult.Success -> {
                val summary = SubscriptionImporter.importNodes(
                    store = store,
                    rawUrl = normalized,
                    preview = result.preview,
                    allowHttp = allowHttp,
                )
                store.markSubscriptionUpdated(normalized, title = result.preview.title)
                SubscriptionRefreshOutcome(
                    success = true,
                    message = summary.message(),
                    title = meta?.title?.takeIf { it.isNotBlank() } ?: result.preview.title,
                    nodes = result.preview.nodes.size,
                )
            }
            is SubscriptionFetchResult.Failure ->
                SubscriptionRefreshOutcome(false, result.message)
        }
    }
}
