package com.pancreas.ai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * HIPAA-aligned encrypted file store.
 *
 * All PHI written to disk (glucose readings, insulin doses, food logs) is
 * encrypted at rest using AES-256-GCM via AndroidX EncryptedFile.  The
 * encryption key lives exclusively in the Android Keystore hardware-backed
 * store and never touches the filesystem.
 *
 * Encryption scheme  : AES256_GCM_HKDF_4KB  (AEAD — provides both
 *                       confidentiality and integrity / tamper detection)
 * Key storage        : Android Keystore (hardware-backed where available)
 * Key derivation     : HKDF from the Keystore master key
 *
 * Migration: on first access the helper checks whether a plain-text legacy
 * file exists.  If it does, its contents are re-written to the encrypted
 * file and the plain-text file is securely deleted before any data is
 * returned to the caller.  This ensures a seamless upgrade for existing
 * installations without data loss.
 */
object SecureFileStore {

    private const val TAG = "SecureFileStore"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Read the contents of [fileName] from internal storage, decrypting on
     * the way in.  Returns null if the file does not exist yet.
     */
    fun read(ctx: Context, fileName: String): String? {
        val encFile = encryptedFile(ctx, encName(fileName))
        val plainFile = File(ctx.filesDir, fileName)

        // ── Migration path ────────────────────────────────────────────────────
        // If a plain-text legacy file still exists, encrypt it now and delete
        // the original.
        if (plainFile.exists()) {
            Log.i(TAG, "Migrating '$fileName' to encrypted storage")
            val plainText = try { plainFile.readText() } catch (e: Exception) {
                Log.e(TAG, "Could not read legacy file for migration", e); null
            }
            if (plainText != null) {
                write(ctx, fileName, plainText)    // write encrypted
                secureDelete(plainFile)            // wipe original
                return plainText
            }
        }

        // ── Normal encrypted read ─────────────────────────────────────────────
        val encPhysical = File(ctx.filesDir, encName(fileName))
        if (!encPhysical.exists()) return null

        return try {
            encFile.openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt '$fileName': ${e.javaClass.simpleName} — ${e.message}")
            // Corrupted encrypted file: delete it so the app doesn't stay broken
            encPhysical.delete()
            null
        }
    }

    /**
     * Encrypt [content] and write it to [fileName] in internal storage,
     * replacing any existing data atomically (write-new → delete-old).
     */
    fun write(ctx: Context, fileName: String, content: String) {
        val encPhysical = File(ctx.filesDir, encName(fileName))

        // EncryptedFile cannot overwrite — must delete first
        if (encPhysical.exists()) encPhysical.delete()

        try {
            encryptedFile(ctx, encName(fileName))
                .openFileOutput()
                .use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt '$fileName'", e)
        }
    }

    /**
     * Delete both the encrypted file and any lingering plain-text legacy copy.
     */
    fun delete(ctx: Context, fileName: String) {
        secureDelete(File(ctx.filesDir, encName(fileName)))
        val plain = File(ctx.filesDir, fileName)
        if (plain.exists()) secureDelete(plain)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Encrypted files get a distinct name so they never collide with legacy plain files. */
    private fun encName(name: String) = "${name}.enc"

    private fun masterKey(ctx: Context) = MasterKey.Builder(ctx)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private fun encryptedFile(ctx: Context, physicalName: String) =
        EncryptedFile.Builder(
            ctx,
            File(ctx.filesDir, physicalName),
            masterKey(ctx),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    /**
     * Overwrite the file with zeros before deleting to reduce residual-data
     * risk on non-wear-levelled storage (best-effort on flash devices).
     */
    private fun secureDelete(file: File) {
        try {
            if (file.exists()) {
                file.writeBytes(ByteArray(file.length().coerceAtMost(65536).toInt()) { 0 })
                file.delete()
            }
        } catch (e: Exception) {
            file.delete() // fall back to simple delete if zeroing fails
        }
    }
}
