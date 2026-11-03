package ru.documentvault.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticationLimitsTest {
    @TempDir lateinit var temp: Path
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test fun `successful login does not reset attempts and removes only expired sessions`() {
        val database = temp.resolve("auth.db")
        val app = VaultServer(database).app()
        app.start("127.0.0.1", 0)
        try {
            val root = "http://127.0.0.1:${app.port()}"
            fun post(path: String, body: ByteArray): HttpResponse<ByteArray> = client.send(
                HttpRequest.newBuilder(URI.create(root + path)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            val encoder = Base64.getEncoder()
            val wrapped = ByteArray(61).also { it[0] = 1 }
            val registration = json.writeValueAsBytes(mapOf("username" to "alice", "password" to "password-for-login", "recoverySalt" to encoder.encodeToString(ByteArray(16)), "wrappedKey" to encoder.encodeToString(wrapped)))
            val account = post("/v1/accounts", registration)
            assertEquals(201, account.statusCode())
            val accountId = json.readTree(account.body())["id"].asText()
            val now = Instant.now().epochSecond
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
                db.prepareStatement("INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)").use { s ->
                    for ((id, expiry) in listOf(1 to now - 1, 2 to now + 3600)) {
                        s.setBytes(1, ByteArray(32) { id.toByte() }); s.setString(2, accountId); s.setLong(3, expiry); s.executeUpdate()
                    }
                }
            }
            fun login(password: String) = post("/v1/sessions", json.writeValueAsBytes(mapOf("username" to "alice", "password" to password))).statusCode()
            repeat(3) { assertEquals(401, login("incorrect-password")) }
            assertEquals(200, login("password-for-login"))
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
                db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM sessions").use { r -> r.next(); assertEquals(2, r.getInt(1)) } }
            }
            assertEquals(401, login("incorrect-password"))
            assertEquals(429, login("password-for-login"))
        } finally { app.stop() }
    }

    @Test fun `registration rejects excessive requests before processing them`() {
        val app = VaultServer(temp.resolve("registration.db")).app()
        app.start("127.0.0.1", 0)
        try {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/v1/accounts"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")) .build()
            repeat(10) { assertEquals(400, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) }
            assertEquals(429, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
        } finally { app.stop() }
    }

    @Test fun `trusted loopback proxy separates client limits while default ignores headers`() {
        for (trust in listOf(false, true)) {
            val app = VaultServer(temp.resolve("proxy-$trust.db"), trustLoopbackProxy = trust).app()
            app.start("127.0.0.1", 0)
            try {
                fun attempt(ip: String): Int {
                    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/v1/sessions"))
                        .header("X-Real-IP", ip).POST(HttpRequest.BodyPublishers.ofString("{}")) .build()
                    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                }
                repeat(5) { assertEquals(400, attempt("192.0.2.1")) }
                assertEquals(429, attempt("192.0.2.1"))
                assertEquals(if (trust) 400 else 429, attempt("192.0.2.2"))
            } finally { app.stop() }
        }
    }

    @Test fun `untrusted peers and malformed forwarded addresses cannot select a limit bucket`() {
        assertEquals("192.0.2.8", limiterAddress("192.0.2.8", "198.51.100.1", true))
        assertEquals("127.0.0.1", limiterAddress("127.0.0.1", "198.51.100.1, 203.0.113.1", true))
        assertEquals("127.0.0.1", limiterAddress("127.0.0.1", "example.org", true))
        assertEquals("127.0.0.1", limiterAddress("127.0.0.1", "198.51.100.1", false))
        assertEquals("198.51.100.1", limiterAddress("127.0.0.1", "198.51.100.1", true))
    }
}
