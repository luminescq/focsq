package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRecoveryPolicyTest {
    @Test
    fun unexpected_service_destruction_keeps_the_tunnel_requested() {
        assertTrue(VpnRecoveryPolicy.keepsTunnelRequested(VpnFailureKind.ServiceDestroyed))
    }

    @Test
    fun tun_fd_delivery_failure_keeps_the_tunnel_requested() {
        assertTrue(VpnRecoveryPolicy.keepsTunnelRequested(VpnFailureKind.TunFdDeliveryFailed))
    }

    @Test
    fun permission_revocation_stops_the_tunnel() {
        assertFalse(VpnRecoveryPolicy.keepsTunnelRequested(VpnFailureKind.PermissionRevoked))
    }

    @Test
    fun invalid_tun_establishment_stops_the_tunnel() {
        assertFalse(VpnRecoveryPolicy.keepsTunnelRequested(VpnFailureKind.TunEstablishFailed))
    }

    @Test
    fun every_diagnostic_has_a_stable_nonempty_code_and_message() {
        VpnFailureKind.entries.forEach { kind ->
            assertTrue(kind.code.isNotBlank())
            assertTrue(VpnRecoveryPolicy.message(kind, "detail").isNotBlank())
        }
    }
}
