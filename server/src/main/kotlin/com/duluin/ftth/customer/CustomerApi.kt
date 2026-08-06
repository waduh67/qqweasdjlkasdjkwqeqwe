package com.duluin.ftth.customer

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Kontrak publik module customer untuk module lain (gis saat menyusun panel ODP
 * dan telusur jalur; nanti incident saat menghitung pelanggan terdampak).
 */
interface CustomerApi {

    fun findCustomer(id: UUID): CustomerRef?

    /** Resolusi sekumpulan id pelanggan sekaligus; id yang tak ditemukan diabaikan. */
    fun findCustomersByIds(ids: Set<UUID>): List<CustomerRef>

    /**
     * Resolusi satu langganan untuk module bng saat memprovisikan identitas jaringan
     * (akun PPPoE): memberi customerId, paket, dan status tanpa membocorkan agregat
     * internal customer. `null` bila langganan tak ditemukan.
     */
    fun findSubscription(id: UUID): SubscriptionRef?

    /**
     * Semua langganan seorang pelanggan — untuk agregator Subscriber-360 yang menyusun
     * pandangan 360° dari satu pelanggan. Kosong bila pelanggan belum punya langganan.
     */
    fun findSubscriptionsByCustomer(customerId: UUID): List<SubscriptionRef>

    /** Semua pelanggan yang tersambung pada sebuah ODP, terurut menurut nomor port. */
    fun findOccupantsOfOdp(odpId: UUID): List<OdpOccupant>

    /**
     * Pelanggan aktif yang belum punya ONU terpasang di ODP mana pun — kandidat
     * pemilik sebuah ONU liar yang baru terlihat OLT. Dipakai monitoring untuk
     * menautkan ONU terdeteksi ke pelanggan yang menunggu instalasi.
     *
     * @param areaIds `null` = tanpa batas area; set kosong = pengguna tanpa area
     *        sehingga hasilnya kosong.
     */
    fun findAwaitingInstallation(areaIds: Set<UUID>?): List<CustomerRef>

    /**
     * Penempatan fisik seorang pelanggan (ODP mana, port berapa, ONU apa).
     * `null` bila pelanggan belum punya ONU yang terpasang — kondisi normal untuk
     * pelanggan yang baru didaftarkan dan menunggu instalasi.
     */
    fun findPlacementOf(customerId: UUID): CustomerPlacement?

    /** Nomor port yang sudah terpakai pada sebuah ODP. */
    fun occupiedPortsOn(odpId: UUID): Set<Int>

    /** Jumlah ONU terpasang per ODP dalam satu query — untuk heatmap utilisasi peta. */
    fun countOccupantsByOdp(odpIds: Set<UUID>): Map<UUID, Long>

    /** Vector tile berisi layer `customer`; digabung module `gis` dengan layer jaringan. */
    fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray

    /**
     * Memetakan serial yang dilaporkan OLT ke ONU yang terdaftar. Serial yang
     * tidak ada di hasil berarti perangkat liar — terpasang di lapangan tapi
     * belum pernah didaftarkan.
     */
    fun findOnusBySerialNumbers(serialNumbers: Set<String>): List<OnuRef>

    /**
     * Penempatan sekumpulan ONU (ke pelanggan & ODP mana), untuk memetakan alarm
     * ONU ke kabel yang terdampak. ONU yang tidak ditemukan diabaikan.
     */
    fun placementsForOnus(onuIds: Set<UUID>): List<OnuPlacementRef>

    /**
     * Menerapkan status ONU yang teramati dari jaringan.
     *
     * Module monitoring TIDAK menulis ke agregat ONU secara langsung; ia melapor
     * lewat kontrak ini dan module customer yang memutuskan. Hanya baris yang
     * statusnya benar-benar berubah yang ditulis — satu siklus polling membawa
     * ribuan bacaan dan hampir semuanya tidak berubah.
     *
     * @return jumlah ONU yang statusnya berubah.
     */
    fun recordObservedOnuStatuses(statuses: Map<UUID, String>): Int

    /**
     * Memprovisikan sebuah ONU dari perangkat yang terdeteksi jaringan: daftarkan
     * serialnya untuk pelanggan (atau pakai ulang bila sudah terdaftar untuk
     * pelanggan yang sama) lalu pasang ke port ODP. Aturan port tetap ditegakkan
     * network. Dipakai module monitoring saat operator menuntaskan ONU dari kotak
     * masuk provisioning.
     *
     * @throws com.duluin.ftth.common.domain.error.ConflictException bila serial sudah terdaftar untuk pelanggan lain
     */
    fun provisionOnu(command: ProvisionOnuCommand): OnuRef

    /**
     * Langganan yang layak ditagih milik tenant aktif: yang ACTIVE atau ISOLATED —
     * langganan terisolir tetap berjalan kontraknya dan tetap ditagih. Dipakai module
     * billing untuk menerbitkan tagihan periode berjalan.
     */
    fun findBillableSubscriptions(): List<BillableSubscription>

    /** Resolusi satu langganan untuk penagihan; `null` bila tak ditemukan. */
    fun findBillableSubscription(subscriptionId: UUID): BillableSubscription?

    /**
     * Isolir langganan karena menunggak. No-op bila langganan tidak sedang ACTIVE
     * (sudah terisolir, pending, atau berakhir) — penegakan tunggakan aman diulang.
     */
    fun isolateForBilling(subscriptionId: UUID)

    /**
     * Pulihkan langganan setelah tagihannya lunas. No-op bila langganan tidak sedang
     * ISOLATED — auto-pulih tidak menghidupkan langganan yang memang belum aktif.
     */
    fun reactivateForBilling(subscriptionId: UUID)

    /**
     * Aktivasi langganan begitu instalasi (WO PSB) selesai — layanan resmi hidup dan mulai
     * ditagih (prorata dari saat aktivasi). No-op bila langganan tidak sedang PENDING, jadi
     * menyelesaikan ulang WO PSB yang sempat ditolak penyelia tak menggeser tanggal aktivasi.
     * Dipakai module workorder saat teknisi menuntaskan WO pemasangan.
     */
    fun activateForInstallation(subscriptionId: UUID)

    /**
     * Terminasi langganan begitu pembongkaran (WO DISMANTLE) selesai — layanan berakhir.
     * No-op bila langganan sudah TERMINATED, jadi aman diulang. Dipakai module workorder saat
     * teknisi menuntaskan WO bongkar.
     */
    fun terminateForDismantle(subscriptionId: UUID)

    /**
     * Onboarding: daftarkan pelanggan baru lewat kontrak publik (kode unik & aturan module
     * customer tetap ditegakkan), kembalikan id-nya. Dipakai orkestrasi PSB ekspres yang
     * merangkai pendaftaran + langganan + akun + WO dalam satu transaksi.
     */
    fun registerCustomer(command: RegisterCustomerCommand): UUID

    /**
     * Onboarding: buka langganan merujuk paket katalog; lahir berstatus PENDING (menunggu
     * instalasi), sehingga akun jaringannya pun PENDING sampai WO PSB selesai. Kembalikan id
     * langganan. [monthlyFeeOverride] null = pakai harga paket.
     */
    fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: java.math.BigDecimal?): UUID

    /**
     * Statistik pelanggan & langganan TENANT untuk laporan (modul `reporting`): jumlah
     * pelanggan, cacah langganan per status, dan MRR (jumlah tarif bulanan langganan yang
     * masih ditagih). Customer tetap satu-satunya yang menyentuh tabelnya (RLS per tenant).
     */
    fun subscriberStats(): SubscriberStats
}

/**
 * Potret pelanggan & langganan satu tenant untuk laporan.
 *
 * [totalCustomers] = seluruh pelanggan tenant. [subscriptionsByStatus] = cacah langganan per
 * nama status ([com.duluin.ftth.customer.domain.model.SubscriptionStatus]: PENDING/ACTIVE/
 * ISOLATED/TERMINATED). [billableCount] = langganan penghasil pendapatan berulang (ACTIVE+
 * ISOLATED) — pembagi ARPU. [mrr] = jumlah tarif bulanan langganan billable (skala 2).
 */
data class SubscriberStats(
    val totalCustomers: Int,
    val subscriptionsByStatus: Map<String, Int>,
    val billableCount: Int,
    val mrr: java.math.BigDecimal,
)

/** Perintah mendaftarkan pelanggan baru lewat kontrak publik (orkestrasi onboarding PSB ekspres). */
data class RegisterCustomerCommand(
    /** Kosong/null = server membuat kode berurut otomatis (`CUST-000001`). */
    val code: String?,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String,
    val location: Coordinate,
    val areaId: UUID?,
    /** Nomor identitas (NIK/KTP/paspor); opsional. */
    val idCardNumber: String? = null,
)

/**
 * Pandangan ringkas sebuah langganan untuk penagihan (module billing). Membawa snapshot
 * override siklus billing per-paket (null = ikut kebijakan global billing) supaya
 * penerbitan/penegakan bisa mengikuti setelan paket tanpa billing menyentuh catalog.
 */
data class BillableSubscription(
    val subscriptionId: UUID,
    val customerId: UUID,
    val packageName: String,
    val monthlyFee: java.math.BigDecimal,
    /** Nama [com.duluin.ftth.customer.domain.model.SubscriptionStatus], mis. "ACTIVE". */
    val status: String,
    val activatedAt: java.time.Instant?,
    /** Override siklus dari paket; null = ikut BillingProperties global. */
    val prorateOnActivation: Boolean?,
    val billingDayOfMonth: Int?,
    val graceDays: Int?,
    val autoIsolir: Boolean?,
)

/** Perintah memprovisikan ONU liar menjadi pelanggan terpasang. */
data class ProvisionOnuCommand(
    val serialNumber: String,
    val model: String?,
    val customerId: UUID,
    val odpId: UUID,
    val portNumber: Int,
    /** Redaman baseline saat instalasi untuk deteksi degradasi; boleh null. */
    val installRxPowerDbm: Double?,
)

/** Pandangan ringkas sebuah ONU untuk konsumen lintas-module. */
data class OnuRef(
    val id: UUID,
    val serialNumber: String,
    val customerId: UUID,
    val customerName: String,
    val odpId: UUID?,
    val status: String,
)

data class CustomerRef(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val location: Coordinate,
    val status: String,
)

/** Pandangan ringkas sebuah langganan untuk konsumen lintas-module (mis. bng). */
data class SubscriptionRef(
    val id: UUID,
    val customerId: UUID,
    /** Paket katalog langganan; bng membaca sisi jaringannya live untuk RADIUS. */
    val planId: UUID?,
    val packageName: String,
    val bandwidthMbps: Int,
    /** Nama [com.duluin.ftth.customer.domain.model.SubscriptionStatus], mis. "ACTIVE". */
    val status: String,
)

/** Penempatan sebuah ONU: milik pelanggan mana dan di ODP mana (bila terpasang). */
data class OnuPlacementRef(
    val onuId: UUID,
    val customerId: UUID,
    val odpId: UUID?,
)

/** Di mana ONU seorang pelanggan terpasang, beserta kondisi optiknya. */
data class CustomerPlacement(
    /** Id ONU terpasang — untuk memadukan dengan bacaan hidup monitoring per ONU. */
    val onuId: UUID,
    val odpId: UUID,
    val portNumber: Int,
    val onuSerialNumber: String,
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
)

/**
 * Satu pelanggan yang menempati port ODP — gabungan data pelanggan, ONU, dan
 * langganannya. Bentuk inilah yang menjawab pertanyaan lapangan "di ODP ini ada
 * siapa saja dan statusnya apa".
 */
data class OdpOccupant(
    val portNumber: Int,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val phone: String?,
    val location: Coordinate,
    /** Id ONU penghuni — untuk memadukan dengan bacaan hidup monitoring per ONU. */
    val onuId: UUID,
    val onuSerialNumber: String,
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
    val subscriptionPackage: String?,
    val subscriptionStatus: String?,
)
