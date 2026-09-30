package com.duluin.ftth.fulfillment

import com.duluin.ftth.bng.adapter.outbound.persistence.BngFulfillmentReceiptStore
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.AopTestUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class WarehouseFulfillmentITBngExactSet : BngHandoffFixture() {
    @Test fun `duplicate receipt ids cannot conceal an omitted correlated action at initial insert`() {
        val (token,workOrder)=preparedHandoff()
        val target=AopTestUtils.getUltimateTargetObject<BngFulfillmentReceiptStore>(context.getBean(BngFulfillmentReceiptStore::class.java))
        val field=BngFulfillmentReceiptStore::class.java.getDeclaredField("entityManager").apply { isAccessible=true }
        val original=EntityManager::class.java.cast(field.get(target))
        val injected=AtomicBoolean()
        val manager=Proxy.newProxyInstance(EntityManager::class.java.classLoader,arrayOf(EntityManager::class.java)) { _,method,arguments ->
            val sql=arguments?.firstOrNull()?.toString().orEmpty()
            val values=if(method.name=="createNativeQuery" && sql.startsWith("INSERT INTO bng_fulfillment_receipt")) {
                injected.set(true)
                val primary="(SELECT id FROM bng_action WHERE fulfillment_approval_id=:id AND fulfillment_xid=pg_current_xact_id() AND action='PROVISION')"
                arrayOf(sql.replace("CAST(:actions AS uuid[])","CASE WHEN cardinality(CAST(:actions AS uuid[]))=2 THEN ARRAY[$primary,$primary] ELSE CAST(:actions AS uuid[]) END"))
            } else arguments ?: emptyArray()
            try { method.invoke(original,*values) } catch(failure: InvocationTargetException) { throw failure.targetException }
        }

        field.set(target,manager)
        try {
            assertThat(request("POST","/api/work-orders/$workOrder/approve",token,"{}").status).isEqualTo(200)
        } finally { field.set(target,original) }

        assertThat(injected.get()).isTrue()
        fixture(token).transaction {
            val state=scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='$workOrder'")
            println("T17-AV-3 exact-set duplicate initial receipt state=$state")
            assertThat(state).isEqualTo("REQUIRES_RECONCILIATION")
            assertThat(scalar("SELECT count(*) FROM bng_fulfillment_receipt")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM bng_action WHERE fulfillment_approval_id IS NOT NULL")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM subscriber_access")).isEqualTo("PENDING")
        }
    }
}
