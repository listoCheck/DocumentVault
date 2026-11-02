package ru.documentvault.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.Javalin
import io.javalin.http.BadRequestResponse
import io.javalin.http.ConflictResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.http.TooManyRequestsResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val MAX_BODY = 16L * 1024 * 1024
private val json = ObjectMapper()
private val base64 = Base64.getEncoder()
private val decoder = Base64.getDecoder()
private val random = SecureRandom()
private val passwordEncoder = BCryptPasswordEncoder(12)
private val placeholderPasswordHash = passwordEncoder.encode("not-a-real-password")

/** Local development server; deploy only after adding HTTPS and operational safeguards. */
class VaultServer(database: Path) {
    private val jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath()}"
    private val loginLimiter = LoginLimiter()

    init {
        connection().use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, recovery_salt BLOB NOT NULL, wrapped_key BLOB NOT NULL)")
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS sessions (token_hash BLOB PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, expires_at INTEGER NOT NULL)")
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, ciphertext BLOB NOT NULL, created_at INTEGER NOT NULL)")
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS document_metadata (document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE, ciphertext BLOB NOT NULL)")
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl).also {
        it.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    fun app(): Javalin = Javalin.create { config ->
        config.http.maxRequestSize = MAX_BODY
        config.routes.apply {
            get("/health") { it.result("ok") }
            post("/v1/accounts") { register(it) }
            post("/v1/sessions") { login(it) }
            delete("/v1/sessions/current") { logout(it) }
            get("/v1/vault") { vault(it) }
            get("/v1/documents") { listDocuments(it) }
            post("/v1/documents") { upload(it) }
            get("/v1/documents/{id}") { download(it) }
            get("/v1/documents/{id}/metadata") { metadata(it) }
            put("/v1/documents/{id}/metadata") { saveMetadata(it) }
            delete("/v1/documents/{id}") { deleteDocument(it) }
        }
    }

    private fun readJson(ctx: Context): JsonNode = try {
        json.readTree(ctx.body()) ?: throw BadRequestResponse("Invalid JSON")
    } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
        throw BadRequestResponse("Invalid JSON")
    }

    private fun field(body: JsonNode, name: String, max: Int): String {
        val node = body.get(name)
        if (node == null || !node.isTextual || node.textValue().length !in 1..max) throw BadRequestResponse("Invalid $name")
        return node.textValue()
    }

    private fun decode(value: String, name: String): ByteArray = try {
        decoder.decode(value)
    } catch (_: IllegalArgumentException) { throw BadRequestResponse("Invalid $name") }

    private fun validateUsername(value: String): String {
        if (!value.matches(Regex("[a-zA-Z0-9_.-]{3,40}"))) throw BadRequestResponse("Invalid username")
        return value.lowercase(java.util.Locale.ROOT)
    }

    private fun validatePassword(value: String) {
        if (value.length < 12 || value.toByteArray(Charsets.UTF_8).size > 72) throw BadRequestResponse("Password must be at least 12 characters and at most 72 UTF-8 bytes")
    }

    private fun register(ctx: Context) {
        val body = readJson(ctx)
        val username = validateUsername(field(body, "username", 40))
        val password = field(body, "password", 72).also(::validatePassword)
        val salt = decode(field(body, "recoverySalt", 64), "recoverySalt")
        val wrappedKey = decode(field(body, "wrappedKey", 256), "wrappedKey")
        if (salt.size != 16 || wrappedKey.size != 61 || wrappedKey[0] != 1.toByte()) throw BadRequestResponse("Invalid recovery envelope")
        val accountId = UUID.randomUUID().toString()
        val hash = passwordEncoder.encode(password)
        connection().use { db ->
            try {
                db.prepareStatement("INSERT INTO accounts (id, username, password_hash, recovery_salt, wrapped_key) VALUES (?, ?, ?, ?, ?)").use { s ->
                    s.setString(1, accountId); s.setString(2, username); s.setString(3, hash); s.setBytes(4, salt); s.setBytes(5, wrappedKey); s.executeUpdate()
                }
            } catch (e: java.sql.SQLException) {
                if (e.errorCode == 19) throw ConflictResponse("Account already exists") else throw e
            }
        }
        ctx.status(201).contentType("application/json").result(json.writeValueAsString(mapOf("id" to accountId)))
    }

    private fun login(ctx: Context) {
        if (!loginLimiter.allow(ctx.ip())) throw TooManyRequestsResponse("Too many login attempts; try again later")
        val body = readJson(ctx)
        val username = validateUsername(field(body, "username", 40))
        val password = field(body, "password", 72)
        if (password.toByteArray(Charsets.UTF_8).size > 72) throw UnauthorizedResponse("Invalid credentials")
        val account = connection().use { db ->
            db.prepareStatement("SELECT id, password_hash FROM accounts WHERE username = ?").use { s ->
                s.setString(1, username)
                s.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null }
            }
        }
        val matches = passwordEncoder.matches(password, account?.second ?: placeholderPasswordHash)
        if (account == null || !matches) throw UnauthorizedResponse("Invalid credentials")
        loginLimiter.clear(ctx.ip())
        val token = ByteArray(32).also(random::nextBytes)
        val expires = Instant.now().epochSecond + 24 * 60 * 60
        connection().use { db ->
            db.prepareStatement("INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)").use { s ->
                s.setBytes(1, sha256(token)); s.setString(2, account.first); s.setLong(3, expires); s.executeUpdate()
            }
        }
        ctx.header("Cache-Control", "no-store").contentType("application/json").result(json.writeValueAsString(mapOf("token" to Base64.getUrlEncoder().withoutPadding().encodeToString(token), "expiresAt" to expires)))
    }

    private fun token(ctx: Context): ByteArray {
        val auth = ctx.header("Authorization") ?: throw UnauthorizedResponse("Authentication required")
        if (!auth.startsWith("Bearer ")) throw UnauthorizedResponse("Authentication required")
        return try { Base64.getUrlDecoder().decode(auth.substring(7)).also { if (it.size != 32) throw UnauthorizedResponse("Invalid session") } }
        catch (_: IllegalArgumentException) { throw UnauthorizedResponse("Invalid session") }
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

    private fun accountId(ctx: Context): String {
        val tokenHash = sha256(token(ctx))
        return connection().use { db ->
            db.prepareStatement("SELECT account_id FROM sessions WHERE token_hash = ? AND expires_at > ?").use { s ->
                s.setBytes(1, tokenHash); s.setLong(2, Instant.now().epochSecond)
                s.executeQuery().use { r -> if (r.next()) r.getString(1) else throw UnauthorizedResponse("Invalid session") }
            }
        }
    }

    private fun logout(ctx: Context) {
        val hash = sha256(token(ctx))
        connection().use { db -> db.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { s -> s.setBytes(1, hash); s.executeUpdate() } }
        ctx.status(204)
    }

    private fun vault(ctx: Context) {
        val user = accountId(ctx)
        connection().use { db -> db.prepareStatement("SELECT recovery_salt, wrapped_key FROM accounts WHERE id = ?").use { s ->
            s.setString(1, user); s.executeQuery().use { r ->
                if (!r.next()) throw UnauthorizedResponse("Invalid session")
                ctx.header("Cache-Control", "no-store").contentType("application/json").result(json.writeValueAsString(mapOf("recoverySalt" to base64.encodeToString(r.getBytes(1)), "wrappedKey" to base64.encodeToString(r.getBytes(2)))))
            }
        } }
    }

    private fun listDocuments(ctx: Context) {
        val user = accountId(ctx)
        val documents = mutableListOf<Map<String, Any>>()
        connection().use { db -> db.prepareStatement("SELECT id, created_at FROM documents WHERE account_id = ? ORDER BY created_at DESC, id").use { s ->
            s.setString(1, user); s.executeQuery().use { r -> while (r.next()) documents.add(mapOf("id" to r.getString(1), "createdAt" to r.getLong(2))) }
        } }
        ctx.header("Cache-Control", "no-store").contentType("application/json").result(json.writeValueAsString(documents))
    }

    private fun upload(ctx: Context) {
        val user = accountId(ctx)
        val id = try { UUID.fromString(ctx.header("X-Document-Id")).toString() } catch (_: Exception) { throw BadRequestResponse("A UUID X-Document-Id is required") }
        val data = ctx.bodyAsBytes()
        if (data.size < 29 || data.size.toLong() > MAX_BODY || data[0] != 1.toByte()) throw BadRequestResponse("Invalid encrypted envelope")
        connection().use { db ->
            try { db.prepareStatement("INSERT INTO documents (id, account_id, ciphertext, created_at) VALUES (?, ?, ?, ?)").use { s ->
                s.setString(1, id); s.setString(2, user); s.setBytes(3, data); s.setLong(4, Instant.now().epochSecond); s.executeUpdate()
            } } catch (e: java.sql.SQLException) { if (e.errorCode == 19) throw ConflictResponse("Document already exists") else throw e }
        }
        ctx.status(201).contentType("application/json").result(json.writeValueAsString(mapOf("id" to id)))
    }

    private fun download(ctx: Context) {
        val user = accountId(ctx)
        connection().use { db -> db.prepareStatement("SELECT ciphertext FROM documents WHERE id = ? AND account_id = ?").use { s ->
            s.setString(1, ctx.pathParam("id")); s.setString(2, user); s.executeQuery().use { r ->
                if (!r.next()) throw NotFoundResponse("Document not found")
                ctx.header("Cache-Control", "no-store").contentType("application/octet-stream").result(r.getBytes(1))
            }
        } }
    }

    private fun deleteDocument(ctx: Context) {
        val user = accountId(ctx)
        val deleted = connection().use { db -> db.prepareStatement("DELETE FROM documents WHERE id = ? AND account_id = ?").use { s ->
            s.setString(1, ctx.pathParam("id")); s.setString(2, user); s.executeUpdate()
        } }
        if (deleted == 0) throw NotFoundResponse("Document not found")
        ctx.status(204)
    }
    private fun metadata(ctx: Context) {
        val user = accountId(ctx)
        connection().use { db -> db.prepareStatement("SELECT m.ciphertext FROM document_metadata m JOIN documents d ON d.id = m.document_id WHERE d.id = ? AND d.account_id = ?").use { s ->
            s.setString(1, ctx.pathParam("id")); s.setString(2, user); s.executeQuery().use { r ->
                if (!r.next()) throw NotFoundResponse("Metadata not found")
                ctx.header("Cache-Control", "no-store").contentType("application/octet-stream").result(r.getBytes(1))
            }
        } }
    }
    private fun saveMetadata(ctx: Context) {
        val user = accountId(ctx)
        val id = ctx.pathParam("id")
        val data = ctx.bodyAsBytes()
        if (data.size !in 29..(1024 * 1024) || data[0] != 1.toByte()) throw BadRequestResponse("Invalid encrypted metadata")
        connection().use { db -> db.prepareStatement("INSERT INTO document_metadata (document_id, ciphertext) SELECT id, ? FROM documents WHERE id = ? AND account_id = ? ON CONFLICT(document_id) DO UPDATE SET ciphertext=excluded.ciphertext").use { s ->
            s.setBytes(1, data); s.setString(2, id); s.setString(3, user)
            if (s.executeUpdate() == 0) throw NotFoundResponse("Document not found")
        } }
        ctx.status(204)
    }
}

fun main() {
    val database = Path.of(System.getenv("VAULT_DATABASE") ?: "documentvault.db")
    val port = System.getenv("VAULT_PORT")?.toInt() ?: 7070
    // The default is local-only. Container deployments can bind to an isolated network behind TLS.
    val host = System.getenv("VAULT_BIND") ?: "127.0.0.1"
    val app = VaultServer(database).app()
    app.start(host, port)
}

/** Per-process IP limit. A public deployment also needs an edge rate limiter. */
internal class LoginLimiter(private val maxAttempts: Int = 5, private val windowSeconds: Long = 600, private val maxEntries: Int = 10_000) {
    private val entries = LinkedHashMap<String, MutableList<Long>>(16, 0.75f, true)
    @Synchronized fun allow(ip: String, now: Long = Instant.now().epochSecond): Boolean {
        val attempts = entries.getOrPut(ip) { mutableListOf() }
        attempts.removeAll { it <= now - windowSeconds }
        if (attempts.size >= maxAttempts) return false
        attempts.add(now)
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
        return true
    }
    @Synchronized fun clear(ip: String) { entries.remove(ip) }
}
