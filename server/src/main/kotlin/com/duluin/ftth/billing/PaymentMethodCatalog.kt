package com.duluin.ftth.billing

import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant

/**
 * Katalog metode bayar in-app (mode API Pivot) yang ditawarkan ke tenant/pelanggan: QRIS dan
 * Virtual Account (daftar bank kurasi). Konstan bersama untuk KEDUA alur tagihan (langganan
 * platform→tenant & tagihan pelanggan tenant→pelanggan, termasuk portal) dan dipakai backend
 * untuk memvalidasi pilihan sebelum membuat charge. [METHOD_VA]/[METHOD_QRIS] persis nilai
 * `paymentMethod.type` yang diminta Pivot.
 *
 * Bagian dari API publik modul `billing` (paket root) → boleh dipakai `platformbilling` & `portal`.
 *
 * Catatan: channel VA di sini adalah daftar kurasi; bank yang benar-benar aktif tergantung
 * konfigurasi akun master Pivot. Channel non-aktif akan ditolak Pivot saat charge dibuat.
 */
object PaymentMethodCatalog {
    const val METHOD_QRIS = "QR"
    const val METHOD_VA = "VIRTUAL_ACCOUNT"

    /** Bank Virtual Account yang ditawarkan (kode = channel Pivot). */
    val vaChannels: List<VaChannelOption> = listOf(
        VaChannelOption("BRI", "Bank BRI"),
        VaChannelOption("BNI", "Bank BNI"),
        VaChannelOption("MANDIRI", "Bank Mandiri"),
        VaChannelOption("BCA", "Bank BCA"),
        VaChannelOption("BSI", "Bank Syariah Indonesia"),
        VaChannelOption("CIMB", "CIMB Niaga"),
        VaChannelOption("PERMATA", "Bank Permata"),
    )

    /** Metode yang ditawarkan ke UI: QRIS (tanpa channel) & Virtual Account (pilih bank). */
    val methods: List<PaymentMethodOption> = listOf(
        PaymentMethodOption(type = METHOD_QRIS, label = "QRIS", channels = emptyList()),
        PaymentMethodOption(type = METHOD_VA, label = "Virtual Account", channels = vaChannels),
    )

    /**
     * Validasi pilihan metode/channel; lempar [ValidationException] bila tak didukung. VA wajib
     * menyertakan channel bank yang ada di [vaChannels]; QRIS tak butuh channel.
     */
    fun validate(method: String, channel: String?) {
        when (method.trim().uppercase()) {
            METHOD_QRIS -> Unit
            METHOD_VA -> {
                val ch = channel?.trim()?.uppercase()
                if (ch.isNullOrBlank()) throw ValidationException("Bank Virtual Account wajib dipilih")
                if (vaChannels.none { it.code == ch }) {
                    throw ValidationException("Bank Virtual Account '$channel' tidak didukung")
                }
            }
            else -> throw ValidationException("Metode bayar '$method' tidak didukung")
        }
    }

    /** Metode & channel ternormalisasi (uppercase, trim); channel null untuk QRIS. */
    fun normalize(method: String, channel: String?): Pair<String, String?> {
        val m = method.trim().uppercase()
        return m to if (m == METHOD_VA) channel?.trim()?.uppercase() else null
    }

    /**
     * Instruksi tersimpan [saved] masih bisa dipakai untuk pilihan [method]/[channel] — jadi tak
     * perlu membuat sesi bayar baru di penyedia. Syaratnya instrumennya sama persis, instruksinya
     * benar-benar terisi, dan belum kedaluwarsa pada [now].
     *
     * Dipakai halaman bayar publik di KEDUA sisi tagihan (pelanggan & langganan SaaS): tautannya
     * dipegang siapa saja, jadi memuat ulang halaman tak boleh menghambur sesi bayar baru.
     * Instruksi tanpa masa berlaku (`*ExpiresAt` null) dianggap masih hidup — penyedia yang tak
     * memberi batas waktu tak boleh memaksa charge diulang tiap kali halaman dibuka.
     */
    fun stillUsable(saved: StoredInstruction, method: String, channel: String?, now: Instant): Boolean {
        val (normMethod, normChannel) = normalize(method, channel)
        if (saved.method != normMethod || saved.channel != normChannel) return false
        return when (normMethod) {
            METHOD_VA -> saved.vaNumber != null && saved.vaExpiresAt?.isBefore(now) != true
            METHOD_QRIS -> saved.qrContent != null && saved.qrExpiresAt?.isBefore(now) != true
            else -> false
        }
    }
}

/**
 * Cuplikan instruksi bayar yang sudah tersimpan pada sebuah tagihan — bentuk netral agar
 * [PaymentMethodCatalog.stillUsable] bisa dipakai baik oleh `Invoice` (tagihan pelanggan) maupun
 * `TenantSubscriptionInvoice` (langganan SaaS) yang menyimpan kolom yang sama.
 */
data class StoredInstruction(
    val method: String?,
    val channel: String?,
    val vaNumber: String?,
    val vaExpiresAt: Instant?,
    val qrContent: String?,
    val qrExpiresAt: Instant?,
)

/** Satu metode bayar yang ditawarkan; [channels] kosong bila tak perlu pilih bank (QRIS). */
data class PaymentMethodOption(
    val type: String,
    val label: String,
    val channels: List<VaChannelOption>,
)

/** Satu bank Virtual Account: [code] = channel Pivot, [label] nama tampil. */
data class VaChannelOption(
    val code: String,
    val label: String,
)
