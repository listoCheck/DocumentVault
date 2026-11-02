package ru.documentvault.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import ru.documentvault.core.VaultCrypto
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerIntegrationTest {
    @TempDir lateinit var temp: Path
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val b64 = Base64.getEncoder()

    @Test fun `encrypted vault survives login and isolates accounts`() {
        val app = VaultServer(temp.resolve("vault.db")).app()
        app.start("127.0.0.1", 0)
        try {
            val root = "http://127.0.0.1:${app.port()}"
            fun request(method: String, path: String, body: ByteArray = byteArrayOf(), token: String? = null, id: String? = null): HttpResponse<ByteArray> {
                val builder = HttpRequest.newBuilder(URI.create(root + path))
                if (token != null) builder.header("Authorization", "Bearer $token")
                if (id != null) builder.header("X-Document-Id", id)
                if (path == "/v1/accounts" || path == "/v1/sessions") builder.header("Content-Type", "application/json")
                return client.send(builder.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun register(name: String, key: ByteArray, salt: ByteArray): HttpResponse<ByteArray> {
                val wrappingKey = VaultCrypto.deriveRecoveryKey("recovery passphrase".toCharArray(), salt)
                val wrapped = VaultCrypto.encrypt(wrappingKey, key, "master-key".toByteArray())
                val body = mapper.writeValueAsBytes(mapOf("username" to name, "password" to "password-for-login", "recoverySalt" to b64.encodeToString(salt), "wrappedKey" to b64.encodeToString(wrapped)))
                return request("POST", "/v1/accounts", body)
            }
            fun login(name: String): String {
                val result = request("POST", "/v1/sessions", mapper.writeValueAsBytes(mapOf("username" to name, "password" to "password-for-login")))
                assertEquals(200, result.statusCode())
                return mapper.readTree(result.body())["token"].asText()
            }
            val masterKey = VaultCrypto.newMasterKey()
            val salt = VaultCrypto.newSalt()
            assertEquals(201, register("alice", masterKey, salt).statusCode())
            assertEquals(409, register("alice", masterKey, salt).statusCode())
            assertEquals(201, register("bob", VaultCrypto.newMasterKey(), VaultCrypto.newSalt()).statusCode())
            assertEquals(401, request("GET", "/v1/documents").statusCode())
            val alice = login("alice")
            val bob = login("bob")
            val vault = mapper.readTree(request("GET", "/v1/vault", token = alice).body())
            val restoreKey = VaultCrypto.deriveRecoveryKey("recovery passphrase".toCharArray(), Base64.getDecoder().decode(vault["recoverySalt"].asText()))
            assertContentEquals(masterKey, VaultCrypto.decrypt(restoreKey, Base64.getDecoder().decode(vault["wrappedKey"].asText()), "master-key".toByteArray()))
            val id = UUID.randomUUID().toString()
            val plaintext = "секретное содержимое".toByteArray()
            val encrypted = VaultCrypto.encrypt(masterKey, plaintext, "document:$id".toByteArray())
            assertEquals(201, request("POST", "/v1/documents", encrypted, alice, id).statusCode())
            assertEquals(409, request("POST", "/v1/documents", encrypted, alice, id).statusCode())
            val stored = request("GET", "/v1/documents/$id", token = alice)
            assertEquals(200, stored.statusCode())
            assertContentEquals(plaintext, VaultCrypto.decrypt(masterKey, stored.body(), "document:$id".toByteArray()))
            assertEquals(404, request("GET", "/v1/documents/$id", token = bob).statusCode())
            assertEquals(404, request("DELETE", "/v1/documents/$id", token = bob).statusCode())
            val meta = VaultCrypto.encrypt(masterKey, "private metadata".toByteArray(), "metadata:$id".toByteArray())
            assertEquals(404, request("PUT", "/v1/documents/$id/metadata", meta, bob).statusCode())
            assertEquals(204, request("PUT", "/v1/documents/$id/metadata", meta, alice).statusCode())
            assertEquals(404, request("GET", "/v1/documents/$id/metadata", token = bob).statusCode())
            assertContentEquals(meta, request("GET", "/v1/documents/$id/metadata", token = alice).body())
            assertTrue(String(request("GET", "/v1/documents", token = alice).body()).contains(id))
            assertFalse(String(request("GET", "/v1/documents", token = bob).body()).contains(id))
            assertFalse(temp.resolve("vault.db").toFile().readBytes().toString(Charsets.ISO_8859_1).contains(plaintext.toString(Charsets.ISO_8859_1)))
            val backup = temp.resolve("backup.db")
            DatabaseBackup.create(temp.resolve("vault.db"), backup)
            val restored = VaultServer(backup).app()
            restored.start("127.0.0.1", 0)
            try {
                val backupRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${restored.port()}/v1/documents/$id"))
                    .header("Authorization", "Bearer $alice").GET().build()
                val restoredDocument = client.send(backupRequest, HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, restoredDocument.statusCode())
                assertContentEquals(plaintext, VaultCrypto.decrypt(masterKey, restoredDocument.body(), "document:$id".toByteArray()))
            } finally { restored.stop() }
            assertEquals(204, request("DELETE", "/v1/sessions/current", token = alice).statusCode())
            assertEquals(401, request("GET", "/v1/vault", token = alice).statusCode())
        } finally { app.stop() }
    }
}
