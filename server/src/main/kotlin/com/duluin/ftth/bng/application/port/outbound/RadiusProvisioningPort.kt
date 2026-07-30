package com.duluin.ftth.bng.application.port.outbound

import java.util.UUID

/**
 * Jalur-TULIS otorisasi RADIUS yang kini dipegang SERVER (bukan collector on-prem):
 * menulis `radcheck`/`radusergroup`/`radgroupreply`/`radgroupcheck` langsung ke radius-db
 * platform. Inilah "RADIUS jadi pusat" — paket = satu grup, akun cukup diikutkan ke grupnya.
 *
 * [scopedUsername] SUDAH di-prefix kode tenant (`"{slug}:{username}"`) oleh pemanggil,
 * bukan di sini — adapter murni SQL, tak menyentuh modul tenancy. Nama grup (`plan:{uuid}`)
 * pakai UUID → sudah unik lintas-tenant, TAK di-prefix. [tenantId] hanya untuk memilih
 * koneksi (jahitan sharding [RadiusConnectionResolver]); kini semua tenant satu cluster.
 *
 * Semua operasi IDEMPOTEN (DELETE-lalu-INSERT satu transaksi): antrean at-least-once, jadi
 * menjalankan dua kali harus menghasilkan keadaan yang sama.
 */
interface RadiusProvisioningPort {

    /**
     * True bila radius-db platform dikonfigurasi (provisioning server-side aktif). Worker
     * memakainya untuk melewati putaran saat radius-db belum diatur (dev/test) — aksi tetap
     * menumpuk PENDING dan jalan begitu dikonfigurasi.
     */
    fun isConfigured(): Boolean

    /** Tulis kredensial (radcheck Cleartext-Password) + keanggotaan grup (radusergroup). */
    fun provision(tenantId: UUID, scopedUsername: String, password: String, groupname: String)

    /** Hapus seluruh baris otorisasi akun (radcheck/radreply/radusergroup by username). */
    fun deprovision(tenantId: UUID, scopedUsername: String)

    /**
     * Setel atribut grup paket: rate-limit normal (radgroupreply Mikrotik-Rate-Limit),
     * batas sesi ([simultaneousUse] → radgroupcheck Simultaneous-Use, dihapus bila null),
     * dan — bila FUP aktif — grup throttle kedua ([fupGroupname]/[fupRateLimit]).
     */
    @Suppress("LongParameterList")
    fun syncGroup(
        tenantId: UUID,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    )
}
