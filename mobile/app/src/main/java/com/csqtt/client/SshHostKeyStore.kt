package com.csqtt.client

import android.content.Context
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.Collections

object SshHostKeyStore {
    private const val PREFS_NAME = "ssh_known_hosts"
    private const val FINGERPRINT_ALGORITHM = "SHA-256"

    private fun entryKey(host: String, port: Int): String =
        "${host.trim().lowercase()}:$port"

    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM).digest(blob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$encoded"
    }

    fun clear(context: Context, host: String, port: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(entryKey(host, port))
            .apply()
    }

    class TofuVerifier(context: Context) : HostKeyVerifier {
        private val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = fingerprint(key)
            val entry = entryKey(hostname, port)
            val pinned = prefs.getString(entry, null)
            if (pinned == null) {
                prefs.edit().putString(entry, presented).apply()
                DeployManager.writeError("SSH host key pinned for $entry: $presented")
                return true
            }
            if (pinned == presented) {
                return true
            }
            DeployManager.writeError(
                "SSH host key mismatch for $entry: expected $pinned, presented $presented"
            )
            return false
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            Collections.emptyList()
    }
}
