package com.csqtt.client

internal object WorkerCountPolicy {
    fun normalize(
        requested: Int,
        maximum: Int = CsqttConstants.Tunnel.MAX_WORKERS,
    ): Int {
        if (requested <= 5) {
            return 5
        }
        val group = CsqttConstants.Tunnel.WORKERS_PER_GROUP
        val normalizedMaximum = (maximum.coerceAtLeast(group) / group) * group
        val clamped = requested.coerceIn(5, normalizedMaximum)
        val rounded = ((clamped + group - 1) / group) * group
        return rounded.coerceAtLeast(5)
    }

    fun maximumForHashes(hashCount: Int): Int {
        val count = hashCount.coerceIn(1, CsqttConstants.Tunnel.MAX_VK_HASHES)
        return (count * CsqttConstants.Tunnel.WORKERS_PER_VK_HASH)
            .coerceIn(5, CsqttConstants.Tunnel.MAX_WORKERS)
    }

    fun maximumForSources(
        linkMode: Boolean,
        linkHashCount: Int,
        autoHashMode: Boolean,
        manualHashCount: Int,
    ): Int = CsqttConstants.Tunnel.MAX_WORKERS

    /**
     * Power available in the profile editor: each manual hash carries up to
     * [CsqttConstants.Tunnel.GROUPS_PER_VK_HASH] worker groups, while automatic
     * hash modes create calls on demand and stay unrestricted.
     */
    fun maximumForProfile(hashMode: String, hashCount: Int): Int =
        if (hashMode == CsqttConstants.VkAutoHash.MODE_MANUAL) {
            maximumForHashes(hashCount)
        } else {
            CsqttConstants.Tunnel.MAX_WORKERS
        }

    fun defaultMaximum(extraWorkers: Boolean = false): Int =
        if (extraWorkers) CsqttConstants.Tunnel.MAX_WORKERS else CsqttConstants.Tunnel.DEFAULT_MAX_WORKERS

    fun normalizeForHashes(requested: Int, hashCount: Int): Int =
        normalize(requested, maximumForHashes(hashCount))

    fun normalizeForHashValues(
        requested: Int,
        hashes: Iterable<String>,
        allowRedistribution: Boolean = false,
    ): Int {
        if (allowRedistribution) {
            return normalize(requested, CsqttConstants.Tunnel.MAX_WORKERS)
        }
        val hashCount = hashes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(CsqttConstants.Tunnel.MAX_VK_HASHES)
            .count()
        return normalizeForHashes(requested, hashCount)
    }

}
