package com.hisabbondhu.app.auth

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional local PIN lock, independent of Google sign-in. Only a salted PBKDF2 hash of the PIN is stored.
 * Forgot PIN: the owner signs out and signs in with Google again, which clears the lock (see Graph.logout).
 */
object AppLock {
    private fun p(c: Context) = c.getSharedPreferences("applock", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getString("hash", null) != null

    private fun hash(pin: String, salt: ByteArray): String {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256))
        return Base64.encodeToString(k.encoded, Base64.NO_WRAP)
    }

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p(c).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("hash", hash(pin, salt))
            .putInt("fails", 0).putLong("until", 0).apply()
    }

    /** Seconds the user must still wait after too many wrong attempts (0 = may try). */
    fun waitSeconds(c: Context): Long = ((p(c).getLong("until", 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun verify(c: Context, pin: String): Boolean {
        if (waitSeconds(c) > 0) return false
        val sp = p(c)
        val salt = Base64.decode(sp.getString("salt", "") ?: return false, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pin, salt).toByteArray(), (sp.getString("hash", "") ?: "").toByteArray())
        if (ok) sp.edit().putInt("fails", 0).apply()
        else {
            val f = sp.getInt("fails", 0) + 1
            sp.edit().putInt("fails", f).putLong("until", if (f >= 5) System.currentTimeMillis() + 30_000L * (f - 4) else 0).apply()
        }
        return ok
    }

    fun disable(c: Context) = clear(c)
    fun clear(c: Context) { p(c).edit().clear().apply() }
}
