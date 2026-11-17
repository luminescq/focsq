package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.normalizeCsqttPower
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

internal data class SubscriptionNode(
    val name: String,
    val host: String,
    val port: Int,
    val password: String,
    val power: Int?,
    val hashes: List<String>,
    val subUuid: String?,
)

internal data class SubscriptionPreview(
    val title: String,
    val nodes: List<SubscriptionNode>,
    val skipped: Int,
    val isSingle: Boolean,
)

internal sealed interface SubscriptionFetchResult {
    data class Success(val preview: SubscriptionPreview) : SubscriptionFetchResult
    data class Failure(val message: String) : SubscriptionFetchResult
}

internal data class SubscriptionProfileWrite(
    val profile: Int,
    val host: String,
    val port: Int,
    val password: String,
    val hashes: List<String>,
    val power: Int?,
    val nodeName: String,
    val title: String,
    val url: String,
    val subUuid: String?,
)

internal data class SubscriptionImportSummary(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val firstProfile: Int?,
) {
    val total: Int get() = imported + updated

    fun message(): String {
        val parts = mutableListOf<String>()
        if (imported > 0) parts += "добавлено: $imported"
        if (updated > 0) parts += "обновлено: $updated"
        if (skipped > 0) parts += "пропущено: $skipped"
        return if (parts.isEmpty()) "Подписка не изменилась" else "Подписка — ${parts.joinToString(", ")}"
    }
}

internal fun normalizeSubscriptionUrl(raw: String, allowHttp: Boolean = false): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        if (allowHttp && trimmed.startsWith("https://", ignoreCase = true)) {
            "http://" + trimmed.substring(8)
        } else {
            trimmed
        }
    } else {
        if (allowHttp) "http://$trimmed" else "https://$trimmed"
    }
}

/**
 * Fetches and parses CSQTT subscriptions the same way focsq does:
 * JSON group/single responses first, HTML page fallback, decrypted csqtt:// links.
 */
internal object SubscriptionImporter {
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val MAX_REDIRECTS = 5

    private sealed interface BodyResult {
        data class Body(val text: String) : BodyResult
        data class Failed(val message: String) : BodyResult
    }

    private sealed interface ParseResult {
        data class Success(val preview: SubscriptionPreview) : ParseResult
        data class Rejected(val message: String) : ParseResult
        data class Invalid(val message: String) : ParseResult
    }

    private val trustAllSslSocketFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            },
        )
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        sslContext.socketFactory
    }

    private val trustAllHostnameVerifier: javax.net.ssl.HostnameVerifier =
        javax.net.ssl.HostnameVerifier { _, _ -> true }

    suspend fun fetch(
        rawUrl: String,
        allowHttp: Boolean = false,
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val url = normalizeSubscriptionUrl(rawUrl, allowHttp)
        if (url.isEmpty()) return@withContext SubscriptionFetchResult.Failure("Укажите ссылку подписки")
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.host.isNullOrBlank()) {
            return@withContext SubscriptionFetchResult.Failure("Некорректная ссылка подписки")
        }
        if (!isAllowedScheme(url, allowHttp)) {
            return@withContext SubscriptionFetchResult.Failure("Поддерживаются только HTTPS-ссылки")
        }

        var lastError = "Не удалось загрузить подписку"
        var rejectedError: String? = null
        for (candidate in candidateUrls(uri)) {
            when (val body = fetchBody(candidate, allowHttp)) {
                is BodyResult.Failed -> lastError = body.message
                is BodyResult.Body -> when (val parsed = parseBody(body.text)) {
                    is ParseResult.Success -> return@withContext SubscriptionFetchResult.Success(parsed.preview)
                    is ParseResult.Rejected -> rejectedError = parsed.message
                    is ParseResult.Invalid -> lastError = parsed.message
                }
            }
        }
        SubscriptionFetchResult.Failure(rejectedError ?: lastError)
    }

    suspend fun importNodes(
        store: SettingsStore,
        rawUrl: String,
        preview: SubscriptionPreview,
        allowHttp: Boolean = false,
    ): SubscriptionImportSummary {
        val url = normalizeSubscriptionUrl(rawUrl)
        if (url.isEmpty() || preview.nodes.isEmpty()) {
            return SubscriptionImportSummary(0, 0, preview.skipped, null)
        }

        val occupancy = store.occupiedProfiles.first()
        val subscriptions = store.profileSubscriptions.first()
        val snapshots = mutableMapOf<Int, ProfileConnectionSnapshot>()
        for (slot in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
            if (slot in occupancy) {
                snapshots[slot] = store.readProfileConnectionSettings(slot)
            }
        }

        val existingForUrl = subscriptions
            .filterValues { it.url == url }
            .keys
            .sorted()
        val byUuid = mutableMapOf<String, Int>()
        for ((slot, info) in subscriptions) {
            if (info.subUuid.isNotEmpty()) byUuid[info.subUuid] = slot
        }
        val byEndpoint = mutableMapOf<String, Int>()
        for (slot in existingForUrl) {
            snapshots[slot]?.endpointKey()?.let { byEndpoint.putIfAbsent(it, slot) }
        }
        val globalEndpoints = mutableMapOf<String, Int>()
        for ((slot, snapshot) in snapshots) {
            snapshot.endpointKey()?.let { globalEndpoints.putIfAbsent(it, slot) }
        }

        val freeSlots = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
            .filter { it !in occupancy }
            .toMutableList()
        val usedSlots = mutableSetOf<Int>()
        val seenEndpoints = mutableSetOf<String>()
        val writes = mutableListOf<SubscriptionProfileWrite>()
        var imported = 0
        var updated = 0
        var skipped = preview.skipped

        for (node in preview.nodes) {
            val endpoint = endpointKey(node.host, node.port)
            if (endpoint == null || !seenEndpoints.add(endpoint)) {
                skipped++
                continue
            }

            val uuid = node.subUuid.orEmpty()
            val matched = (if (uuid.isNotEmpty()) byUuid[uuid] else null)
                ?: byEndpoint[endpoint]
                ?: if (preview.isSingle) existingForUrl.firstOrNull { it !in usedSlots } else null

            if (matched != null) {
                if (matched in usedSlots) {
                    skipped++
                    continue
                }
                usedSlots += matched
                writes += node.toWrite(matched, url, preview.title)
                updated++
                if (uuid.isNotEmpty()) byUuid[uuid] = matched
                byEndpoint[endpoint] = matched
                globalEndpoints[endpoint] = matched
                continue
            }

            // Address already belongs to another profile (or another node of this
            // subscription) — do not duplicate it in a limited set of slots.
            if (globalEndpoints.containsKey(endpoint)) {
                skipped++
                continue
            }

            val slot = freeSlots.firstOrNull { it !in usedSlots }
            if (slot == null) {
                skipped++
                continue
            }
            usedSlots += slot
            writes += node.toWrite(slot, url, preview.title)
            imported++
            if (uuid.isNotEmpty()) byUuid[uuid] = slot
            byEndpoint[endpoint] = slot
            globalEndpoints[endpoint] = slot
        }

        store.applySubscriptionImport(writes)
        store.recordSubscriptionImport(
            url = url,
            title = preview.title,
            allowHttp = allowHttp,
        )
        return SubscriptionImportSummary(
            imported = imported,
            updated = updated,
            skipped = skipped,
            firstProfile = writes.firstOrNull()?.profile,
        )
    }

    private fun SubscriptionNode.toWrite(
        profile: Int,
        url: String,
        title: String,
    ): SubscriptionProfileWrite = SubscriptionProfileWrite(
        profile = profile,
        host = host,
        port = port,
        password = password,
        hashes = hashes,
        power = power,
        nodeName = name,
        title = title,
        url = url,
        subUuid = subUuid,
    )

    private fun ProfileConnectionSnapshot.endpointKey(): String? {
        val raw = peer.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith('[')) {
            val close = raw.indexOf(']')
            if (close <= 1) return null
            val host = raw.substring(1, close)
            val rest = raw.substring(close + 1)
            val port = if (rest.startsWith(':')) rest.substring(1).toIntOrNull() else null
            return endpointKey(host, port ?: serverPeerPort)
        }
        val hostPort = Regex("^(.*):(\\d{1,5})$").matchEntire(raw)
        return if (hostPort != null) {
            endpointKey(hostPort.groupValues[1], hostPort.groupValues[2].toIntOrNull() ?: serverPeerPort)
        } else {
            endpointKey(raw, serverPeerPort)
        }
    }

    private fun endpointKey(host: String, port: Int): String? {
        val normalized = host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty() || port !in 1..65535) return null
        return "$normalized|$port"
    }

    private fun candidateUrls(uri: URI): List<String> {
        val path = uri.path.orEmpty()
        val candidates = LinkedHashSet<String>()

        fun withPath(newPath: String): String? = runCatching {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, newPath, uri.query, uri.fragment).toString()
        }.getOrNull()

        when {
            path.endsWith("/sub") -> {
                withPath(path.dropLast(4) + "/json")?.let(candidates::add)
                withPath(path.dropLast(4) + "/sub.json")?.let(candidates::add)
                candidates += uri.toString()
            }
            path.endsWith("/json") || path.endsWith(".json") -> candidates += uri.toString()
            else -> {
                withPath("$path/json")?.let(candidates::add)
                withPath("$path/sub.json")?.let(candidates::add)
                withPath("$path/sub")?.let(candidates::add)
                withPath("$path.json")?.let(candidates::add)
                candidates += uri.toString()
            }
        }
        return candidates.toList()
    }

    private fun fetchBody(
        rawUrl: String,
        allowHttp: Boolean,
        redirectsLeft: Int = MAX_REDIRECTS,
    ): BodyResult {
        val connection = runCatching {
            (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "csqtt-client")
                setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                if (allowHttp && this is javax.net.ssl.HttpsURLConnection) {
                    sslSocketFactory = trustAllSslSocketFactory
                    hostnameVerifier = trustAllHostnameVerifier
                }
            }
        }.getOrElse {
            return BodyResult.Failed("Ошибка сети: ${it.message ?: "нет соединения"}")
        }

        return try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    BodyResult.Failed("Сервер вернул $code")
                } else if (redirectsLeft <= 0) {
                    BodyResult.Failed("Слишком много перенаправлений")
                } else {
                    val next = runCatching { URL(URL(rawUrl), location).toString() }.getOrNull()
                    if (next == null) {
                        BodyResult.Failed("Некорректное перенаправление")
                    } else if (!isAllowedScheme(next, allowHttp)) {
                        BodyResult.Failed("Поддерживаются только HTTPS-ссылки")
                    } else {
                        fetchBody(next, allowHttp, redirectsLeft - 1)
                    }
                }
            } else if (code !in 200..299) {
                BodyResult.Failed("Сервер вернул $code")
            } else {
                val length = connection.contentLengthLong
                if (length > MAX_BODY_BYTES) {
                    BodyResult.Failed("Ответ подписки слишком большой")
                } else {
                    val bytes = connection.inputStream.use(::readLimited)
                    if (bytes.isEmpty()) {
                        BodyResult.Failed("Пустой ответ подписки")
                    } else {
                        BodyResult.Body(String(bytes, charsetOf(connection)))
                    }
                }
            }
        } catch (e: Exception) {
            BodyResult.Failed("Ошибка сети: ${e.message ?: "нет соединения"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowedScheme(url: String, allowHttp: Boolean): Boolean {
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        return scheme == "https" || (allowHttp && scheme == "http")
    }

    private fun charsetOf(connection: HttpURLConnection): Charset {
        val name = connection.contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_BODY_BYTES) {
                throw IllegalStateException("Ответ подписки слишком большой")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseBody(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull()
            if (array != null) return parseNodeArray(array, "Подписка")
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        return if (json != null) parseJson(json) else parseHtml(text)
    }

    private fun parseJson(json: JSONObject): ParseResult {
        if (!json.optBoolean("ok", false)) {
            val error = json.optString("error").trim().ifBlank { "Сервер отклонил подписку" }
            return ParseResult.Rejected(error)
        }
        val group = json.optJSONObject("group")
        val title = group?.optString("name")?.trim()?.ifBlank { "Подписка" } ?: "Подписка"
        val items = group?.optJSONArray("items")
            ?: group?.optJSONArray("clients")
            ?: json.optJSONArray("clients")
            ?: json.optJSONArray("items")
        if (items != null) return parseNodeArray(items, title)
        if (group != null) return ParseResult.Invalid("В подписке нет узлов")
        return parseSingle(json)
    }

    private fun parseNodeArray(items: JSONArray, title: String): ParseResult {
        val nodes = mutableListOf<SubscriptionNode>()
        var skipped = 0
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            val node = item?.let { nodeFrom(it, "Узел ${index + 1}") }
            if (node == null) {
                skipped++
            } else {
                nodes += node
            }
        }
        if (nodes.isEmpty()) {
            return ParseResult.Invalid(
                if (skipped > 0) "Не удалось расшифровать узлы подписки" else "В подписке нет узлов",
            )
        }
        return ParseResult.Success(SubscriptionPreview(title, nodes, skipped, isSingle = false))
    }

    private fun parseSingle(json: JSONObject): ParseResult {
        val title = json.optString("name").trim().ifBlank { "Подписка" }
        val rawLink = json.optString("link").trim()
        if (rawLink.isEmpty()) return ParseResult.Invalid("В подписке нет ссылки")
        val node = nodeFrom(json, title) ?: return ParseResult.Invalid("Не удалось расшифровать конфигурацию")
        return ParseResult.Success(SubscriptionPreview(title, listOf(node), 0, isSingle = true))
    }

    private fun nodeFrom(obj: JSONObject, fallbackName: String): SubscriptionNode? {
        val rawLink = obj.optString("link").trim()
        if (rawLink.isEmpty()) return null
        val parsed = parseCsqttLink(rawLink) ?: return null
        if (parsed.password.isBlank()) return null
        return SubscriptionNode(
            name = obj.optString("name").trim().ifBlank { fallbackName },
            host = parsed.host,
            port = parsed.port,
            password = parsed.password,
            power = readPower(obj) ?: parsed.power,
            hashes = parsed.hashes,
            subUuid = obj.optString("sub_uuid").trim().ifEmpty { null },
        )
    }

    private fun readPower(obj: JSONObject): Int? {
        val raw = obj.opt("power") ?: obj.opt("workers")
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        return normalizeCsqttPower(value)
    }

    private val titlePattern = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val headingPattern = Regex("<h[12][^>]*>(.*?)</h[12]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagPattern = Regex("<[^>]+>")
    private val linkPattern = Regex("""csqtt://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private fun parseHtml(html: String): ParseResult {
        val rawLinks = linkPattern.findAll(html).map { it.value }.distinct().toList()
        if (rawLinks.isEmpty()) return ParseResult.Invalid("В ответе нет ссылки csqtt://")
        val title = (titlePattern.find(html) ?: headingPattern.find(html))
            ?.groupValues
            ?.get(1)
            ?.replace(tagPattern, "")
            ?.trim()
            ?.ifBlank { "Подписка" }
            ?: "Подписка"
        val nodes = mutableListOf<SubscriptionNode>()
        var skipped = 0
        for (rawLink in rawLinks) {
            val parsed = parseCsqttLink(rawLink)
            if (parsed == null || parsed.password.isBlank()) {
                skipped++
                continue
            }
            nodes += SubscriptionNode(
                name = title,
                host = parsed.host,
                port = parsed.port,
                password = parsed.password,
                power = parsed.power,
                hashes = parsed.hashes,
                subUuid = null,
            )
        }
        if (nodes.isEmpty()) return ParseResult.Invalid("Не удалось расшифровать конфигурацию")
        return ParseResult.Success(
            SubscriptionPreview(
                title = title,
                nodes = nodes,
                skipped = skipped,
                isSingle = nodes.size == 1,
            ),
        )
    }
}
