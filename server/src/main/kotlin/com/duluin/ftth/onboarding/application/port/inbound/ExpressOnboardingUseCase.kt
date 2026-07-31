package com.duluin.ftth.onboarding.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * PSB ekspres: satu langkah onboarding pelanggan baru untuk operator ISP menengah ke atas.
 * Merangkai pendaftaran pelanggan → buka langganan → provisi akun jaringan → buka WO PSB
 * dalam SATU transaksi (all-or-nothing). Langganan & akun lahir PENDING dan BELUM ditulis ke
 * RADIUS — layanan baru resmi hidup saat WO PSB dituntaskan teknisi (yang mengaktifkan langganan
 * lalu memprovisi akun ke RADIUS). WO adalah tulang punggung akuntabilitas pemasangan.
 */
interface ExpressOnboardingUseCase {

    fun onboardPsb(command: ExpressPsbCommand): ExpressPsbResult
}

/**
 * Perintah PSB ekspres. Menyatukan tiga entitas lintas-module lewat kontrak publiknya:
 * pelanggan, langganan (paket), dan akun jaringan (kredensial). [username]/[secret] boleh
 * dikosongkan — server meng-generate-nya (operator melihatnya lewat field yang di-generate
 * klien). [serviceType] null → PPPOE. [title] null → "PSB {nama pelanggan}".
 */
data class ExpressPsbCommand(
    // Pelanggan
    val code: String,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String,
    val location: Coordinate,
    val areaId: UUID?,
    // Langganan
    val planId: UUID,
    val monthlyFeeOverride: BigDecimal?,
    // Akun jaringan
    val username: String?,
    val secret: String?,
    val serviceType: String?,
    val nasId: UUID?,
    val framedIp: String?,
    // Work order PSB
    val title: String?,
    val description: String?,
    val scheduledAt: Instant?,
    val assignedTo: UUID?,
)

/**
 * Hasil PSB ekspres: id semua entitas yang terbentuk + kode WO untuk operator. [username] final
 * (server bisa meng-generate). TAK pernah membawa secret — password PPPoE tak pernah keluar API;
 * operator memakai yang ia isi/generate di sisi klien.
 */
data class ExpressPsbResult(
    val customerId: UUID,
    val subscriptionId: UUID,
    val accessId: UUID,
    val username: String,
    val workOrderId: UUID,
    val workOrderCode: String,
)
