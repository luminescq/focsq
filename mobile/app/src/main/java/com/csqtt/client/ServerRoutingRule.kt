package com.csqtt.client

import org.json.JSONObject

data class ServerRoutingRule(
    val isWhite: Boolean,
    val packages: List<String>,
    val domains: List<String>,
    val templateDomains: Boolean,
    val ipIsWhite: Boolean = false,
    val ips: List<String> = emptyList(),
    val domainsIsWhite: Boolean = isWhite,
)

internal fun isRoutingPackageName(value: String): Boolean =
    value.length in 1..255 &&
        value.split('.').all { part ->
            part.isNotEmpty() && (part[0] in 'A'..'Z' || part[0] in 'a'..'z' || part[0] == '_') &&
                part.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }

internal fun parseServerRoutingRule(value: String): ServerRoutingRule? = runCatching {
    val json = JSONObject(value)
    fun strings(key: String): List<String> {
        if (!json.has(key)) return emptyList()
        val array = json.getJSONArray(key)
        require(array.length() <= 4096) { "routing list too large" }
        return (0 until array.length()).map { index ->
            require(array.get(index) is String) { "routing entry must be a string" }
            array.getString(index).trim()
        }.filter(String::isNotEmpty).distinct()
    }
    val appsWhite = json.optBoolean("is_whitelist", json.optString("mode", "blacklist") == "whitelist")
    val domainsWhite = json.optBoolean(
        "domains_is_whitelist",
        json.optString("domains_mode", if (appsWhite) "whitelist" else "blacklist") == "whitelist",
    )
    val packages = strings("packages")
    require(packages.all(::isRoutingPackageName)) { "invalid routing package name" }
    ServerRoutingRule(
        isWhite = appsWhite,
        packages = packages,
        domains = strings("domains"),
        templateDomains = json.optBoolean("template_domains", false),
        ipIsWhite = json.optBoolean("ip_is_whitelist", json.optString("ip_mode", "blacklist") == "whitelist"),
        ips = strings("ips"),
        domainsIsWhite = domainsWhite,
    )
}.getOrNull()
