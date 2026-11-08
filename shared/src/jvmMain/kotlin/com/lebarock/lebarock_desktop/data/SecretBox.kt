package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.AppInfo
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps a secret (the print agent's branch key) encrypted on disk, the desktop counterpart of the
 * handheld's Android Keystore: on Windows with DPAPI, so only this Windows account on this PC can
 * read it back; elsewhere (development) with AES-GCM and a key file of the data folder.
 * The sealed text says which: "dpapi:…" or "aes:…".
 */
class SecretBox(private val dir: File = AppInfo.dataDir, private val windows: Boolean = AppInfo.isWindows) {

    fun seal(secret: String): String {
        val plain = secret.toByteArray(Charsets.UTF_8)
        return if (windows) {
            "dpapi:" + b64(Crypt32Util.cryptProtectData(plain))
        } else {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, localKey(), GCMParameterSpec(128, iv)) }
            "aes:" + b64(iv) + ":" + b64(cipher.doFinal(plain))
        }
    }

    /** The secret, or null when it cannot be read back (another PC or Windows account, damaged). */
    fun open(sealed: String?): String? {
        if (sealed.isNullOrBlank()) return null
        return runCatching {
            when {
                sealed.startsWith("dpapi:") -> String(Crypt32Util.cryptUnprotectData(unb64(sealed.removePrefix("dpapi:"))), Charsets.UTF_8)
                sealed.startsWith("aes:") -> {
                    val (iv, data) = sealed.removePrefix("aes:").split(':', limit = 2)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, unb64(iv))) }
                    String(cipher.doFinal(unb64(data)), Charsets.UTF_8)
                }
                else -> null
            }
        }.onFailure { AppLog.warn("Secret illisible sur ce poste", it) }.getOrNull()
    }

    private fun localKey(): SecretKeySpec {
        val file = File(dir, ".secret.key")
        if (!file.exists()) {
            file.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            file.setReadable(false, false)
            file.setReadable(true, true)
        }
        return SecretKeySpec(file.readBytes(), "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
