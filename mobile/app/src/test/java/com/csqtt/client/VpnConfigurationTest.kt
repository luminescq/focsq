package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnConfigurationTest {
    @Test
    fun defaultMtuIs1500() {
        assertEquals(1500, CsqttConstants.Vpn.DEFAULT_MTU)
    }
}
