package com.yourapp.remotectrl.crypto

import android.util.Base64
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SessionCrypto {

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
        const val HKDF_INFO = "remotectrl-session-v1"
    }

    private var _keyPair: org.bouncycastle.crypto.AsymmetricCipherKeyPair? = null
    private var sharedKey: ByteArray? = null

    init {
        val ecParams = ECNamedCurveTable.getParameterSpec("secp256r1")
        val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)

        val keyParams = ECKeyGenerationParameters(domainParams, java.security.SecureRandom())
        val generator = ECKeyPairGenerator()
        generator.init(keyParams)
        _keyPair = generator.generateKeyPair()
    }

    private val keyPair: org.bouncycastle.crypto.AsymmetricCipherKeyPair
        get() = _keyPair!!

    fun getPublicKeyBase64(): String {
        val pubKey = keyPair.`public` as ECPublicKeyParameters
        val encoded = pubKey.q.getEncoded(false)
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    fun computeSharedKey(peerPubKeyBytes: ByteArray) {
        val ecParams = ECNamedCurveTable.getParameterSpec("secp256r1")
        val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)

        val peerPoint = ecParams.curve.decodePoint(peerPubKeyBytes)
        val peerKey = ECPublicKeyParameters(peerPoint, domainParams)

        val agreement = ECDHBasicAgreement()
        agreement.init(keyPair.`private`)
        val rawShared = agreement.calculateAgreement(peerKey).toByteArray()

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(
            rawShared,
            null,
            HKDF_INFO.toByteArray()
        ))
        sharedKey = ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    fun getSharedKey(): ByteArray = sharedKey ?: throw IllegalStateException("Key not established")
    fun setSharedKey(key: ByteArray) { sharedKey = key }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("Key not established")
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("Key not established")
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        return cipher.doFinal(ciphertext)
    }
}
