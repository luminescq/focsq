package com.csqtt.client

internal fun nextTunnelGenerationId(nowSeconds: Long, current: Long): Long = maxOf(
    nowSeconds,
    if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1,
)

internal fun reserveTunnelGenerationId(
    nowSeconds: Long,
    proposed: Long,
    persisted: Long,
): Long = nextTunnelGenerationId(maxOf(nowSeconds, proposed), persisted)

internal data class TunnelLifecycleTicket(
    val epoch: Long,
    val sequence: Long,
)

internal class TunnelLifecycleState {
    private var epoch = 0L
    private var sequence = 0L
    private var desiredRunning = false
    private var active: TunnelLifecycleTicket? = null

    @Synchronized
    fun requestStart(): Long {
        epoch = next(epoch)
        desiredRunning = true
        active = null
        return epoch
    }

    @Synchronized
    fun requestRestart(): Long? {
        if (!desiredRunning) return null
        epoch = next(epoch)
        active = null
        return epoch
    }

    @Synchronized
    fun requestStop(): Long {
        epoch = next(epoch)
        desiredRunning = false
        active = null
        return epoch
    }

    @Synchronized
    fun reserveProcess(expectedEpoch: Long, processIsNull: Boolean): TunnelLifecycleTicket? {
        if (!processIsNull || !canStart(expectedEpoch) || active != null) return null
        sequence = next(sequence)
        return TunnelLifecycleTicket(expectedEpoch, sequence).also { active = it }
    }

    @Synchronized
    fun releaseReservation(ticket: TunnelLifecycleTicket) {
        if (active == ticket) active = null
    }

    @Synchronized
    fun processEnded(ticket: TunnelLifecycleTicket): Boolean {
        if (active != ticket) return false
        active = null
        return desiredRunning && epoch == ticket.epoch
    }

    @Synchronized
    fun accepts(ticket: TunnelLifecycleTicket): Boolean =
        desiredRunning && epoch == ticket.epoch && active == ticket

    @Synchronized
    fun canStart(expectedEpoch: Long): Boolean =
        desiredRunning && epoch == expectedEpoch

    @Synchronized
    fun isDesiredRunning(): Boolean = desiredRunning

    private fun next(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L
}
