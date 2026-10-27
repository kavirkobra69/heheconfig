package com.v2ray.ang.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup format: [MAGIC][salt 16][iv 12][AES-GCM(zip{prefs.json, tdlib/...})].
 * The key is derived from the user's password with PBKDF2.
 */
object SessionBackup {
    private const val MAGIC = "TGV2R1"
    class Restored(val dir: File, val prefs: JSONObject)

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) throw Exception("truncated file")
            n += r
        }
    }

    fun export(ctx: Context, out: OutputStream, password: String, prefs: SharedPreferences) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        out.write(MAGIC.toByteArray())
        out.write(salt)
        out.write(iv)
        val zip = ZipOutputStream(CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, password, salt, iv)))
        val json = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is String -> json.put(k, v)
                is Boolean -> json.put(k, v)
                is Int -> json.put(k, v)
            }
        }
        zip.putNextEntry(ZipEntry("prefs.json"))
        zip.write(json.toString().toByteArray())
        zip.closeEntry()
        val base = File(ctx.filesDir, "tdlib")
        if (base.exists()) {
            base.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("tdlib/" + f.relativeTo(base).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zip.close()
    }

    fun extract(ctx: Context, inp: InputStream, password: String): Restored {
        val magic = ByteArray(MAGIC.length)
        readFully(inp, magic)
        if (String(magic) != MAGIC) throw Exception("not a backup file")
        val salt = ByteArray(16); readFully(inp, salt)
        val iv = ByteArray(12); readFully(inp, iv)
        val cin = CipherInputStream(inp, cipher(Cipher.DECRYPT_MODE, password, salt, iv))
        val zin = ZipInputStream(cin)
        val tmp = File(ctx.filesDir, "tdlib_restore")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var settings: JSONObject? = null
        try {
            var e = zin.nextEntry
            while (e != null) {
                if (e.name == "prefs.json") {
                    settings = JSONObject(String(zin.readBytes()))
                } else if (e.name.startsWith("tdlib/") && !e.isDirectory) {
                    val f = File(tmp, e.name.removePrefix("tdlib/"))
                    if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw Exception("bad entry")
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
            val buf = ByteArray(8192)
            while (cin.read(buf) != -1) { /* consume stream to validate GCM tag */ }
        } catch (ex: Exception) {
            tmp.deleteRecursively()
            throw ex
        }
        return Restored(tmp, settings ?: throw Exception("no settings in backup"))
    }

    fun commit(ctx: Context, r: Restored, prefs: SharedPreferences) {
        val live = File(ctx.filesDir, "tdlib")
        live.deleteRecursively()
        if (!r.dir.renameTo(live)) {
            r.dir.copyRecursively(live, true)
            r.dir.deleteRecursively()
        }
        val ed = prefs.edit().clear()
        for (k in r.prefs.keys()) {
            when (val v = r.prefs.get(k)) {
                is String -> ed.putString(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
            }
        }
        ed.apply()
    }
}
