package com.duluin.ftth.order

import java.time.Instant
import java.util.UUID

/*
 * KENAPA DUA EVENT INI TINGGAL DI PAKET `order`, BUKAN DI PAKET `fulfillment` YANG MENERBITKANNYA.
 *
 * Kebiasaan repo ini menaruh event di paket module PENERBITNYA (lihat `workorder.FulfillmentApproved`
 * yang dikonsumsi module fulfillment). Di sini kebiasaan itu SENGAJA dilanggar, dan alasannya
 * keras: module fulfillment SUDAH bergantung pada module order — `PublicApiFulfillmentEffectExecutor`
 * memanggil `OrderApi.applyFulfillment` untuk efek ORDER. Kalau event-nya ditaruh di paket
 * fulfillment, module order harus meng-import balik paket fulfillment untuk mendengarkannya, dan
 * ApplicationModules.verify() langsung menjatuhkan build dengan "Cycle detected: fulfillment ->
 * order -> fulfillment".
 *
 * Menaruhnya di sini membuat panahnya tetap SATU ARAH (fulfillment -> order) untuk dua-duanya:
 * pemanggilan API maupun event. Module order tidak perlu tahu module fulfillment ada; ia hanya
 * mengumumkan bentuk sinyal yang bersedia ia dengar tentang pesanannya sendiri, dan siapa pun yang
 * mengurus pemenuhan pesanan boleh menerbitkannya.
 */

/**
 * Pemenuhan sebuah PESANAN macet dan butuh campur tangan manusia — di implementasi sekarang berarti
 * saga fulfillment mendarat di `REQUIRES_RECONCILIATION`.
 *
 * KENAPA ini perlu diumumkan: tanpa sinyal ini pesanannya tampak normal di layar operator —
 * statusnya masih ACCEPTED/SCHEDULED seperti ratusan pesanan lain — sementara pemenuhannya berhenti
 * dan tak ada satu pun yang bergerak. Kegagalannya baru ketahuan saat pelanggan menelepon, biasanya
 * berhari-hari kemudian.
 *
 * [orderId] SENGAJA non-null: sinyal ini hanya untuk pemenuhan yang benar-benar bertaut pesanan.
 * Saga migrasi dan saga langganan murni tidak punya pesanan untuk ditandai, dan menerbitkan event
 * dengan `orderId` null hanya memindahkan pengecekannya ke setiap pendengar.
 *
 * [outcome] adalah pesan kegagalan INTERNAL ("ORDER_EFFECT_REJECTED", dsb). Ia TIDAK BOLEH sampai
 * ke halaman lacak pelanggan — kalimat untuk pelanggan disusun module order sendiri.
 *
 * [namespace] + [operationKey] mengidentifikasi operasi yang macet, dan dipakai penerima sebagai
 * rujukan penanda supaya pelepasannya nanti bisa dipasangkan ke pemasangannya.
 */
data class OrderFulfillmentStalled(
    val tenantId: UUID,
    val orderId: UUID,
    val namespace: String,
    val operationKey: String,
    val outcome: String?,
    val occurredAt: Instant,
)

/** Kemacetan [OrderFulfillmentStalled] sudah diselesaikan manusia; penanda yang dipasang sistem karenanya boleh dilepas. */
data class OrderFulfillmentStallResolved(
    val tenantId: UUID,
    val orderId: UUID,
    val namespace: String,
    val operationKey: String,
    val occurredAt: Instant,
)
