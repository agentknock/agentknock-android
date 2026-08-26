package dev.agentknock.storage.secret

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal enum class SshKeyAlgorithm(
    val storedName: String,
    val publicName: String,
) {
    ED25519("ed25519", "ssh-ed25519"),
    ;

    companion object {
        fun fromStoredName(name: String): SshKeyAlgorithm? = entries.find { it.storedName == name }

        fun fromPublicName(name: String): SshKeyAlgorithm? = entries.find { it.publicName == name }
    }
}

internal data class SshPrivateKey(
    val algorithm: SshKeyAlgorithm,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val comment: String,
) {
    val publicKeyLine: String
        get() = SshPublicKey(algorithm, publicKey, comment).line

    val fingerprint: String
        get() = SshPublicKey(algorithm, publicKey, comment).fingerprint
}

internal data class SshPublicKey(
    val algorithm: SshKeyAlgorithm,
    val publicKey: ByteArray,
    val comment: String,
) {
    val line: String
        get() = buildString {
            append(algorithm.publicName)
            append(' ')
            append(Base64.getEncoder().encodeToString(blob()))
            if (comment.isNotEmpty()) {
                append(' ')
                append(comment)
            }
        }

    val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob()),
        )

    fun blob(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            val algorithmBytes = algorithm.publicName.encodeToByteArray()
            output.writeInt(algorithmBytes.size)
            output.write(algorithmBytes)
            output.writeInt(publicKey.size)
            output.write(publicKey)
        }
        bytes.toByteArray()
    }
}

internal class SshKeyCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generateEd25519(comment: String): SshPrivateKey {
        validateComment(comment)
        val privateKey = Ed25519PrivateKeyParameters(secureRandom)
        return SshPrivateKey(
            algorithm = SshKeyAlgorithm.ED25519,
            privateKey = privateKey.encoded,
            publicKey = privateKey.generatePublicKey().encoded,
            comment = comment,
        )
    }

    fun importOpenSshPrivateKey(value: String): SshPrivateKey {
        val encoded = decodePem(value)
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(OPENSSH_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(OPENSSH_MAGIC)) { "Not an OpenSSH private key" }
            require(input.readSshString() == NONE) { "Encrypted SSH private keys are not supported" }
            require(input.readSshString() == NONE) { "Encrypted SSH private keys are not supported" }
            require(input.readSshBytes().isEmpty()) { "Invalid OpenSSH key derivation options" }
            require(input.readUnsignedInt() == 1L) { "The SSH private key must contain one key" }
            val outerPublicBlob = input.readSshBytes()
            val privateBlock = input.readSshBytes()
            require(input.available() == 0) { "Unexpected data after the OpenSSH private key" }
            parseEd25519PrivateBlock(privateBlock, outerPublicBlob)
        }
    }

    fun fromStored(
        algorithm: String,
        privateKey: ByteArray,
        publicKey: ByteArray,
        comment: String,
    ): SshPrivateKey {
        val parsedAlgorithm = requireNotNull(SshKeyAlgorithm.fromStoredName(algorithm)) {
            "Unsupported SSH key algorithm"
        }
        require(parsedAlgorithm == SshKeyAlgorithm.ED25519) { "Unsupported SSH key algorithm" }
        require(privateKey.size == Ed25519PrivateKeyParameters.KEY_SIZE) {
            "Invalid Ed25519 private key"
        }
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Invalid Ed25519 public key" }
        validateComment(comment)
        val derived = Ed25519PrivateKeyParameters(privateKey).generatePublicKey().encoded
        require(MessageDigest.isEqual(derived, publicKey)) {
            "The SSH public key does not match its private key"
        }
        return SshPrivateKey(parsedAlgorithm, privateKey.copyOf(), publicKey.copyOf(), comment)
    }

    fun publicKey(
        algorithm: String,
        publicKey: ByteArray,
        comment: String,
    ): SshPublicKey {
        val parsedAlgorithm = requireNotNull(SshKeyAlgorithm.fromStoredName(algorithm)) {
            "Unsupported SSH key algorithm"
        }
        require(parsedAlgorithm == SshKeyAlgorithm.ED25519) { "Unsupported SSH key algorithm" }
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Invalid Ed25519 public key" }
        validateComment(comment)
        return SshPublicKey(parsedAlgorithm, publicKey.copyOf(), comment)
    }

    fun importOpenSshPublicKey(value: String): SshPublicKey {
        val fields = value.trim().split(Regex("\\s+"), limit = 3)
        require(fields.size >= 2) { "Invalid OpenSSH public key" }
        val algorithm = requireNotNull(SshKeyAlgorithm.fromPublicName(fields[0])) {
            "Unsupported SSH public key algorithm"
        }
        val blob = runCatching { Base64.getDecoder().decode(fields[1]) }
            .getOrElse { throw IllegalArgumentException("Invalid OpenSSH public key", it) }
        val parsed = DataInputStream(ByteArrayInputStream(blob)).use { input ->
            require(input.readSshString() == algorithm.publicName) {
                "OpenSSH public key algorithm does not match its blob"
            }
            val publicKey = input.readSshBytes()
            require(input.available() == 0) { "Unexpected data after the OpenSSH public key" }
            publicKey(algorithm.storedName, publicKey, fields.getOrElse(2) { "" })
        }
        require(MessageDigest.isEqual(parsed.blob(), blob)) { "Invalid OpenSSH public key" }
        return parsed
    }

    fun signGitSignature(
        privateKey: SshPrivateKey,
        message: ByteArray,
    ): String {
        val validated = fromStored(
            privateKey.algorithm.storedName,
            privateKey.privateKey,
            privateKey.publicKey,
            privateKey.comment,
        )
        val hashAlgorithm = SSHSIG_HASH_ALGORITHM
        val messageHash = MessageDigest.getInstance("SHA-512").digest(message)
        val signedData = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SSHSIG_MAGIC)
                output.writeSshString(GIT_SSHSIG_NAMESPACE.encodeToByteArray())
                output.writeSshString(byteArrayOf())
                output.writeSshString(hashAlgorithm.encodeToByteArray())
                output.writeSshString(messageHash)
            }
            bytes.toByteArray()
        }
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(validated.privateKey))
            update(signedData, 0, signedData.size)
        }
        val signature = signer.generateSignature()
        val signatureBlob = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSshString(validated.algorithm.publicName.encodeToByteArray())
                output.writeSshString(signature)
            }
            bytes.toByteArray()
        }
        val gitSignature = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SSHSIG_MAGIC)
                output.writeInt(SSHSIG_VERSION)
                output.writeSshString(
                    SshPublicKey(validated.algorithm, validated.publicKey, "").blob(),
                )
                output.writeSshString(GIT_SSHSIG_NAMESPACE.encodeToByteArray())
                output.writeSshString(byteArrayOf())
                output.writeSshString(hashAlgorithm.encodeToByteArray())
                output.writeSshString(signatureBlob)
            }
            bytes.toByteArray()
        }
        val armored = Base64.getMimeEncoder(76, "\n".encodeToByteArray())
            .encodeToString(gitSignature)
        return "$SSHSIG_PEM_BEGIN\n$armored\n$SSHSIG_PEM_END\n"
    }

    private fun parseEd25519PrivateBlock(
        privateBlock: ByteArray,
        outerPublicBlob: ByteArray,
    ): SshPrivateKey = DataInputStream(ByteArrayInputStream(privateBlock)).use { input ->
        val firstCheck = input.readInt()
        require(input.readInt() == firstCheck) { "Invalid OpenSSH private key checks" }
        val algorithm = requireNotNull(SshKeyAlgorithm.fromPublicName(input.readSshString())) {
            "Only Ed25519 SSH private keys are supported"
        }
        require(algorithm == SshKeyAlgorithm.ED25519) {
            "Only Ed25519 SSH private keys are supported"
        }
        val publicKey = input.readSshBytes()
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Invalid Ed25519 public key" }
        val privateAndPublic = input.readSshBytes()
        require(privateAndPublic.size == ED25519_PRIVATE_AND_PUBLIC_BYTES) {
            "Invalid Ed25519 private key"
        }
        require(
            MessageDigest.isEqual(
                privateAndPublic.copyOfRange(ED25519_PRIVATE_KEY_BYTES, privateAndPublic.size),
                publicKey,
            ),
        ) { "The OpenSSH private and public keys do not match" }
        val comment = input.readSshString()
        validateComment(comment)
        var padding = 1
        while (input.available() > 0) {
            require(input.readUnsignedByte() == padding and 0xff) {
                "Invalid OpenSSH private key padding"
            }
            padding++
        }
        val expectedOuterPublic = SshPublicKey(algorithm, publicKey, "").blob()
        require(MessageDigest.isEqual(expectedOuterPublic, outerPublicBlob)) {
            "The OpenSSH public and private sections do not match"
        }
        val privateKey = privateAndPublic.copyOfRange(0, ED25519_PRIVATE_KEY_BYTES)
        fromStored(algorithm.storedName, privateKey, publicKey, comment)
    }

    private fun decodePem(value: String): ByteArray {
        val normalized = value.trim()
        val lines = normalized.lines()
        require(lines.firstOrNull() == PEM_BEGIN && lines.lastOrNull() == PEM_END) {
            "Paste an unencrypted OpenSSH private key"
        }
        val body = lines.drop(1).dropLast(1).joinToString(separator = "") { it.trim() }
        require(body.isNotEmpty()) { "The OpenSSH private key is empty" }
        return try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The OpenSSH private key is not valid Base64")
        }
    }

    private fun validateComment(comment: String) {
        require('\u0000' !in comment && '\n' !in comment && '\r' !in comment) {
            "An SSH public-key comment must fit on one line"
        }
    }

    private fun DataInputStream.readSshBytes(): ByteArray {
        val length = readUnsignedInt()
        require(length <= available().toLong()) { "Truncated OpenSSH private key" }
        return ByteArray(length.toInt()).also(::readFully)
    }

    private fun DataInputStream.readSshString(): String =
        readSshBytes().decodeToString(throwOnInvalidSequence = true)

    private fun DataInputStream.readUnsignedInt(): Long = readInt().toLong() and 0xffff_ffffL

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private companion object {
        val OPENSSH_MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
        const val NONE = "none"
        const val PEM_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
        const val PEM_END = "-----END OPENSSH PRIVATE KEY-----"
        const val ED25519_PRIVATE_KEY_BYTES = 32
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val ED25519_PRIVATE_AND_PUBLIC_BYTES = 64
        val SSHSIG_MAGIC = "SSHSIG".encodeToByteArray()
        const val SSHSIG_VERSION = 1
        const val SSHSIG_HASH_ALGORITHM = "sha512"
        const val GIT_SSHSIG_NAMESPACE = "git"
        const val SSHSIG_PEM_BEGIN = "-----BEGIN SSH SIGNATURE-----"
        const val SSHSIG_PEM_END = "-----END SSH SIGNATURE-----"
    }
}
