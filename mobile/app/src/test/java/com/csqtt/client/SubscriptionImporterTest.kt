package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.encryptCsqttPayload
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class SubscriptionImporterTest {
    private fun withServer(
        body: String,
        status: Int = 200,
        block: suspend (String) -> Unit,
    ) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/sub")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesGroupJsonWithEncryptedAndOpenNodes() = withServer(
        """
        {
          "ok": true,
          "group": {
            "name": "Demo",
            "items": [
              {
                "name": "Node A",
                "sub_uuid": "u1",
                "link": "csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=secret&power=50"
              },
              {
                "name": "Node B",
                "sub_uuid": "u2",
                "workers": "30",
                "link": "csqtt://${encryptCsqttPayload("secret@198.51.100.9:47000")}"
              }
            ]
          }
        }
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals("Demo", preview.title)
        assertEquals(2, preview.nodes.size)
        assertEquals(0, preview.skipped)
        assertEquals(false, preview.isSingle)

        val first = preview.nodes[0]
        assertEquals("Node A", first.name)
        assertEquals("203.0.113.7", first.host)
        assertEquals(46000, first.port)
        assertEquals("secret", first.password)
        assertEquals(50, first.power)
        assertEquals("u1", first.subUuid)

        val second = preview.nodes[1]
        assertEquals("198.51.100.9", second.host)
        assertEquals(47000, second.port)
        assertEquals("secret", second.password)
        assertEquals(30, second.power)
    }

    @Test
    fun skipsUndecryptableNodesAndReportsThem() = withServer(
        """
        {
          "ok": true,
          "group": {
            "name": "Mixed",
            "items": [
              {"name": "Broken", "link": "csqtt://not-a-valid-payload"},
              {"name": "Good", "link": "csqtt://connect?v=2&host=203.0.113.8&peer=46000&password=secret"}
            ]
          }
        }
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals(1, preview.nodes.size)
        assertEquals(1, preview.skipped)
    }

    @Test
    fun reportsServerRejection() = withServer(
        """{"ok": false, "error": "subscription expired"}""",
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Failure)
        assertEquals(
            "subscription expired",
            (result as SubscriptionFetchResult.Failure).message,
        )
    }

    @Test
    fun followsRedirectWithinAllowedScheme() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            exchange.responseHeaders.add("Location", "/final")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/final") { exchange ->
            val bytes = """
                {"ok": true, "group": {"name": "Redirected", "items": [
                  {"name": "Node", "link": "csqtt://connect?v=2&host=203.0.113.10&peer=46000&password=secret"}
                ]}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/sub"
            val result = SubscriptionImporter.fetch(url, allowHttp = true)
            assertTrue(result is SubscriptionFetchResult.Success)
            val preview = (result as SubscriptionFetchResult.Success).preview
            assertEquals("Redirected", preview.title)
            assertEquals(1, preview.nodes.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsRedirectToDisallowedScheme() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            exchange.responseHeaders.add("Location", "ftp://example.com/sub")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/sub"
            val result = SubscriptionImporter.fetch(url, allowHttp = true)
            assertTrue(result is SubscriptionFetchResult.Failure)
            assertEquals(
                "Поддерживаются только HTTPS-ссылки",
                (result as SubscriptionFetchResult.Failure).message,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesServerGroupJsonWithClientsArrayAndDecryptsV2Links() = withServer(
        """
        {
          "ok": true,
          "group": {"id": "g1", "name": "Team", "sub_uuid": "group-uuid"},
          "clients": [
            {
              "name": "Client A",
              "sub_uuid": "u1",
              "link": "csqtt://connect?v=2&host=203.0.113.20&peer=46000&password=secretA&power=40"
            },
            {
              "name": "Client B",
              "sub_uuid": "u2",
              "link": "csqtt://${encryptCsqttPayload("connect?v=2&host=203.0.113.21&peer=46001&password=secretB&hashes=abcdefghijklmnop1")}"
            }
          ]
        }
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals("Team", preview.title)
        assertEquals(2, preview.nodes.size)
        assertEquals(0, preview.skipped)
        assertTrue(preview.isSingle.not())

        val first = preview.nodes[0]
        assertEquals("Client A", first.name)
        assertEquals("203.0.113.20", first.host)
        assertEquals(46000, first.port)
        assertEquals("secretA", first.password)
        assertEquals(40, first.power)
        assertEquals("u1", first.subUuid)

        val second = preview.nodes[1]
        assertEquals("Client B", second.name)
        assertEquals("203.0.113.21", second.host)
        assertEquals(46001, second.port)
        assertEquals("secretB", second.password)
        assertEquals(listOf("abcdefghijklmnop1"), second.hashes)
    }

    @Test
    fun resolvesGroupSubJsonEndpoint() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/group/sub-group") { exchange ->
            val bytes = "<html><head><title>Fallback</title></head><body></body></html>"
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/group/sub-group.json") { exchange ->
            val bytes = """
                {
                  "ok": true,
                  "group": {"id": "g1", "name": "Team", "sub_uuid": "group-uuid"},
                  "clients": [
                    {"name": "A", "link": "csqtt://connect?v=2&host=203.0.113.30&peer=46000&password=secret"},
                    {"name": "B", "link": "csqtt://connect?v=2&host=203.0.113.31&peer=46001&password=secret"}
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/group/sub-group"
            val result = SubscriptionImporter.fetch(url, allowHttp = true)
            assertTrue(result is SubscriptionFetchResult.Success)
            val preview = (result as SubscriptionFetchResult.Success).preview
            assertEquals("Team", preview.title)
            assertEquals(2, preview.nodes.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesBareJsonArrayOfClients() = withServer(
        """
        [
          {"name": "A", "link": "csqtt://connect?v=2&host=203.0.113.40&peer=46000&password=secret"},
          {"name": "B", "link": "csqtt://${encryptCsqttPayload("connect?v=2&host=203.0.113.41&peer=46001&password=secret")}"}
        ]
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals(2, preview.nodes.size)
        assertEquals("203.0.113.40", preview.nodes[0].host)
        assertEquals("203.0.113.41", preview.nodes[1].host)
    }

    @Test
    fun parsesAllLinksFromHtmlPage() = withServer(
        """
        <html>
          <head><title>Group Page</title></head>
          <body>
            <a href="csqtt://connect?v=2&host=203.0.113.50&peer=46000&password=secret">first</a>
            <a href="csqtt://${encryptCsqttPayload("connect?v=2&host=203.0.113.51&peer=46001&password=secret")}">second</a>
          </body>
        </html>
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals("Group Page", preview.title)
        assertEquals(2, preview.nodes.size)
        assertEquals(setOf("203.0.113.50", "203.0.113.51"), preview.nodes.map { it.host }.toSet())
    }

    @Test
    fun parsesHtmlPageFallback() = withServer(
        """
        <html>
          <head><title>My Sub</title></head>
          <body>
            <a href="csqtt://connect?v=2&host=203.0.113.9&peer=46001&password=secret">connect</a>
          </body>
        </html>
        """.trimIndent(),
    ) { url ->
        val result = SubscriptionImporter.fetch(url, allowHttp = true)
        assertTrue(result is SubscriptionFetchResult.Success)
        val preview = (result as SubscriptionFetchResult.Success).preview
        assertEquals("My Sub", preview.title)
        assertTrue(preview.isSingle)
        assertEquals(1, preview.nodes.size)
        assertEquals("203.0.113.9", preview.nodes[0].host)
        assertEquals(46001, preview.nodes[0].port)
    }
}
