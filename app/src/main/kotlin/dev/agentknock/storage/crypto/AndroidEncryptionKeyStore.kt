package dev.agentknock.storage.crypto

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

internal enum class EncryptionKeyBacking {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE,
    UNKNOWN_SECURE,
    UNKNOWN,
}

internal data class GeneratedEncryptionKey(
    val backing: EncryptionKeyBacking,
)

internal interface EncryptionKeyStore : EncryptionKeySource {
    fun generate(keyId: String): GeneratedEncryptionKey

    fun importKey(keyId: String, keyMaterial: ByteArray): GeneratedEncryptionKey

    fun delete(keyId: String)

    fun managedKeyIds(): List<String>
}

internal class AndroidEncryptionKeyStore(
    private val packageManager: PackageManager,
) : EncryptionKeyStore {
    private val keyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    @Synchronized
    override fun get(keyId: String): SecretKey? = try {
        keyStore.getKey(alias(keyId), null) as? SecretKey
    } catch (_: UnrecoverableKeyException) {
        null
    } catch (_: KeyPermanentlyInvalidatedException) {
        null
    }

    @Synchronized
    override fun delete(keyId: String) {
        keyStore.deleteEntry(alias(keyId))
    }

    @Synchronized
    override fun managedKeyIds(): List<String> = keyStore.aliases().asSequence().toList()
        .filter { it.startsWith(ALIAS_PREFIX) }
        .map { it.removePrefix(ALIAS_PREFIX) }

    @Synchronized
    override fun generate(keyId: String): GeneratedEncryptionKey {
        val alias = alias(keyId)
        check(!keyStore.containsAlias(alias)) { "Encryption key already exists: $keyId" }

        return try {
            val strongBoxRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val (key, strongBoxUsed) = if (strongBoxRequested) {
                try {
                    generate(alias, useStrongBox = true) to true
                } catch (_: StrongBoxUnavailableException) {
                    keyStore.deleteEntry(alias)
                    generate(alias, useStrongBox = false) to false
                }
            } else {
                generate(alias, useStrongBox = false) to false
            }

            val backing = runCatching { determineBacking(key, strongBoxUsed) }
                .getOrDefault(EncryptionKeyBacking.UNKNOWN)
            GeneratedEncryptionKey(backing = backing)
        } catch (failure: Throwable) {
            runCatching { keyStore.deleteEntry(alias) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    override fun importKey(keyId: String, keyMaterial: ByteArray): GeneratedEncryptionKey {
        require(keyMaterial.size == KEY_SIZE_BYTES) { "Imported vault keys must be AES-128 keys" }
        val alias = alias(keyId)
        check(!keyStore.containsAlias(alias)) { "Encryption key already exists: $keyId" }

        return try {
            val strongBoxRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val strongBoxUsed = if (strongBoxRequested) {
                try {
                    importMaterial(alias, keyMaterial, useStrongBox = true)
                    true
                } catch (_: StrongBoxUnavailableException) {
                    keyStore.deleteEntry(alias)
                    importMaterial(alias, keyMaterial, useStrongBox = false)
                    false
                }
            } else {
                importMaterial(alias, keyMaterial, useStrongBox = false)
                false
            }

            val key = checkNotNull(get(keyId)) { "Imported encryption key is unavailable: $keyId" }
            val backing = runCatching { determineBacking(key, strongBoxUsed) }
                .getOrDefault(EncryptionKeyBacking.UNKNOWN)
            GeneratedEncryptionKey(backing = backing)
        } catch (failure: Throwable) {
            runCatching { keyStore.deleteEntry(alias) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun generate(alias: String, useStrongBox: Boolean): SecretKey {
        val parameters = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(parameters)
            generateKey()
        }
    }

    private fun importMaterial(alias: String, keyMaterial: ByteArray, useStrongBox: Boolean) {
        val material = keyMaterial.copyOf()
        try {
            val protection = KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useStrongBox) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()
            keyStore.setEntry(
                alias,
                KeyStore.SecretKeyEntry(SecretKeySpec(material, KeyProperties.KEY_ALGORITHM_AES)),
                protection,
            )
        } finally {
            material.fill(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun determineBacking(key: SecretKey, strongBoxUsed: Boolean): EncryptionKeyBacking {
        if (strongBoxUsed) return EncryptionKeyBacking.STRONGBOX

        val keyInfo = SecretKeyFactory
            .getInstance(key.algorithm, ANDROID_KEY_STORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return if (keyInfo.isInsideSecureHardware()) {
                EncryptionKeyBacking.TRUSTED_ENVIRONMENT
            } else {
                EncryptionKeyBacking.SOFTWARE
            }
        }
        return when (keyInfo.getSecurityLevel()) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> EncryptionKeyBacking.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                EncryptionKeyBacking.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> EncryptionKeyBacking.SOFTWARE
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> EncryptionKeyBacking.UNKNOWN_SECURE
            else -> EncryptionKeyBacking.UNKNOWN
        }
    }

    private fun alias(keyId: String) = "$ALIAS_PREFIX$keyId"

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "dev.agentknock.local-encryption."
        private const val KEY_SIZE_BITS = 128
        private const val KEY_SIZE_BYTES = KEY_SIZE_BITS / 8
    }
}
