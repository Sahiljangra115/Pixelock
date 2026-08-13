package dev.pixelock.app.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The password that ends a timer early. Only a salted hash is kept. Slow, so run it off the main thread. */
object EndPassword {

    const val MIN_LENGTH = 6

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(): String =
        Base64.getEncoder().encodeToString(ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes))

    fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.getEncoder().encodeToString(key)
    }

    fun matches(password: String, salt: String, hash: String): Boolean =
        MessageDigest.isEqual(hash(password, salt).toByteArray(), hash.toByteArray())
}
