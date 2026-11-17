package com.csqtt.client.ui.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployUiStateTest {
    private val completeState = DeployUiState(
        host = "server.example",
        sshLogin = "root",
        sshPassword = "ssh-password",
        mainPasswordConfigured = true,
        webPanelConfigured = true,
    )

    @Test
    fun `install requires web authorization`() {
        assertTrue(completeState.canInstall)
        assertFalse(completeState.copy(webPanelConfigured = false).canInstall)
    }

    @Test
    fun `install requires main password authorization`() {
        assertTrue(completeState.canInstall)
        assertFalse(completeState.copy(mainPasswordConfigured = false).canInstall)
        assertTrue(isDeployMainPasswordValid("0123456789abcdef"))
        assertTrue(isDeployMainPasswordValid("0123456789abcdefgh"))
        assertTrue(isDeployMainPasswordValid("Пароль-туннеля#&%+=/:!?"))
        assertFalse(isDeployMainPasswordValid(""))
        assertFalse(isDeployMainPasswordValid("pass|word"))
        assertFalse(isDeployMainPasswordValid("pass word"))
    }

    @Test
    fun `web login and password are both required`() {
        assertTrue(hasWebPanelCredentials("admin", "secret"))
        assertFalse(hasWebPanelCredentials("", "secret"))
        assertFalse(hasWebPanelCredentials("admin", ""))
        assertFalse(hasWebPanelCredentials("   ", "secret"))
        assertFalse(hasWebPanelCredentials("admin", "   "))
    }

    @Test
    fun `uninstall does not require panel authorization`() {
        assertTrue(
            completeState.copy(
                mainPasswordConfigured = false,
                webPanelConfigured = false,
            ).canUninstall
        )
    }

    @Test
    fun `docker selection preserves deploy validation`() {
        assertTrue(completeState.copy(dockerInstall = true).canInstall)
        assertFalse(completeState.copy(dockerInstall = true, host = "").canInstall)
    }

    @Test
    fun `disabling manual ports restores every deployment port default`() {
        val ports = resolveServerPorts(
            manualPorts = false,
            sshPort = "228",
            peerPort = "47000",
            webPort = "47002",
        )

        assertTrue(ports.ssh == 22)
        assertTrue(ports.peer == 46000)
        assertTrue(ports.web == 46002)
    }

    @Test
    fun `the panel link is the profile's server`() {
        // a deploy stores the server's ports on success; the link follows them
        assertTrue(completeState.panelDisplayUrl.startsWith("https://server.example:46002/"))
        assertTrue(completeState.copy(manualPorts = true, webPort = "46102").panelDisplayUrl.startsWith("https://server.example:46102/"))
    }

    @Test
    fun `manual ports preserve valid custom values`() {
        val ports = resolveServerPorts(
            manualPorts = true,
            sshPort = "228",
            peerPort = "47000",
            webPort = "47002",
        )

        assertTrue(ports.ssh == 228)
        assertTrue(ports.peer == 47000)
        assertTrue(ports.web == 47002)
    }

    @Test
    fun `successful deploy synchronizes a nonempty server password`() {
        assertTrue(passwordToSynchronizeAfterDeploy(true, "server-password") == "server-password")
        assertTrue(passwordToSynchronizeAfterDeploy(false, "server-password") == null)
        assertTrue(passwordToSynchronizeAfterDeploy(true, "") == null)
        assertTrue(passwordToSynchronizeAfterDeploy(true, "   ") == null)
    }
}
