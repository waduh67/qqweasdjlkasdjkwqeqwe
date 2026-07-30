package com.duluin.ftth.bng.application.port.outbound

/**
 * Jalur-KONTROL sesi RADIUS yang kini bisa dipegang SERVER langsung (RADIUS-as-a-service):
 * mengirim DAE (RFC 5176) — Disconnect-Request memutus sesi PPPoE, CoA-Request menurunkan/
 * mengubah kecepatannya — ke BRAS/NAS yang menutup sesi, bukan ke server RADIUS. Dipakai
 * hanya untuk NAS yang server jangkau sendiri (reachability DIRECT/VPN); NAS COLLECTOR tetap
 * dilayani agent on-prem lewat jalur turun collector.
 *
 * [host] = alamat NAS tujuan DAE (IP publik untuk DIRECT, IP overlay untuk VPN — pemanggil
 * yang memilih). [secret] = shared secret CoA. [username] BARE (bukan scoped `{slug}:`):
 * itulah User-Name yang dipegang NAS di sesinya — `sql_user_name` hanya menulis ulang lapisan
 * SQL, bukan User-Name di kabel. [acctSessionId]/[nasIp] diresolusi pemanggil dari `radacct`
 * agar paket menyasar sesi yang tepat. [identifier] diturunkan dari id aksi agar kiriman ulang
 * (at-least-once) membawa identifier sama.
 *
 * Kontrak kegagalan: kedua metode MELEMPAR pada kegagalan tegas (NAK non-idempoten, NAS bisu,
 * secret salah). [disconnect] MENELAN "sesi sudah tak ada" (NAK 503) sebagai selesai — target
 * sudah tercapai. Pemanggil membedakan "sesi tak ada saat mau CoA" (degradasi anggun) SEBELUM
 * memanggil [changeRate], sebab CoA pada sesi mati tak bermakna.
 */
interface RadiusSessionControlPort {

    /**
     * Kirim Disconnect-Request ke [host]. NAK "Session Context Not Found" (503) ditelan
     * sebagai selesai (sesi hilang antara baca `radacct` & DAE = target tercapai); NAK lain
     * atau NAS bisu → lempar.
     */
    @Suppress("LongParameterList")
    fun disconnect(
        host: String,
        secret: String,
        username: String,
        acctSessionId: String?,
        nasIp: String?,
        identifier: Int,
    )

    /**
     * Kirim CoA-Request ke [host] menyetel Mikrotik-Rate-Limit ke [upMbps]/[downMbps].
     * ACK → selesai; NAK atau NAS bisu → lempar. Pemanggil sudah memastikan sesi hidup.
     */
    @Suppress("LongParameterList")
    fun changeRate(
        host: String,
        secret: String,
        username: String,
        downMbps: Int,
        upMbps: Int,
        acctSessionId: String?,
        identifier: Int,
    )
}
