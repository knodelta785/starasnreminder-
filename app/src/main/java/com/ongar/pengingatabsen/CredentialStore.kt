package com.ongar.pengingatabsen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan akun STAR ASN milik pengguna sendiri.
 * Password dienkripsi AES-256-GCM dengan kunci di Android Keystore (kunci tidak bisa
 * diekspor dari HP). Tidak ada akun yang tertanam di kode aplikasi.
 */
object CredentialStore {
    data class Account(
        val id: String,
        val label: String,
        val username: String,
        val encPassword: String
    )

    private const val PREF = "star_asn_accounts"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    private const val KEY_QUICK = "quick_notif"
    private const val KEY_LOCK = "require_unlock"
    private const val KEY_SUBMIT = "auto_submit"
    private const val ALIAS = "pengingat_absen_star_asn"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- Kripto ----------
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Mengembalikan null jika gagal dibaca (misalnya kunci hilang setelah restore). */
    fun decryptPassword(a: Account): String? = try {
        val parts = a.encPassword.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    // ---------- Daftar akun ----------
    fun list(ctx: Context): List<Account> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Account(
                    o.getString("id"), o.optString("label"),
                    o.getString("user"), o.getString("pass")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("label", it.label)
                    .put("user", it.username).put("pass", it.encPassword)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** id == null: akun baru. password kosong saat edit: password lama dipertahankan. */
    fun save(ctx: Context, id: String?, label: String, username: String, password: String?) {
        val list = list(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val enc = when {
            !password.isNullOrEmpty() -> encrypt(password)
            idx >= 0 -> list[idx].encPassword
            else -> return
        }
        if (idx >= 0) {
            list[idx] = Account(list[idx].id, label, username, enc)
        } else {
            val acc = Account(UUID.randomUUID().toString(), label, username, enc)
            list.add(acc)
            if (list.size == 1) setActive(ctx, acc.id)
        }
        persist(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = list(ctx).filter { it.id != id }
        persist(ctx, list)
        if (prefs(ctx).getString(KEY_ACTIVE, null) == id) {
            prefs(ctx).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun activeId(ctx: Context): String? = active(ctx)?.id

    fun active(ctx: Context): Account? {
        val l = list(ctx)
        val id = prefs(ctx).getString(KEY_ACTIVE, null)
        return l.firstOrNull { it.id == id } ?: l.firstOrNull()
    }

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    // ---------- Pengaturan ----------
    fun quickNotif(ctx: Context) = prefs(ctx).getBoolean(KEY_QUICK, true)
    fun setQuickNotif(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_QUICK, on).apply()

    fun requireUnlock(ctx: Context) = prefs(ctx).getBoolean(KEY_LOCK, false)
    fun setRequireUnlock(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOCK, on).apply()

    fun autoSubmit(ctx: Context) = prefs(ctx).getBoolean(KEY_SUBMIT, true)
    fun setAutoSubmit(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SUBMIT, on).apply()
}
