package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.security.SessionIdentity
import com.duluin.ftth.fieldservice.VisitRef
import com.duluin.ftth.fieldservice.domain.model.VisitState
import com.duluin.ftth.iam.DeliveryAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderFulfillmentApi
import com.duluin.ftth.workorder.WorkOrderFulfillmentCommand
import com.duluin.ftth.workorder.WorkOrderSettlementApi
import java.util.UUID

internal class FirstFulfillmentForgery(private val fixture: WarehousePostingFixture, private val workOrderId: UUID, private val actorId: UUID) {
    fun insert(missingVisit: Boolean, handoff: Boolean = false, fakeVisitOwner: Boolean = false): FrozenFulfillment = with(fixture) {
        check(scalar("SELECT current_user") == "warehouse_app")
        check(scalar("SELECT count(*) FROM fulfillment_approval_snapshot WHERE work_order_id='$workOrderId'") == "0")
        val cutovers = context.getBean(InventoryTenantCutoverApi::class.java)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = context.getBean(DeliveryAuthorityApi::class.java).lockActor(SessionIdentity(tenant, actorId, null))
        sql("UPDATE work_order SET approval_status='APPROVED',approved_by='$actorId',approved_at=clock_timestamp() WHERE id='$workOrderId'")
        val approved = context.getBean(WorkOrderSettlementApi::class.java).lockApproved(workOrderId, current)
        val job = approved.material
        val materialContext = MaterialPlanningContext(job.workOrderId, job.code, job.workType, job.action.name, job.workOrderRevision,
            job.customerId, job.areaId, job.activeAssigneeIds, current.fence, cutover)
        val inventory = context.getBean(InventorySettlementApi::class.java)
        val material = inventory.freeze(materialContext)
        val effects = buildSet {
            add(FulfillmentEffectType.INVENTORY); add(FulfillmentEffectType.WORK_ORDER)
            if (missingVisit) add(FulfillmentEffectType.VISIT)
            if (job.orderId != null) add(FulfillmentEffectType.ORDER)
        }
        val snapshot = FulfillmentApprovalSnapshot(UUID.randomUUID(), current.fence.identity, cutover.snapshot.epoch, approved,
            material, effects, job.orderId?.let { context.getBean(com.duluin.ftth.order.OrderApi::class.java).fulfillmentRevision(it) },
            if (missingVisit) VisitRef(tenant, UUID.randomUUID(), job.orderId ?: UUID.randomUUID(), workOrderId,
                job.activeAssigneeIds.single(), VisitState.CHECKED_OUT, 0, null) else null, null, null, null)
        val frozen = context.getBean(FulfillmentApprovalStore::class.java).save(snapshot, "first-forgery:${snapshot.id}")
        val request = frozen.request
        val checkpoints = context.getBean(FulfillmentCheckpointRepository::class.java)
        val checkpoint = checkpoints.claimOrCreate(request)
        if (job.orderId != null) {
            sql("SELECT set_config('app.fulfillment_approval_id','${snapshot.id}',true)")
            sql("""UPDATE order_record SET status='FULFILLED',revision=revision+1,last_actor_id='$actorId',
                last_operation_namespace='${request.namespace}',last_operation_key='${request.operationKey}',last_operation_hash='${request.canonicalHash}' WHERE id='${job.orderId}'""")
            sql("""INSERT INTO order_operation(id,tenant_id,namespace,operation_key,payload_hash,outcome_json)
                SELECT '${UUID.randomUUID()}','$tenant','${request.namespace}','${request.operationKey}','${request.canonicalHash}',
                    jsonb_build_object('id',id,'tenantId',tenant_id,'customerId',customer_id,'status',status,'revision',revision)::text
                FROM order_record WHERE id='${job.orderId}'""")
        }
        if (fakeVisitOwner) {
            val visit = requireNotNull(snapshot.visit)
            val operation = UUID.randomUUID()
            sql("""INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active)
                VALUES ('${visit.id}','$tenant','${visit.orderId}','$workOrderId','${visit.technicianId}','SUBMITTED',1,true)""")
            sql("""INSERT INTO fieldservice_visit_operation(id,tenant_id,visit_id,namespace,operation_key,payload_hash,result)
                VALUES ('$operation','$tenant','${visit.id}','${request.namespace}','${request.operationKey}','${request.canonicalHash}','SUBMITTED')""")
            sql("""INSERT INTO fieldservice_fulfillment_receipt(id,tenant_id,visit_id,operation_id,actor_id,namespace,operation_key,payload_hash,
                source_revision,result_revision,source_state,result_state) VALUES ('${snapshot.id}','$tenant','${visit.id}','$operation','${visit.technicianId}',
                    '${request.namespace}','${request.operationKey}','${request.canonicalHash}',0,1,'CHECKED_OUT','SUBMITTED')""")
        }
        inventory.verify(materialContext, MaterialSettlementApproval(snapshot.id, request.canonicalHash, material))
        context.getBean(WorkOrderFulfillmentApi::class.java).recordFulfillmentResult(WorkOrderFulfillmentCommand(tenant,
            workOrderId, request.namespace, request.operationKey, request.canonicalHash, request.source.name, "APPLIED"))
        for (effect in effects) {
            checkpoints.markEffectStarted(tenant, request.namespace, request.operationKey, effect, java.time.Instant.now())
            checkpoints.markEffectCompleted(tenant, request.namespace, request.operationKey, effect, java.time.Instant.now())
        }
        checkpoints.save(checkpoint.copy(state = FulfillmentState.APPLIED, outcome = "APPLIED"))
        if (handoff) checkpoints.enqueueOutbox(checkpoint)
        frozen
    }

    fun holdOwnerValidator(scope: String) = with(fixture) {
        jdbc { connection ->
            val manager = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(
                context.getBean(jakarta.persistence.EntityManagerFactory::class.java))
            requireNotNull(manager).flush()
            val constraints = connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT DISTINCT quote_ident(t.tgname) FROM pg_trigger t JOIN pg_class relation ON relation.oid=t.tgrelid
                    WHERE relation.relnamespace=current_schema()::regnamespace AND t.tgdeferrable AND NOT t.tgisinternal
                        AND t.tgname NOT LIKE 'warehouse_fulfillment_owner_%'""").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
            constraints.forEach { sql("SET CONSTRAINTS $it IMMEDIATE") }
        }
        when (scope) {
            "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
            "mismatched" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
            "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
            "normal" -> Unit
            else -> error("Unknown tenant timing")
        }
    }
}
