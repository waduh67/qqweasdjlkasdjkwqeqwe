package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading

/**
 * Kontrak untuk membaca sesi PPPoE dari satu BRAS.
 *
 * Seperti [OltAdapter] untuk OLT, di sinilah perbedaan vendor BRAS diisolasi:
 * RouterOS lewat REST `/ppp/active`, FreeRADIUS lewat tabel `radacct`. Bagian lain
 * collector maupun server hanya pernah melihat [RadiusSessionReading] yang seragam.
 */
interface BngAdapter {

    /** Nama vendor sebagaimana dikenal server, mis. `MIKROTIK`. */
    val vendor: String

    /** Membaca seluruh sesi PPPoE aktif di bawah BRAS ini. */
    fun pollSessions(target: NasTarget): List<RadiusSessionReading>
}

/**
 * Memilih adapter BRAS sesuai vendor.
 *
 * Bila [fallback] terisi (mode simulator), vendor tak dikenal memakainya alih-alih
 * menghasilkan `null` — sehingga satu simulator bisa memerankan BRAS vendor apa pun
 * tanpa perlu adapter khusus per vendor. Di produksi [fallback] `null`: BRAS dengan
 * vendor yang adapternya belum ada dilewati (dilaporkan lewat log), bukan ditebak.
 */
class BngAdapterRegistry(adapters: List<BngAdapter>, private val fallback: BngAdapter? = null) {

    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): BngAdapter? = byVendor[vendor.uppercase()] ?: fallback

    val supportedVendors: Set<String> get() = byVendor.keys
}
