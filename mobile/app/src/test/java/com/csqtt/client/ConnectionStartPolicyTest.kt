package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStartPolicyTest {
    @Test
    fun tokenFromParticipantLinkIsReadyWithoutStoredOwnerToken() {
        assertEquals(
            true,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = true,
                autoHashMode = true,
                storedTokenPresent = false,
                manualHashCount = 0,
            ),
        )
    }

    @Test
    fun participantLinkUsesExplicitlyEnteredHashesWhenLinkHasNoVkCredentials() {
        assertEquals(
            true,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = false,
                autoHashMode = false,
                storedTokenPresent = false,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun participantLinkWithoutCredentialsIsBlockedUntilUserEntersThem() {
        assertEquals(
            false,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = false,
                autoHashMode = false,
                storedTokenPresent = false,
                manualHashCount = 0,
            ),
        )
    }

    @Test
    fun `participant may connect through a valid link with manually saved hashes`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `missing manual participant hashes is reported before attempting native startup`() {
        assertEquals(
            ConnectionStartBlocker.Hashes,
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                hashesReady = false,
            ),
        )
    }

    @Test
    fun `owner validation identifies the first missing setting`() {
        assertEquals(
            ConnectionStartBlocker.Server,
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = false,
                peerPortValid = true,
                connectionPasswordSet = true,
                hashesReady = true,
            ),
        )
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = true,
                peerPortValid = true,
                connectionPasswordSet = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `unreadable stored secret does not block passwordless connection`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = true,
                peerPortValid = true,
                connectionPasswordSet = false,
                connectionPasswordReadable = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `link mode does not depend on the local encrypted password`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                connectionPasswordReadable = false,
                hashesReady = true,
            ),
        )
    }
}
