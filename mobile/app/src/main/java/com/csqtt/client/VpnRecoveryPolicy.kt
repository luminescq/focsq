package com.csqtt.client

internal enum class VpnFailureKind(
    val code: String,
    val recoverable: Boolean,
) {
    ServiceDestroyed("vpn_service_destroyed", true),
    TunFdDeliveryFailed("tun_fd_delivery_failed", true),
    TunEstablishFailed("tun_establish_failed", false),
    PermissionRevoked("vpn_permission_revoked", false),
}

internal object VpnRecoveryPolicy {
    fun keepsTunnelRequested(kind: VpnFailureKind): Boolean = kind.recoverable

    fun message(kind: VpnFailureKind, detail: String): String = when (kind) {
        VpnFailureKind.ServiceDestroyed -> "VPN-служба Android была пересоздана: $detail"
        VpnFailureKind.TunFdDeliveryFailed -> "Rust-клиент не принял TUN-интерфейс: $detail"
        VpnFailureKind.TunEstablishFailed -> "Не удалось создать VPN-интерфейс: $detail"
        VpnFailureKind.PermissionRevoked -> "Разрешение Android VPN отозвано: $detail"
    }
}
