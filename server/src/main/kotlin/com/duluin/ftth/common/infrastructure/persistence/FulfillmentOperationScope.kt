package com.duluin.ftth.common.infrastructure.persistence

import com.duluin.ftth.common.domain.FulfillmentEffectReference
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class FulfillmentOperationScope(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enter(reference: FulfillmentEffectReference) {
        entityManager.createNativeQuery("SELECT set_config('app.fulfillment_approval_id',:id,true)")
            .setParameter("id",reference.approvalId.toString()).singleResult
    }
}
