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

    /**
     * Tulis kredensial (radcheck Cleartext-Password) + keanggotaan grup (radusergroup).
     * [framedIp] non-null (DHCP/Static) menambah reservasi `radreply Framed-IP-Address`;
     * null (PPPoE/Hotspot) hanya membersihkan reservasi lama — jalur idempoten sama.
     */
    fun provision(tenantId: UUID, scopedUsername: String, password: String, groupname: String, framedIp: String?)

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

    /**
     * Pastikan grup pelanggan terisolir ([com.duluin.ftth.bng.domain.model.RadiusGroups.ISOLIR])
     * ada dan berisi dua atribut yang menghidupkan halaman isolir: sisa kecepatan
     * ([rateLimit]) dan keanggotaan address-list router ([addressList] → VSA
     * `Mikrotik-Address-List`).
     *
     * Terpisah dari [syncGroup] karena bukan grup paket: ia milik PLATFORM, sama untuk semua
     * tenant, dan tak pernah lahir dari katalog siapa pun — tak ada `planId` yang bisa
     * menurunkannya. Idempoten (hapus-lalu-tulis), jadi aman dipanggil berulang.
     */
    fun ensureIsolirGroup(tenantId: UUID, rateLimit: String, addressList: String)

    /**
     * Grup yang KINI tercatat di `radusergroup` untuk tiap identitas di [scopedUsernames]
     * — satu-satunya jalan mengetahui apa yang sungguh berlaku di RADIUS, bukan apa yang
     * kita kira sudah kita tulis. Dipakai [com.duluin.ftth.bng.application.service.IsolirReconciler].
     *
     * Identitas yang TAK punya baris sengaja tak muncul di peta alih-alih dipetakan ke null:
     * "belum pernah diprovisikan" adalah keadaan yang sah (akun yang instalasinya belum
     * rampung), bukan penyimpangan yang perlu diperbaiki. Jalur-BACA di port jalur-tulis
     * karena tabelnya sama dan koneksinya sama; memisahkannya ke port sendiri hanya akan
     * menggandakan resolver koneksi tanpa menambah batas apa pun.
     */
    fun groupsOf(tenantId: UUID, scopedUsernames: Collection<String>): Map<String, String>
}
