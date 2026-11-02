package ru.documentvault.core

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Client-side primitives. Envelopes contain version, nonce and authenticated ciphertext. */
object VaultCrypto {
    private const val VERSION: Byte = 1
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    const val KDF_ITERATIONS = 600_000
    private val random = SecureRandom()

    fun newMasterKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun deriveRecoveryKey(password: CharArray, salt: ByteArray): ByteArray {
        require(password.isNotEmpty()) { "Recovery password must not be empty" }
        require(salt.size == SALT_BYTES) { "Invalid salt length" }
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, context: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Invalid key length" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context)
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext)
    }

    @Throws(AEADBadTagException::class)
    fun decrypt(key: ByteArray, envelope: ByteArray, context: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Invalid key length" }
        require(envelope.size >= 1 + NONCE_BYTES + TAG_BITS / 8 && envelope[0] == VERSION) {
            "Invalid encrypted envelope"
        }
        val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context)
        return cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.size - 1 - NONCE_BYTES)
    }
}
