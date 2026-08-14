package dev.agentknock.storage.crypto

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

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
    fun contains(keyId: String): Boolean

    fun generate(keyId: String): GeneratedEncryptionKey
}

internal class AndroidEncryptionKeyStore(
    private val packageManager: PackageManager,
) : EncryptionKeyStore {
    private val keyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    @Synchronized
    override fun contains(keyId: String): Boolean = keyStore.containsAlias(alias(keyId))

    @Synchronized
    override fun get(keyId: String): SecretKey? = keyStore.getKey(alias(keyId), null) as? SecretKey

    @Synchronized
    override fun generate(keyId: String): GeneratedEncryptionKey {
        val alias = alias(keyId)
        check(!keyStore.containsAlias(alias)) { "Encryption key already exists: $keyId" }

        val strongBoxRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val (key, strongBoxUsed) = if (strongBoxRequested) {
            try {
                generate(alias, useStrongBox = true) to true
            } catch (_: StrongBoxUnavailableException) {
                generate(alias, useStrongBox = false) to false
            }
        } else {
            generate(alias, useStrongBox = false) to false
        }

        return GeneratedEncryptionKey(backing = determineBacking(key, strongBoxUsed))
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
    }
}
