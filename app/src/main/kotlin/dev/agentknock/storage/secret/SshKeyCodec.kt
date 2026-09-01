package dev.agentknock.storage.secret

import dev.agentknock.protocol.SshSignatureAlgorithm
import dev.agentknock.protocol.sshSignatureBlob
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal enum class SshKeyAlgorithm(
    val storedName: String,
    val publicName: String,
) {
    ED25519("ed25519", "ssh-ed25519"),
    RSA("rsa", "ssh-rsa"),
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
            when (algorithm) {
                SshKeyAlgorithm.ED25519 -> {
                    output.writeInt(publicKey.size)
                    output.write(publicKey)
                }
                SshKeyAlgorithm.RSA -> output.write(publicKey)
            }
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

    fun generateRsa(comment: String): SshPrivateKey {
        validateComment(comment)
        val pair = KeyPairGenerator.getInstance(RSA).apply {
            initialize(GENERATED_RSA_BITS, secureRandom)
        }.generateKeyPair()
        val privateKey = pair.private as? RSAPrivateCrtKey
            ?: throw IllegalStateException("RSA key generation returned an unsupported key")
        val publicKey = encodeRsaPublicKey(privateKey.publicExponent, privateKey.modulus)
        return fromStored(
            algorithm = SshKeyAlgorithm.RSA.storedName,
            privateKey = privateKey.encoded,
            publicKey = publicKey,
            comment = comment,
        )
    }

    fun importOpenSshPrivateKey(value: String): SshPrivateKey {
        val encoded = decodePem(value)
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(OPENSSH_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(OPENSSH_MAGIC)) { "Not an OpenSSH private key" }
            require(input.readSshString(MAX_ALGORITHM_BYTES, "cipher name") == NONE) {
                "Encrypted SSH private keys are not supported"
            }
            require(input.readSshString(MAX_ALGORITHM_BYTES, "key derivation name") == NONE) {
                "Encrypted SSH private keys are not supported"
            }
            require(input.readSshBytes(MAX_KDF_OPTIONS_BYTES, "key derivation options").isEmpty()) {
                "Invalid OpenSSH key derivation options"
            }
            require(input.readUnsignedInt() == 1L) { "The SSH private key must contain one key" }
            val outerPublicBlob = input.readSshBytes(MAX_PUBLIC_BLOB_BYTES, "public key")
            val privateBlock = input.readSshBytes(MAX_PRIVATE_BLOCK_BYTES, "private key")
            require(input.available() == 0) { "Unexpected data after the OpenSSH private key" }
            parsePrivateBlock(privateBlock, outerPublicBlob)
        }
    }

    fun fromStored(
        algorithm: String,
        privateKey: ByteArray,
        publicKey: ByteArray,
        comment: String,
    ): SshPrivateKey = fromStored(
        algorithm = requireNotNull(SshKeyAlgorithm.fromStoredName(algorithm)) {
            "Unsupported SSH key algorithm"
        },
        privateKey = privateKey,
        publicKey = publicKey,
        comment = comment,
    )

    fun fromStored(
        algorithm: SshKeyAlgorithm,
        privateKey: ByteArray,
        publicKey: ByteArray,
        comment: String,
    ): SshPrivateKey {
        validateComment(comment)
        when (algorithm) {
            SshKeyAlgorithm.ED25519 -> {
                require(privateKey.size == Ed25519PrivateKeyParameters.KEY_SIZE) {
                    "Invalid Ed25519 private key"
                }
                require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) {
                    "Invalid Ed25519 public key"
                }
                val derived = Ed25519PrivateKeyParameters(privateKey).generatePublicKey().encoded
                require(MessageDigest.isEqual(derived, publicKey)) {
                    "The SSH public key does not match its private key"
                }
            }
            SshKeyAlgorithm.RSA -> {
                require(privateKey.size <= MAX_PKCS8_BYTES) { "RSA private key is too large" }
                val private = rsaPrivateKey(privateKey)
                validateRsaPrivateKey(private)
                val public = parseRsaPublicKey(publicKey)
                require(
                    private.publicExponent == public.exponent &&
                        private.modulus == public.modulus
                ) { "The SSH public key does not match its private key" }
            }
        }
        return SshPrivateKey(algorithm, privateKey.copyOf(), publicKey.copyOf(), comment)
    }

    fun publicKey(
        algorithm: String,
        publicKey: ByteArray,
        comment: String,
    ): SshPublicKey = publicKey(
        algorithm = requireNotNull(SshKeyAlgorithm.fromStoredName(algorithm)) {
            "Unsupported SSH key algorithm"
        },
        publicKey = publicKey,
        comment = comment,
    )

    fun publicKey(
        algorithm: SshKeyAlgorithm,
        publicKey: ByteArray,
        comment: String,
    ): SshPublicKey {
        when (algorithm) {
            SshKeyAlgorithm.ED25519 -> require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) {
                "Invalid Ed25519 public key"
            }
            SshKeyAlgorithm.RSA -> parseRsaPublicKey(publicKey)
        }
        validateComment(comment)
        return SshPublicKey(algorithm, publicKey.copyOf(), comment)
    }

    fun importOpenSshPublicKey(value: String): SshPublicKey {
        require(value.length <= MAX_PUBLIC_KEY_LINE_CHARS) { "OpenSSH public key is too large" }
        val fields = value.trim().split(Regex("\\s+"), limit = 3)
        require(fields.size >= 2) { "Invalid OpenSSH public key" }
        val algorithm = requireNotNull(SshKeyAlgorithm.fromPublicName(fields[0])) {
            "Unsupported SSH public key algorithm"
        }
        require(fields[1].length <= MAX_PUBLIC_BLOB_BASE64_CHARS) {
            "OpenSSH public key is too large"
        }
        val blob = runCatching { Base64.getDecoder().decode(fields[1]) }
            .getOrElse { throw IllegalArgumentException("Invalid OpenSSH public key", it) }
        require(blob.size <= MAX_PUBLIC_BLOB_BYTES) { "OpenSSH public key is too large" }
        val parsed = DataInputStream(ByteArrayInputStream(blob)).use { input ->
            require(input.readSshString(MAX_ALGORITHM_BYTES, "public key algorithm") == algorithm.publicName) {
                "OpenSSH public key algorithm does not match its blob"
            }
            val publicKey = when (algorithm) {
                SshKeyAlgorithm.ED25519 -> input.readSshBytes(
                    ED25519_PUBLIC_KEY_BYTES,
                    "Ed25519 public key",
                )
                SshKeyAlgorithm.RSA -> input.readBytes()
            }
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
        val signatureAlgorithm: String
        val signature = when (validated.algorithm) {
            SshKeyAlgorithm.ED25519 -> {
                signatureAlgorithm = validated.algorithm.publicName
                Ed25519Signer().run {
                    init(true, Ed25519PrivateKeyParameters(validated.privateKey))
                    update(signedData, 0, signedData.size)
                    generateSignature()
                }
            }
            SshKeyAlgorithm.RSA -> {
                signatureAlgorithm = RSA_SHA512
                Signature.getInstance(SHA512_WITH_RSA).run {
                    initSign(rsaPrivateKey(validated.privateKey))
                    update(signedData)
                    sign()
                }
            }
        }
        val signatureBlob = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSshString(signatureAlgorithm.encodeToByteArray())
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

    fun signSshAuthentication(
        privateKey: SshPrivateKey,
        message: ByteArray,
        algorithm: SshSignatureAlgorithm,
    ): ByteArray {
        val validated = fromStored(
            privateKey.algorithm.storedName,
            privateKey.privateKey,
            privateKey.publicKey,
            privateKey.comment,
        )
        val signature = when (validated.algorithm) {
            SshKeyAlgorithm.ED25519 -> {
                require(algorithm == SshSignatureAlgorithm.ED25519) {
                    "SSH signature algorithm does not match the key"
                }
                Ed25519Signer().run {
                    init(true, Ed25519PrivateKeyParameters(validated.privateKey))
                    update(message, 0, message.size)
                    generateSignature()
                }
            }
            SshKeyAlgorithm.RSA -> {
                val signatureName = when (algorithm) {
                    SshSignatureAlgorithm.RSA_SHA256 -> SHA256_WITH_RSA
                    SshSignatureAlgorithm.RSA_SHA512 -> SHA512_WITH_RSA
                    SshSignatureAlgorithm.ED25519 -> throw IllegalArgumentException(
                        "SSH signature algorithm does not match the key",
                    )
                }
                Signature.getInstance(signatureName).run {
                    initSign(rsaPrivateKey(validated.privateKey))
                    update(message)
                    sign()
                }
            }
        }
        return sshSignatureBlob(algorithm, signature)
    }

    private fun parsePrivateBlock(
        privateBlock: ByteArray,
        outerPublicBlob: ByteArray,
    ): SshPrivateKey = DataInputStream(ByteArrayInputStream(privateBlock)).use { input ->
        require(privateBlock.isNotEmpty() && privateBlock.size % NONE_BLOCK_SIZE == 0) {
            "Invalid OpenSSH private key block size"
        }
        val firstCheck = input.readInt()
        require(input.readInt() == firstCheck) { "Invalid OpenSSH private key checks" }
        val algorithm = requireNotNull(
            SshKeyAlgorithm.fromPublicName(
                input.readSshString(MAX_ALGORITHM_BYTES, "private key algorithm"),
            ),
        ) {
            "Unsupported SSH private key algorithm"
        }
        when (algorithm) {
            SshKeyAlgorithm.ED25519 -> parseEd25519PrivateFields(input, outerPublicBlob)
            SshKeyAlgorithm.RSA -> parseRsaPrivateFields(input, outerPublicBlob)
        }
    }

    private fun parseEd25519PrivateFields(
        input: DataInputStream,
        outerPublicBlob: ByteArray,
    ): SshPrivateKey {
        val publicKey = input.readSshBytes(ED25519_PUBLIC_KEY_BYTES, "Ed25519 public key")
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Invalid Ed25519 public key" }
        val privateAndPublic = input.readSshBytes(
            ED25519_PRIVATE_AND_PUBLIC_BYTES,
            "Ed25519 private key",
        )
        require(privateAndPublic.size == ED25519_PRIVATE_AND_PUBLIC_BYTES) {
            "Invalid Ed25519 private key"
        }
        require(
            MessageDigest.isEqual(
                privateAndPublic.copyOfRange(ED25519_PRIVATE_KEY_BYTES, privateAndPublic.size),
                publicKey,
            ),
        ) { "The OpenSSH private and public keys do not match" }
        val comment = input.readSshString(MAX_COMMENT_BYTES, "comment")
        validateComment(comment)
        input.requirePadding(NONE_BLOCK_SIZE)
        val algorithm = SshKeyAlgorithm.ED25519
        val expectedOuterPublic = SshPublicKey(algorithm, publicKey, "").blob()
        require(MessageDigest.isEqual(expectedOuterPublic, outerPublicBlob)) {
            "The OpenSSH public and private sections do not match"
        }
        val privateKey = privateAndPublic.copyOfRange(0, ED25519_PRIVATE_KEY_BYTES)
        return fromStored(algorithm.storedName, privateKey, publicKey, comment)
    }

    private fun parseRsaPrivateFields(
        input: DataInputStream,
        outerPublicBlob: ByteArray,
    ): SshPrivateKey {
        val modulus = input.readPositiveMpint("RSA modulus", MAX_RSA_MPINT_BYTES)
        val exponent = input.readPositiveMpint("RSA public exponent", MAX_RSA_EXPONENT_BYTES)
        val privateExponent = input.readPositiveMpint("RSA private exponent", MAX_RSA_MPINT_BYTES)
        val coefficient = input.readPositiveMpint("RSA coefficient", MAX_RSA_MPINT_BYTES)
        val primeP = input.readPositiveMpint("RSA prime p", MAX_RSA_MPINT_BYTES)
        val primeQ = input.readPositiveMpint("RSA prime q", MAX_RSA_MPINT_BYTES)
        validateRsaBits(modulus)
        require(exponent > BigInteger.ONE && exponent.testBit(0)) {
            "Invalid RSA public exponent"
        }
        require(primeQ.modInverse(primeP) == coefficient) { "Invalid RSA coefficient" }
        require(primeP * primeQ == modulus) { "Invalid RSA private key primes" }
        val comment = input.readSshString(MAX_COMMENT_BYTES, "comment")
        validateComment(comment)
        input.requirePadding(NONE_BLOCK_SIZE)
        val publicKey = encodeRsaPublicKey(exponent, modulus)
        val expectedOuterPublic = SshPublicKey(SshKeyAlgorithm.RSA, publicKey, "").blob()
        require(MessageDigest.isEqual(expectedOuterPublic, outerPublicBlob)) {
            "The OpenSSH public and private sections do not match"
        }
        val privateKey = KeyFactory.getInstance(RSA).generatePrivate(
            RSAPrivateCrtKeySpec(
                modulus,
                exponent,
                privateExponent,
                primeP,
                primeQ,
                privateExponent.mod(primeP - BigInteger.ONE),
                privateExponent.mod(primeQ - BigInteger.ONE),
                coefficient,
            ),
        ).encoded
        return fromStored(SshKeyAlgorithm.RSA.storedName, privateKey, publicKey, comment)
    }

    private fun parseRsaPublicKey(value: ByteArray): RsaPublicKey =
        DataInputStream(ByteArrayInputStream(value)).use { input ->
            val exponent = input.readPositiveMpint("RSA public exponent", MAX_RSA_EXPONENT_BYTES)
            val modulus = input.readPositiveMpint("RSA modulus", MAX_RSA_MPINT_BYTES)
            require(input.available() == 0) { "Unexpected data after the RSA public key" }
            require(MessageDigest.isEqual(value, encodeRsaPublicKey(exponent, modulus))) {
                "The RSA public key is not canonically encoded"
            }
            validateRsaPublicKey(exponent, modulus)
            RsaPublicKey(exponent, modulus)
        }

    private fun encodeRsaPublicKey(exponent: BigInteger, modulus: BigInteger): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeMpint(exponent)
                output.writeMpint(modulus)
            }
            bytes.toByteArray()
        }

    private fun rsaPrivateKey(value: ByteArray): RSAPrivateCrtKey {
        require(value.size <= MAX_PKCS8_BYTES) { "RSA private key is too large" }
        val privateKey = KeyFactory.getInstance(RSA).generatePrivate(PKCS8EncodedKeySpec(value))
        return privateKey as? RSAPrivateCrtKey
            ?: throw IllegalArgumentException("Invalid RSA private key")
    }

    private fun validateRsaBits(modulus: BigInteger) {
        require(modulus.bitLength() >= MIN_RSA_BITS) {
            "RSA private keys must be at least $MIN_RSA_BITS bits"
        }
        require(modulus.bitLength() <= MAX_RSA_BITS) {
            "RSA private keys must be at most $MAX_RSA_BITS bits"
        }
    }

    private fun validateRsaPublicKey(exponent: BigInteger, modulus: BigInteger) {
        validateRsaBits(modulus)
        require(
            exponent > BigInteger.ONE &&
                exponent.testBit(0) &&
                exponent.bitLength() <= MAX_RSA_EXPONENT_BITS
        ) { "Invalid RSA public exponent" }
    }

    private fun validateRsaPrivateKey(privateKey: RSAPrivateCrtKey) {
        val p = privateKey.primeP
        val q = privateKey.primeQ
        val d = privateKey.privateExponent
        val e = privateKey.publicExponent
        require(p > BigInteger.ONE && q > BigInteger.ONE && p != q) {
            "Invalid RSA private key primes"
        }
        require(p * q == privateKey.modulus) { "Invalid RSA private key primes" }
        validateRsaPublicKey(e, privateKey.modulus)
        val pMinusOne = p - BigInteger.ONE
        val qMinusOne = q - BigInteger.ONE
        val lcm = pMinusOne.divide(pMinusOne.gcd(qMinusOne)).multiply(qMinusOne)
        require(d.multiply(e).mod(lcm) == BigInteger.ONE) {
            "Invalid RSA private exponent"
        }
        require(privateKey.primeExponentP == d.mod(pMinusOne)) {
            "Invalid RSA CRT exponent"
        }
        require(privateKey.primeExponentQ == d.mod(qMinusOne)) {
            "Invalid RSA CRT exponent"
        }
        require(privateKey.crtCoefficient == q.modInverse(p)) {
            "Invalid RSA coefficient"
        }
        val challenge = RSA_SELF_TEST_MESSAGE
        val signature = Signature.getInstance(SHA256_WITH_RSA).run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        val publicKey = KeyFactory.getInstance(RSA).generatePublic(
            RSAPublicKeySpec(privateKey.modulus, e),
        )
        require(
            Signature.getInstance(SHA256_WITH_RSA).run {
                initVerify(publicKey)
                update(challenge)
                verify(signature)
            },
        ) { "RSA private key failed consistency check" }
    }

    private fun decodePem(value: String): ByteArray {
        require(value.length <= MAX_PEM_CHARS) { "The OpenSSH private key is too large" }
        val normalized = value.trim()
        val lines = normalized.lines()
        require(lines.firstOrNull() == PEM_BEGIN && lines.lastOrNull() == PEM_END) {
            "Paste an unencrypted OpenSSH private key"
        }
        val body = lines.drop(1).dropLast(1).joinToString(separator = "") { it.trim() }
        require(body.isNotEmpty()) { "The OpenSSH private key is empty" }
        val decoded = try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The OpenSSH private key is not valid Base64")
        }
        require(decoded.size <= MAX_OPENSSH_KEY_BYTES) { "The OpenSSH private key is too large" }
        return decoded
    }

    private fun validateComment(comment: String) {
        require('\u0000' !in comment && '\n' !in comment && '\r' !in comment) {
            "An SSH public key comment must fit on one line"
        }
        require(comment.encodeToByteArray().size <= MAX_COMMENT_BYTES) {
            "An SSH public key comment is too long"
        }
    }

    private fun DataInputStream.readSshBytes(maxLength: Int, field: String): ByteArray {
        val length = readUnsignedInt()
        require(length <= maxLength.toLong()) { "$field is too large" }
        require(length <= available().toLong()) { "Truncated OpenSSH private key" }
        return ByteArray(length.toInt()).also(::readFully)
    }

    private fun DataInputStream.readSshString(maxLength: Int, field: String): String =
        readSshBytes(maxLength, field).decodeToString(throwOnInvalidSequence = true)

    private fun DataInputStream.readPositiveMpint(field: String, maxLength: Int): BigInteger {
        val encoded = readSshBytes(maxLength, field)
        require(encoded.isNotEmpty()) { "$field is empty" }
        val value = BigInteger(encoded)
        require(value.signum() > 0) { "$field is not positive" }
        require(value.toByteArray().contentEquals(encoded)) { "$field is not canonically encoded" }
        return value
    }

    private fun DataInputStream.requirePadding(blockSize: Int) {
        val paddingLength = available()
        require(paddingLength in 1..blockSize) { "Invalid OpenSSH private key padding" }
        for (expected in 1..paddingLength) {
            require(readUnsignedByte() == expected and 0xff) {
                "Invalid OpenSSH private key padding"
            }
        }
    }

    private fun DataInputStream.readUnsignedInt(): Long = readInt().toLong() and 0xffff_ffffL

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeMpint(value: BigInteger) {
        require(value.signum() > 0) { "SSH mpint must be positive" }
        writeSshString(value.toByteArray())
    }

    private data class RsaPublicKey(
        val exponent: BigInteger,
        val modulus: BigInteger,
    )

    private companion object {
        val OPENSSH_MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
        const val NONE = "none"
        const val PEM_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
        const val PEM_END = "-----END OPENSSH PRIVATE KEY-----"
        const val ED25519_PRIVATE_KEY_BYTES = 32
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val ED25519_PRIVATE_AND_PUBLIC_BYTES = 64
        const val MIN_RSA_BITS = 2048
        const val MAX_RSA_BITS = 16384
        const val MAX_RSA_EXPONENT_BITS = 64
        const val GENERATED_RSA_BITS = 3072
        const val NONE_BLOCK_SIZE = 8
        const val MAX_ALGORITHM_BYTES = 64
        const val MAX_KDF_OPTIONS_BYTES = 4 * 1024
        const val MAX_COMMENT_BYTES = 4 * 1024
        const val MAX_PUBLIC_BLOB_BYTES = 16 * 1024
        const val MAX_PUBLIC_BLOB_BASE64_CHARS = 24 * 1024
        const val MAX_PUBLIC_KEY_LINE_CHARS = 32 * 1024
        const val MAX_PRIVATE_BLOCK_BYTES = 128 * 1024
        const val MAX_OPENSSH_KEY_BYTES = 192 * 1024
        const val MAX_PEM_CHARS = 300 * 1024
        const val MAX_PKCS8_BYTES = 64 * 1024
        const val MAX_RSA_MPINT_BYTES = MAX_RSA_BITS / 8 + 1
        const val MAX_RSA_EXPONENT_BYTES = 8
        const val RSA = "RSA"
        const val RSA_SHA512 = "rsa-sha2-512"
        const val SHA256_WITH_RSA = "SHA256withRSA"
        const val SHA512_WITH_RSA = "SHA512withRSA"
        val SSHSIG_MAGIC = "SSHSIG".encodeToByteArray()
        const val SSHSIG_VERSION = 1
        const val SSHSIG_HASH_ALGORITHM = "sha512"
        const val GIT_SSHSIG_NAMESPACE = "git"
        const val SSHSIG_PEM_BEGIN = "-----BEGIN SSH SIGNATURE-----"
        const val SSHSIG_PEM_END = "-----END SSH SIGNATURE-----"
        val RSA_SELF_TEST_MESSAGE = "Agentknock RSA key consistency".encodeToByteArray()
    }
}
