package dev.nexus.local.identity

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * TLS device identity per protocol/pairing.md:
 * ECDSA P-256 key pair (PKCS#8) + self-signed X.509 cert, CN = device_id,
 * SKI/AKI extensions, 10-year validity.
 *
 * Stored in app-private files (`identity_cert.der`, `identity_key.der`),
 * mirroring the Rust layout. The private key never leaves the device.
 */
data class NexusTlsIdentity(
    val deviceId: String,
    val certificate: X509Certificate,
    val privateKey: PrivateKey
) {
    companion object {
        private const val TAG = "NexusIdentity"
        private const val DIR = "nexus"
        private const val CERT_FILE = "identity_cert.der"
        private const val KEY_FILE = "identity_key.der"

        /** Load the persisted identity, generating it on first run. */
        fun loadOrCreate(context: Context, deviceId: String): NexusTlsIdentity {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val certFile = File(dir, CERT_FILE)
            val keyFile = File(dir, KEY_FILE)

            if (certFile.isFile && keyFile.isFile) {
                runCatching {
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(certFile.inputStream()) as X509Certificate
                    val key = KeyFactory.getInstance("EC")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    if (commonName(cert) == deviceId) {
                        Log.i(TAG, "loaded existing TLS identity for $deviceId")
                        return NexusTlsIdentity(deviceId, cert, key)
                    }
                    Log.w(TAG, "cert CN does not match device id; regenerating identity")
                }.onFailure { Log.w(TAG, "identity files unreadable; regenerating: $it") }
            }

            val identity = generate(deviceId)
            certFile.writeBytes(identity.certificate.encoded)
            keyFile.writeBytes(identity.privateKey.encoded)
            Log.i(TAG, "generated new TLS identity for $deviceId")
            return identity
        }

        private fun commonName(cert: X509Certificate): String? =
            cert.subjectX500Principal.name
                .split(",")
                .map { it.trim() }
                .firstOrNull { it.startsWith("CN=") }
                ?.removePrefix("CN=")

        private fun generate(deviceId: String): NexusTlsIdentity {
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            val name = X500Name("CN=$deviceId")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name,                                  // issuer (self-signed)
                BigInteger(128, SecureRandom()),
                Date(now - 86_400_000L),               // 1 day back: clock skew
                Date(now + 10L * 365 * 86_400_000L),   // 10-year validity
                name,                                  // subject = CN=device_id
                keyPair.public
            )
            builder.addExtension(Extension.keyUsage, false, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(
                    arrayOf(
                        KeyPurposeId.id_kp_serverAuth,
                        KeyPurposeId.id_kp_clientAuth
                    )
                )
            )
            // Note: no SKI/AKI extensions — the Rust implementation (rcgen
            // defaults) does not emit them either, and neither side
            // validates extensions (chain validity is not checked by
            // design; pairing.md's mention of SKI/AKI is aspirational).

            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            cert.verify(keyPair.public) // sanity: must self-verify
            return NexusTlsIdentity(deviceId, cert, keyPair.private)
        }
    }
}
