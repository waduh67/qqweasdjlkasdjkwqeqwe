package com.duluin.ftth.vpn.adapter.outbound.pki

import com.duluin.ftth.vpn.application.port.outbound.ServerPkiIssuer
import com.duluin.ftth.vpn.application.port.outbound.ServerPkiMaterial
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.springframework.stereotype.Component
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Implementasi CA berbasis BouncyCastle: aplikasi menerbitkan CA sendiri + sertifikat
 * server (RSA 2048, SHA256withRSA, masa 10 tahun). Klien memakai `remote-cert-tls server`,
 * jadi sertifikat server diberi EKU serverAuth. Tak ada sertifikat klien — autentikasi
 * klien tetap username/password supaya Mikrotik cukup skrip satu baris.
 */
@Component
class BouncyCastleServerPkiIssuer : ServerPkiIssuer {

    override fun issueForServer(commonName: String): ServerPkiMaterial {
        val caKeyPair = generateRsaKeyPair()
        val serverKeyPair = generateRsaKeyPair()

        val notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS))
        val notAfter = Date.from(Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS))

        val caSubject = X500Name("CN=FTTH VPN CA ${sanitizeCn(commonName)}")
        val caCert = buildCaCertificate(caSubject, caKeyPair, notBefore, notAfter)
        val serverCert = buildServerCertificate(
            issuer = caSubject,
            caPrivateKey = caKeyPair.private,
            subject = X500Name("CN=server"),
            subjectPublicKey = serverKeyPair.public,
            notBefore = notBefore,
            notAfter = notAfter,
        )

        return ServerPkiMaterial(
            caCertPem = toPem(caCert),
            caKeyPem = toPem(caKeyPair.private),
            serverCertPem = toPem(serverCert),
            serverKeyPem = toPem(serverKeyPair.private),
        )
    }

    private fun buildCaCertificate(
        subject: X500Name,
        keyPair: KeyPair,
        notBefore: Date,
        notAfter: Date,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            subject, randomSerial(), notBefore, notAfter, subject, keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        return sign(builder, keyPair.private)
    }

    private fun buildServerCertificate(
        issuer: X500Name,
        caPrivateKey: PrivateKey,
        subject: X500Name,
        subjectPublicKey: PublicKey,
        notBefore: Date,
        notAfter: Date,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuer, randomSerial(), notBefore, notAfter, subject, subjectPublicKey,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        return sign(builder, caPrivateKey)
    }

    private fun sign(builder: JcaX509v3CertificateBuilder, signingKey: PrivateKey): X509Certificate {
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signingKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun generateRsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA")
            .apply { initialize(RSA_KEY_SIZE, SecureRandom()) }
            .generateKeyPair()

    private fun randomSerial(): BigInteger = BigInteger(SERIAL_BITS, SecureRandom())

    private fun toPem(obj: Any): String {
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(obj) }
        return writer.toString()
    }

    /** CN aman: hanya alnum/spasi/titik/strip agar valid sebagai nilai X.500, dipangkas. */
    private fun sanitizeCn(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "").take(48).ifBlank { "hub" }

    private companion object {
        const val RSA_KEY_SIZE = 2048
        const val SERIAL_BITS = 159
        const val VALIDITY_DAYS = 3650L
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }
}
