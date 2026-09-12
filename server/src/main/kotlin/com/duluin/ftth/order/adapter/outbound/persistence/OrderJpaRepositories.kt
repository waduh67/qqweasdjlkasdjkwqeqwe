package com.duluin.ftth.order.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.util.UUID

interface OrderJpaRepository : JpaRepository<OrderJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForFulfillmentById(id: UUID): OrderJpaEntity?

    /**
     * Pesanan MILIK seorang pelanggan — termasuk pesanan yang dulu dibuat saat ia masih calon
     * pelanggan.
     *
     * `order_record.customer_id` SENGAJA `updatable = false`, jadi pesanan yang lahir dari
     * prospek tetap menunjuk `lead_id` selamanya meski orangnya sudah dipromosikan jadi
     * pelanggan. Tanpa cabang kedua di bawah, pelanggan yang pertama kali memesan lewat pintu
     * publik membuka portalnya dan melihat riwayat KOSONG — persis pesanan yang membuatnya
     * jadi pelanggan yang hilang dari daftarnya sendiri.
     */
    @Query(
        """SELECT o FROM OrderJpaEntity o
           WHERE o.customerId = :customerId
              OR o.leadId IN (SELECT l.id FROM OrderLeadJpaEntity l WHERE l.convertedCustomerId = :customerId)
           ORDER BY o.id""",
    )
    fun findAllOwnedBy(@Param("customerId") customerId: UUID): List<OrderJpaEntity>

    /** Satu pesanan milik pelanggan tertentu; kepemilikannya ditegakkan di query, bukan di memori. */
    @Query(
        """SELECT o FROM OrderJpaEntity o
           WHERE o.id = :orderId
             AND (o.customerId = :customerId
                  OR o.leadId IN (SELECT l.id FROM OrderLeadJpaEntity l WHERE l.convertedCustomerId = :customerId))""",
    )
    fun findOwnedBy(@Param("customerId") customerId: UUID, @Param("orderId") orderId: UUID): OrderJpaEntity?
}
interface OrderLineJpaRepository : JpaRepository<OrderLineJpaEntity, UUID> {
    fun findAllByOrderIdOrderById(orderId: UUID): List<OrderLineJpaEntity>
    fun deleteAllByOrderId(orderId: UUID)
}
interface OrderOperationJpaRepository : JpaRepository<OrderOperationJpaEntity, UUID> {
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, operationKey: String): OrderOperationJpaEntity?
}
interface OrderLeadJpaRepository : JpaRepository<OrderLeadJpaEntity, UUID> {
    fun findAllByPhoneOrderByCreatedAtDesc(phone: String): List<OrderLeadJpaEntity>
}
