package com.duluin.ftth.vpn

import com.duluin.ftth.vpn.adapter.outbound.pki.BouncyCastleServerPkiIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class BouncyCastleServerPkiIssuerTest {

    private val issuer = BouncyCastleServerPkiIssuer()

    @Test
    fun `menerbitkan CA plus sertifikat server yang ditandatangani CA`() {
        val material = issuer.issueForServer("Hub Jakarta")

        assertThat(material.caCertPem).contains("BEGIN CERTIFICATE")
        assertThat(material.serverCertPem).contains("BEGIN CERTIFICATE")
        assertThat(material.caKeyPem).contains("PRIVATE KEY")
        assertThat(material.serverKeyPem).contains("PRIVATE KEY")

        val caCert = parse(material.caCertPem)
        val serverCert = parse(material.serverCertPem)

        // Sertifikat server ditandatangani CA → verify tidak melempar.
        serverCert.verify(caCert.publicKey)

        // CA adalah CA sejati (basicConstraints >= 0); server bukan (-1).
        assertThat(caCert.basicConstraints).isGreaterThanOrEqualTo(0)
        assertThat(serverCert.basicConstraints).isEqualTo(-1)

        // Klien memakai `remote-cert-tls server` → server wajib EKU serverAuth.
        assertThat(serverCert.extendedKeyUsage).contains(EKU_SERVER_AUTH)
    }

    @Test
    fun `tiap penerbitan menghasilkan CA dan kunci server yang berbeda`() {
        val a = issuer.issueForServer("Hub A")
        val b = issuer.issueForServer("Hub B")

        assertThat(a.caCertPem).isNotEqualTo(b.caCertPem)
        assertThat(a.serverKeyPem).isNotEqualTo(b.serverKeyPem)
    }

    private fun parse(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    private companion object {
        const val EKU_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    }
}
