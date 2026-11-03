package ru.documentvault.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageQuotaTest {
    @TempDir lateinit var temp: Path
    private val client = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private fun envelope(size: Int) = ByteArray(size).also { it[0] = 1 }

    @Test fun `quota is atomic and deletion and replacement reclaim space`() {
        val app = VaultServer(temp.resolve("quota.db"), maxDocuments = 2, maxStorageBytes = 100).app()
        app.start("127.0.0.1", 0)
        try {
            val root = "http://127.0.0.1:${app.port()}"
            fun request(method: String, path: String, body: ByteArray = byteArrayOf(), token: String? = null, id: String? = null): HttpResponse<ByteArray> {
                val builder = HttpRequest.newBuilder(URI.create(root + path))
                if (token != null) builder.header("Authorization", "Bearer $token")
                if (id != null) builder.header("X-Document-Id", id)
                return client.send(builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun account(name: String): String {
                val encoder = Base64.getEncoder()
                val body = mapper.writeValueAsBytes(mapOf("username" to name, "password" to "password-for-login", "recoverySalt" to encoder.encodeToString(ByteArray(16)), "wrappedKey" to encoder.encodeToString(envelope(61))))
                assertEquals(201, request("POST", "/v1/accounts", body).statusCode())
                val login = request("POST", "/v1/sessions", mapper.writeValueAsBytes(mapOf("username" to name, "password" to "password-for-login")))
                assertEquals(200, login.statusCode())
                return mapper.readTree(login.body())["token"].asText()
            }
            val alice = account("alice")
            val bob = account("bob")
            val ids = List(2) { UUID.randomUUID().toString() }
            val uploads = ids.map { id -> CompletableFuture.supplyAsync { request("POST", "/v1/documents", envelope(60), alice, id).statusCode() } }
            assertEquals(listOf(201, 413), uploads.map { it.join() }.sorted())
            val winningId = ids[uploads.indexOfFirst { it.join() == 201 }]
            assertEquals(201, request("POST", "/v1/documents", envelope(60), bob, UUID.randomUUID().toString()).statusCode())
            assertEquals(204, request("PUT", "/v1/documents/$winningId/metadata", envelope(40), alice).statusCode())
            assertEquals(413, request("PUT", "/v1/documents/$winningId/metadata", envelope(41), alice).statusCode())
            assertEquals(40, request("GET", "/v1/documents/$winningId/metadata", token = alice).body().size)
            assertEquals(204, request("PUT", "/v1/documents/$winningId/metadata", envelope(29), alice).statusCode())
            assertEquals(204, request("DELETE", "/v1/documents/$winningId", token = alice).statusCode())
            val first = UUID.randomUUID().toString()
            assertEquals(201, request("POST", "/v1/documents", envelope(29), alice, first).statusCode())
            assertEquals(201, request("POST", "/v1/documents", envelope(29), alice, UUID.randomUUID().toString()).statusCode())
            assertEquals(413, request("POST", "/v1/documents", envelope(29), alice, UUID.randomUUID().toString()).statusCode())
            assertEquals(409, request("POST", "/v1/documents", envelope(29), alice, first).statusCode())
        } finally { app.stop() }
    }
}
