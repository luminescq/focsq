package com.csqtt.client

import java.net.URI

object CaptchaUriPolicy {
    private val allowedDomains = setOf("vk.com", "vk.ru", "ok.ru", "okcdn.ru")

    fun isAllowed(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return allowedDomains.any { host == it || host.endsWith(".$it") }
    }
}
