package com.righelm.android.support

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A RigHelm station the app has connected to (or been told about). */
data class SavedServer(val name: String, val host: String, val port: Int) {
    val id: String get() = "$host:$port"
}

object SavedServers {
    private const val KEY = "savedServers"

    fun load(prefs: SharedPreferences): List<SavedServer> = try {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            SavedServer(o.getString("name"), o.getString("host"), o.getInt("port"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(prefs: SharedPreferences, servers: List<SavedServer>) {
        val a = JSONArray()
        for (s in servers) a.put(JSONObject().put("name", s.name).put("host", s.host).put("port", s.port))
        prefs.edit().putString(KEY, a.toString()).apply()
    }
}

/**
 * Station passwords by "host:port", encrypted with a key that lives in the
 * Android Keystore (it never leaves the device's secure hardware).
 */
class Passwords(private val prefs: SharedPreferences) {
    private val alias = "righelm-station-passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun get(account: String): String? {
        val stored = prefs.getString("pw:$account", null) ?: return null
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun set(account: String, password: String) {
        if (password.isEmpty()) {
            prefs.edit().remove("pw:$account").apply()
            return
        }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(password.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("pw:$account", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            // Not remembered; the operator types it again next time.
        }
    }
}
