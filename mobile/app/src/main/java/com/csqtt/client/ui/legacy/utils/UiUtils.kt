package com.csqtt.client.ui.legacy.utils

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CsqttLink(
    val host: String,
    val port: Int,
    val password: String = "",
    val hashes: List<String> = emptyList(),
    val vkAccessToken: String = "",
    val power: Int? = null,
)

/** Power/thread limit from an untrusted link or subscription. */
fun normalizeCsqttPower(value: Int?): Int? =
    value?.takeIf { it > 0 }?.coerceIn(10, 100)

private const val CSQTT_CIPHER_KEY_SEED = "csqtt-chiper-key"

private val cipherKey: SecretKeySpec by lazy {
    val digest = MessageDigest.getInstance("SHA-256")
    val keyBytes = digest.digest(CSQTT_CIPHER_KEY_SEED.toByteArray(Charsets.UTF_8))
    SecretKeySpec(keyBytes, "AES")
}

fun decodeBase64Safe(input: String): ByteArray? {
    val clean = input.replace("\n", "").replace("\r", "").replace(" ", "").trim()
    return runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { java.util.Base64.getUrlDecoder().decode(clean) }
                .getOrElse { java.util.Base64.getDecoder().decode(clean) }
        } else {
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE)
        }
    }.recoverCatching {
        runCatching { java.util.Base64.getUrlDecoder().decode(clean) }
            .getOrElse { java.util.Base64.getDecoder().decode(clean) }
    }.getOrNull()
}

fun decryptCsqttPayload(rawPayload: String): String? {
    val bytes = decodeBase64Safe(rawPayload) ?: return null
    if (bytes.size >= 28) {
        val decrypted = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, bytes, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, cipherKey, spec)
            val plaintext = cipher.doFinal(bytes, 12, bytes.size - 12)
            String(plaintext, Charsets.UTF_8)
        }.getOrNull()
        if (decrypted != null) {
            return decrypted
        }
    }
    return null
}

fun encryptCsqttPayload(plaintext: String): String {
    val iv = runCatching {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(cipherKey)
        val hmacBytes = mac.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        ByteArray(12).apply { System.arraycopy(hmacBytes, 0, this, 0, 12) }
    }.getOrElse {
        ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.ENCRYPT_MODE, cipherKey, spec)
    val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val combined = ByteArray(12 + ciphertextAndTag.size)
    System.arraycopy(iv, 0, combined, 0, 12)
    System.arraycopy(ciphertextAndTag, 0, combined, 12, ciphertextAndTag.size)
    return runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
        } else {
            android.util.Base64.encodeToString(
                combined,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            ).trim()
        }
    }.recoverCatching {
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }.getOrElse { "" }
}

fun parseCsqttLink(raw: String): CsqttLink? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("csqtt://", ignoreCase = true)) return null
    val payload = trimmed.substring(8).trim()
    if (payload.isEmpty()) return null
    // Open formats never need decryption: v2 connect query and legacy password@host:port.
    if (payload.startsWith("connect?", ignoreCase = true)) return parseOpenCsqttLink(payload)
    if (payload.contains('@')) return parseLegacyCsqttLink(payload)
    val decoded = decryptCsqttPayload(payload) ?: return null
    return parseDecodedCsqttLink(decoded)
}

private fun parseDecodedCsqttLink(decodedRaw: String): CsqttLink? {
    val decoded = decodedRaw.trim()
    if (decoded.isEmpty()) return null
    val body = if (decoded.startsWith("csqtt://", ignoreCase = true)) {
        decoded.substring(8).trim()
    } else {
        decoded
    }
    if (body.isEmpty()) return null
    return when {
        body.startsWith("connect?", ignoreCase = true) -> parseOpenCsqttLink(body)
        body.contains('@') -> parseLegacyCsqttLink(body)
        body.contains("host=") -> parseOpenCsqttLink("connect?$body")
        else -> null
    }
}

private fun parseOpenCsqttLink(body: String): CsqttLink? {
    if (!body.startsWith("connect?", ignoreCase = true)) return null
    val uri = runCatching { java.net.URI("csqtt://$body") }.getOrNull() ?: return null
    return parseCsqttV2(uri)
}

private fun parseLegacyCsqttLink(body: String): CsqttLink? {
    val raw = body.trim().substringAfter("csqtt://", body.trim())
    if (raw.isEmpty()) return null
    val at = raw.lastIndexOf('@')
    if (at <= 0 || at >= raw.length - 1) return null
    val password = decodeQueryComponent(raw.substring(0, at)) ?: return null
    if (password.isEmpty() || password.any(Char::isWhitespace)) return null
    val address = raw.substring(at + 1)
    val host: String
    val port: Int
    if (address.startsWith('[')) {
        val close = address.indexOf(']')
        if (close <= 1) return null
        host = address.substring(1, close)
        if (close + 1 >= address.length || address[close + 1] != ':') return null
        port = address.substring(close + 2).toIntOrNull() ?: return null
    } else {
        val colon = address.lastIndexOf(':')
        if (colon <= 0) return null
        host = address.substring(0, colon)
        port = address.substring(colon + 1).toIntOrNull() ?: return null
    }
    if (!isUsableLinkHost(host) || port !in 1..65535) return null
    return CsqttLink(host = host, port = port, password = password)
}

private fun isUsableLinkHost(host: String): Boolean {
    if (host.isBlank() || host.any(Char::isWhitespace)) return false
    return host != "0.0.0.0" &&
        host != "127.0.0.1" &&
        host != "::1" &&
        !host.equals("localhost", ignoreCase = true)
}

fun CsqttLink.peerAddress(): String {
    val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return "$formattedHost:$port"
}

private fun parseCsqttV2(uri: java.net.URI): CsqttLink? {
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.orEmpty().isNotEmpty()) return null
    val parameters = parseRawQuery(uri.rawQuery ?: return null) ?: return null
    if (parameters["v"]?.let(::decodeQueryComponent) != "2") return null
    val host = parameters["host"]?.let(::decodeQueryComponent)?.takeIf { it.isNotBlank() } ?: return null
    if (!isUsableLinkHost(host)) return null
    val port = parameters["peer"]?.let(::decodeQueryComponent)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val password = parameters["password"]?.let(::decodeQueryComponent)?.trim().orEmpty()
    if (password.isNotEmpty() && password.any(Char::isWhitespace)) return null
    val hashes = parameters["hashes"]?.let(::parseLinkHashes) ?: emptyList()
    if (parameters.containsKey("hashes") && hashes.isEmpty()) return null
    val vkAccessToken = parameters["token"]?.let(::parseLinkVkAccessToken).orEmpty()
    if (parameters.containsKey("token") && vkAccessToken.isEmpty()) return null
    if (hashes.isNotEmpty() && vkAccessToken.isNotEmpty()) return null
    val power = normalizeCsqttPower(
        (parameters["power"] ?: parameters["workers"])
            ?.let(::decodeQueryComponent)
            ?.toIntOrNull(),
    )
    return CsqttLink(host, port, password, hashes, vkAccessToken, power)
}

private fun parseRawQuery(rawQuery: String): Map<String, String>? {
    if (rawQuery.isEmpty()) return null
    val sanitized = rawQuery.replace("&amp;", "&")
    val result = linkedMapOf<String, String>()
    if (sanitized.contains('&') || sanitized.contains(';')) {
        for (part in sanitized.split(Regex("[&;]"))) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = decodeQueryComponent(part.substring(0, separator)) ?: continue
            val value = part.substring(separator + 1)
            if (key.isNotBlank()) {
                if (result.containsKey(key)) return null
                result[key] = value
            }
        }
        if (result.isNotEmpty() && result.containsKey("v") && result.containsKey("host") && result.containsKey("peer")) {
            return result
        }
    }

    val pattern = Regex("(?:^|[&;]|(?<=[a-zA-Z0-9_]))(v|host|peer|password|client|hashes|token|workers|power)=([^&?]*?)(?=(?:v|host|peer|password|client|hashes|token|workers|power)=|$)", RegexOption.IGNORE_CASE)
    val matches = pattern.findAll(sanitized).toList()
    if (matches.isNotEmpty()) {
        val extracted = linkedMapOf<String, String>()
        for (match in matches) {
            val key = decodeQueryComponent(match.groupValues[1].lowercase()) ?: continue
            val value = match.groupValues[2]
            if (extracted.containsKey(key)) return null
            extracted[key] = value
        }
        if (extracted.isNotEmpty() && extracted.containsKey("v") && extracted.containsKey("host") && extracted.containsKey("peer")) {
            return extracted
        }
    }

    return if (result.isNotEmpty()) result else null
}

private fun parseLinkHashes(rawHashes: String): List<String> {
    val parts = rawHashes.split('+')
    if (parts.size !in 1..6 || parts.any { it.isEmpty() }) return emptyList()
    val hashes = parts.map { encoded ->
        decodeQueryComponent(encoded)?.let(::stripVkUrlStatic).orEmpty()
    }
    if (hashes.any { it.length < 16 || it.any(Char::isWhitespace) }) return emptyList()
    return hashes.distinct().takeIf { it.size == hashes.size }.orEmpty()
}

private fun parseLinkVkAccessToken(rawToken: String): String? {
    val token = decodeQueryComponent(rawToken)?.trim().orEmpty()
    return token.takeIf { it.isNotEmpty() && it.length <= 4096 && it.none(Char::isWhitespace) }
}

private fun decodeQueryComponent(value: String): String? {
    return runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrNull()
}

fun stripVkUrlStatic(input: String): String {
    var s = input.trim()
    val lower = s.lowercase()
    val prefixes = listOf(
        "https://vk.com/call/join/",
        "http://vk.com/call/join/",
        "https://m.vk.com/call/join/",
        "http://m.vk.com/call/join/",
        "m.vk.com/call/join/",
        "vk.com/call/join/",
        "https://vk.ru/call/join/",
        "http://vk.ru/call/join/",
        "https://m.vk.ru/call/join/",
        "http://m.vk.ru/call/join/",
        "m.vk.ru/call/join/",
        "vk.ru/call/join/"
    )
    for (prefix in prefixes) {
        if (lower.startsWith(prefix)) {
            s = s.substring(prefix.length)
            break
        }
    }
    val qIdx = s.indexOf('?')
    if (qIdx != -1) s = s.substring(0, qIdx)
    val hIdx = s.indexOf('#')
    if (hIdx != -1) s = s.substring(0, hIdx)
    return s.trimEnd('/')
}

// The server takes the password out of "GETCONF:port|password|…" (split on '|', each field trimmed) and accepts 1..128
// bytes; the rest of the way it is only bytes (HKDF, the TURN digest), so any other character is fine, Cyrillic too.
const val TUNNEL_PASSWORD_MAX_BYTES = 128

fun isValidTunnelPassword(password: String): Boolean =
    password.isNotEmpty() &&
        password.toByteArray(Charsets.UTF_8).size <= TUNNEL_PASSWORD_MAX_BYTES &&
        password.none { it == '|' || it.isWhitespace() || it.isISOControl() }

const val TUNNEL_PASSWORD_RULE = "Без пробелов и «|», не длиннее 128 байт (64 буквы кириллицей)"

fun generate16CharPassword(): String {
    val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val random = java.security.SecureRandom()
    val sb = StringBuilder(16)
    for (i in 0 until 16) {
        sb.append(chars[random.nextInt(chars.length)])
    }
    return sb.toString()
}

private fun encodeLinkComponent(value: String): String =
    runCatching {
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }.getOrDefault(value)

fun formatCsqttLink(
    host: String,
    peer: Int,
    password: String,
    hashes: List<String> = emptyList(),
    token: String = "",
    power: Int? = null,
): String {
    val queryParts = mutableListOf(
        "v=2",
        "host=${encodeLinkComponent(host)}",
        "peer=$peer",
        "password=${encodeLinkComponent(password)}",
    )
    if (hashes.isNotEmpty()) {
        queryParts.add("hashes=" + hashes.joinToString("+") { encodeLinkComponent(it) })
    } else if (token.isNotEmpty()) {
        queryParts.add("token=${encodeLinkComponent(token)}")
    }
    normalizeCsqttPower(power)?.let { queryParts.add("power=$it") }
    val plain = "connect?" + queryParts.joinToString("&")
    return "csqtt://" + encryptCsqttPayload(plain)
}
