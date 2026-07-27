package com.duluin.ftth.vpn.application.port.outbound

/**
 * Materi PKI hasil penerbitan untuk sebuah hub: sebuah CA yang menandatangani dirinya
 * sendiri dan sertifikat server yang ditandatangani CA itu — semuanya dalam PEM.
 *
 * [caKeyPem] & [serverKeyPem] adalah kunci privat (RAHASIA) → disimpan terenkripsi;
 * [caCertPem] & [serverCertPem] publik dan boleh disimpan apa adanya.
 */
data class ServerPkiMaterial(
    val caCertPem: String,
    val caKeyPem: String,
    val serverCertPem: String,
    val serverKeyPem: String,
)

/**
 * Port keluar: aplikasi bertindak sebagai CA-nya sendiri. Menerbitkan CA + sertifikat
 * server dalam satu langkah saat hub dibuat, menghapus langkah easy-rsa manual bagi
 * operator. Sisi klien tetap auth username/password (tanpa sertifikat klien) supaya
 * onboarding Mikrotik cukup skrip satu baris.
 */
interface ServerPkiIssuer {

    /** Terbitkan CA baru + sertifikat server (EKU serverAuth) untuk hub bernama [commonName]. */
    fun issueForServer(commonName: String): ServerPkiMaterial
}
