package com.duluin.ftth.bng

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik module bng untuk module lain (gis, saat memperkaya telusur jalur
 * dengan hop BRAS: identitas jaringan pelanggan + keadaan sesi PPPoE terkininya).
 *
 * Berbeda dari [com.duluin.ftth.bng.application.port.inbound.ViewBngSessionUseCase]
 * yang melayani UI module bng sendiri (per-akun), kontrak ini menjawab per-PELANGGAN
 * dan hanya membocorkan yang perlu lintas-module — tak pernah rahasia (password PPPoE
 * / secret CoA).
 */
interface BngApi {

    /**
     * Identitas jaringan + sesi PPPoE terkini seorang pelanggan. Bila pelanggan punya
     * beberapa akun, yang sesinya online didahulukan lalu yang pertama. `null` bila
     * pelanggan belum punya akun jaringan sama sekali (belum diprovisi PPPoE).
     */
    fun findSubscriberSession(customerId: UUID): SubscriberSessionRef?
}

/**
 * Pandangan lintas-module identitas jaringan + sesi terkini seorang pelanggan. Tanpa
 * rahasia apa pun (password PPPoE / secret CoA tak pernah keluar). Waktu semuanya UTC;
 * UI yang menyesuaikan zona.
 */
data class SubscriberSessionRef(
    val subscriberAccessId: UUID,
    val username: String,
    /** Status akun jaringan: ACTIVE/ISOLATED/TERMINATED. */
    val accessStatus: String,
    /**
     * Nama paket (katalog) yang menempel pada akun; `null` bila paket telah dinonaktifkan/
     * terhapus. Nama field dipertahankan (bukan `planName`) demi kestabilan konsumen gis/web.
     */
    val rateProfileName: String?,
    val online: Boolean,
    val framedIp: String?,
    val nasId: UUID?,
    val nasName: String?,
    val nasIp: String?,
    val uptimeSeconds: Long?,
    val startedAt: Instant?,
    /** Kapan terakhir BRAS melaporkan akun ini; `null` bila belum pernah terpantau. */
    val lastSeenAt: Instant?,
)
