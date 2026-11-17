package com.csqtt.client

import java.net.URI

internal val VK_AUTH_COOKIE_ORIGINS = listOf(
    "https://vk.com/", "https://vk.ru/", "https://id.vk.com/", "https://id.vk.ru/",
    "https://login.vk.com/", "https://login.vk.ru/", "https://m.vk.com/", "https://m.vk.ru/",
)

internal fun vkCookieDeletionHeaders(origin: String, cookies: String?): List<String> {
    val host = runCatching { URI(origin).host }.getOrNull() ?: return emptyList()
    val root = listOf("vk.com", "vk.ru").firstOrNull { host == it || host.endsWith(".$it") }
        ?: return emptyList()
    val domains = generateSequence(host) { it.substringAfter('.', "").takeIf { part -> part.isNotEmpty() } }
        .takeWhile { it == root || it.endsWith(".$root") }.toList()
    return cookies.orEmpty().split(';').map { it.substringBefore('=').trim() }
        .filter { name -> name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "_-.!#$%&'*+^`|~" } }
        .distinct().flatMap { name ->
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure"
            listOf(expired) + domains.map { "$expired; Domain=$it" }
        }
}
