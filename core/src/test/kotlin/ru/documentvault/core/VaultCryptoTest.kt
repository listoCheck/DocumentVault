package ru.documentvault.core

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VaultCryptoTest {
    private val context = "document:123".toByteArray()

    @Test fun `decrypts envelope created by Android client`() {
        val envelope = java.util.Base64.getDecoder().decode("AS4SUc3kES7oCn1BucfslatmpJOTyN+A0W0ART596/+xMPSehH+69hM=")
        assertContentEquals("from android".toByteArray(), VaultCrypto.decrypt(ByteArray(32) { 42 }, envelope, context))
    }

    @Test fun `round trip with a fresh nonce`() {
        val key = VaultCrypto.newMasterKey()
        val data = "Конфиденциальный документ".toByteArray()
        val first = VaultCrypto.encrypt(key, data, context)
        val second = VaultCrypto.encrypt(key, data, context)
        assertFalse(first.contentEquals(second))
        assertContentEquals(data, VaultCrypto.decrypt(key, first, context))
    }

    @Test fun `tampering and incorrect context fail authentication`() {
        val key = VaultCrypto.newMasterKey()
        val envelope = VaultCrypto.encrypt(key, "text".toByteArray(), context)
        assertFailsWith<AEADBadTagException> { VaultCrypto.decrypt(key, envelope, "other".toByteArray()) }
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> { VaultCrypto.decrypt(key, envelope, context) }
    }

    @Test fun `recovery password restores wrapped master key`() {
        val password = "correct horse battery staple".toCharArray()
        val salt = VaultCrypto.newSalt()
        val recoveryKey = VaultCrypto.deriveRecoveryKey(password, salt)
        val masterKey = VaultCrypto.newMasterKey()
        val wrapped = VaultCrypto.encrypt(recoveryKey, masterKey, "master-key".toByteArray())
        val restoredKey = VaultCrypto.deriveRecoveryKey(password, salt)
        assertContentEquals(masterKey, VaultCrypto.decrypt(restoredKey, wrapped, "master-key".toByteArray()))
        val wrongKey = VaultCrypto.deriveRecoveryKey("wrong".toCharArray(), salt)
        assertFailsWith<AEADBadTagException> { VaultCrypto.decrypt(wrongKey, wrapped, "master-key".toByteArray()) }
    }
}
