package ru.documentvault.desktop

import org.junit.jupiter.api.io.TempDir
import ru.documentvault.core.VaultCrypto
import ru.documentvault.server.VaultServer
import java.nio.file.Path
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun `desktop encrypts uploads and restores document after a new login`() {
        val server = VaultServer(temp.resolve("server.db")).app()
        server.start("127.0.0.1", 0)
        try {
            val api = VaultApi("http://127.0.0.1:${server.port()}")
            api.register("alice", "login password 123", "recovery password 123".toCharArray())
            val token = api.login("alice", "login password 123")
            assertFailsWith<AEADBadTagException> { api.unlock(token, "wrong password".toCharArray()) }
            val key = api.unlock(token, "recovery password 123".toCharArray())
            val id = UUID.randomUUID().toString()
            val data = "содержимое тестового документа".toByteArray()
            val payload = DocumentPayload.encode("справка.pdf", data)
            api.upload(token, id, VaultCrypto.encrypt(key, payload, "document:$id".toByteArray()))
            val metadata = "{\"text\":\"секрет\",\"expiry\":\"2030-12-31\"}".toByteArray()
            api.saveMetadata(token, id, VaultCrypto.encrypt(key, metadata, "metadata:$id".toByteArray()))
            assertTrue(api.list(token).contains(id))
            api.logout(token)
            val secondToken = api.login("alice", "login password 123")
            val secondKey = api.unlock(secondToken, "recovery password 123".toCharArray())
            val decrypted = VaultCrypto.decrypt(secondKey, api.download(secondToken, id), "document:$id".toByteArray())
            val restored = DocumentPayload.decode(decrypted)
            assertContentEquals(metadata, VaultCrypto.decrypt(secondKey, api.metadata(secondToken, id)!!, "metadata:$id".toByteArray()))
            assertEquals("справка.pdf", restored.first)
            assertContentEquals(data, restored.second)
            key.fill(0); secondKey.fill(0); payload.fill(0); decrypted.fill(0)
        } finally { server.stop() }
    }

    @Test fun `decoded filenames do not retain directory paths`() {
        assertEquals("file.pdf", DocumentPayload.decode(DocumentPayload.encode("../../file.pdf", byteArrayOf(1))).first)
        assertEquals("file.pdf", DocumentPayload.decode(DocumentPayload.encode("C:\\Users\\file.pdf", byteArrayOf(1))).first)
    }
    @Test fun `remote servers must use https`() {
        VaultApi("http://127.0.0.1:7070")
        VaultApi("https://example.org")
        assertFailsWith<IllegalArgumentException> { VaultApi("http://example.org") }
        assertFailsWith<IllegalArgumentException> { VaultApi("https://user@example.org") }
    }
}
