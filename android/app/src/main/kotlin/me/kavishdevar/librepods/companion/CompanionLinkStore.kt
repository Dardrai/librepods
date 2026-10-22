package me.kavishdevar.librepods.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CompanionLink(val peer: String, val device: String, val key: ByteArray)

/** The peer allowlist and its secret are encrypted together and excluded from Android backup. */
internal class CompanionLinkStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "companion-link"))
    private val alias = "librepods.companion.link.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun load(): CompanionLink? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..1024) { "Invalid saved companion link" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return CompanionLink(CompanionProtocol.address(json.getString("peer")),
            CompanionProtocol.address(json.getString("device")), CompanionProtocol.hexBytes(json.getString("key"), 32))
    }

    fun save(link: CompanionLink) {
        require(link.key.size == 32)
        val json = JSONObject().put("peer", CompanionProtocol.address(link.peer))
            .put("device", CompanionProtocol.address(link.device)).put("key", CompanionProtocol.hex(link.key))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = file.delete()
}
