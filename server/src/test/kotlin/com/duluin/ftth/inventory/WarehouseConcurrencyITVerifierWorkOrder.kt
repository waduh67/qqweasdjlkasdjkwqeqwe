package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand
import com.duluin.ftth.iam.application.port.inbound.UpdateRoleCommand
import com.duluin.ftth.iam.application.service.RoleService
import com.duluin.ftth.iam.application.service.UserService
import com.duluin.ftth.workorder.application.service.WorkOrderService
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITVerifierWorkOrder {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    @ParameterizedTest @ValueSource(strings=["CREATE","UPDATE_OLD","UPDATE_NEW","UPDATE_CLEAR","ASSIGN","START","AUTHORIZE_COMPLETE","CANCEL","OPTICAL","APPROVE","REJECT","DELETE"])
    fun `WO mutations check existing and destination areas against fenced scope`(action: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val allowed=UUID.randomUUID(); val denied=UUID.randomUUID(); val role=UUID.randomUUID()
        fixture.transaction {
            sql("INSERT INTO area(id,tenant_id,code,name) VALUES ('$allowed','$tenant','A','Allowed'),('$denied','$tenant','B','Denied')")
            sql("INSERT INTO role(id,tenant_id,name) VALUES ('$role','$tenant','Teknisi')")
            sql("INSERT INTO role_permission(role_id,permission_id) SELECT '$role',id FROM permission WHERE code LIKE 'workorder.order.%'")
            sql("INSERT INTO user_role(user_id,role_id) VALUES ('$actor','$role')")
            sql("INSERT INTO user_area(user_id,area_id) VALUES ('$actor','$allowed')")
            sql("UPDATE app_user SET platform_admin=false WHERE id='$actor'")
            sql("UPDATE work_order SET area_id='${if(action in setOf("UPDATE_NEW","UPDATE_CLEAR")) allowed else denied}' WHERE id='$workOrder'")
        }
        SecurityContextHolder.getContext().authentication=JwtAuthenticationConverter().convert(
            Jwt.withTokenValue("stale-area-jwt").header("alg","RS256").subject(fixture.actor.toString()).claim("tid",fixture.tenant.toString())
                .claim("email","actor@example.test").claim("name","Actor").claim("padm",false)
                .claim("areas",listOf(allowed.toString(),denied.toString())).claim("perms",listOf("workorder.order.update","workorder.order.field")).build())
        val service=context.getBean(WorkOrderService::class.java)
        val before=fixture.transaction { scalar("SELECT status||':'||warehouse_revision||':'||area_id FROM work_order WHERE id='$workOrder'") }
        assertThatThrownBy { fixture.transaction {
            when(action) {
                "CREATE" -> service.create(com.duluin.ftth.workorder.application.port.inbound.SaveWorkOrderCommand(
                    com.duluin.ftth.workorder.domain.model.WorkOrderType.REPAIR,"New",null,com.duluin.ftth.workorder.domain.model.WorkOrderPriority.NORMAL,null,null,null,denied,null))
                "UPDATE_OLD","UPDATE_NEW","UPDATE_CLEAR" -> service.update(workOrder,com.duluin.ftth.workorder.application.port.inbound.UpdateWorkOrderCommand(
                    "Changed",null,com.duluin.ftth.workorder.domain.model.WorkOrderPriority.NORMAL,null,null,
                    when(action) { "UPDATE_OLD" -> allowed; "UPDATE_CLEAR" -> null; else -> denied },null))
                "ASSIGN" -> service.assign(workOrder,setOf(actor))
                "START" -> service.start(workOrder)
                "AUTHORIZE_COMPLETE" -> service.authorizeComplete(workOrder)
                "CANCEL" -> service.cancel(workOrder,"Cancel")
                "OPTICAL" -> service.recordOptical(workOrder,com.duluin.ftth.workorder.application.port.inbound.RecordOpticalCommand(null,null))
                "APPROVE" -> service.approve(workOrder,null)
                "REJECT" -> service.reject(workOrder,"Reject")
                "DELETE" -> service.delete(workOrder)
                else -> error("Unknown test action")
            }
        } }.isInstanceOf(AccessDeniedException::class.java)
        fixture.transaction {
            assertThat(scalar("SELECT status||':'||warehouse_revision||':'||area_id FROM work_order WHERE id='$workOrder'")).isEqualTo(before)
            assertThat(scalar("SELECT count(*) FROM work_order")).isEqualTo("1")
        }
    }

    @ParameterizedTest @ValueSource(strings=["AREA","DISPATCHER"])
    fun `AV6-01 original JWT cannot retain revoked WO area or dispatcher rights`(mode: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val role=UUID.randomUUID(); val area=UUID.randomUUID()
        fixture.transaction {
            sql("INSERT INTO area(id,tenant_id,code,name) VALUES ('$area','$tenant','A','Area A')")
            sql("INSERT INTO role(id,tenant_id,name) VALUES ('$role','$tenant','Teknisi')")
            sql("INSERT INTO role_permission(role_id,permission_id) SELECT '$role',id FROM permission WHERE code IN ('workorder.order.field','workorder.order.update')")
            sql("INSERT INTO user_role(user_id,role_id) VALUES ('$actor','$role')")
            sql("INSERT INTO user_area(user_id,area_id) VALUES ('$actor','$area')")
            sql("UPDATE app_user SET platform_admin=false WHERE id='$actor'")
            sql("UPDATE work_order SET status='ASSIGNED',area_id='$area' WHERE id='$workOrder'")
            if(mode=="DISPATCHER") sql("DELETE FROM work_order_assignee WHERE work_order_id='$workOrder'")
        }
        val original=Jwt.withTokenValue("original-authorized-jwt").header("alg","RS256").subject(fixture.actor.toString())
            .claim("tid",fixture.tenant.toString()).claim("email","actor@example.test").claim("name","Actor")
            .claim("padm",false).claim("perms",listOf("workorder.order.field","workorder.order.update"))
            .claim("areas",listOf(area.toString())).build()
        fixture.authenticateLegacy(UUID.randomUUID())
        fixture.transaction {
            if(mode=="AREA") context.getBean(UserService::class.java).assignAccess(actor,AssignAccessCommand(setOf(role),emptySet()))
            else {
                val field=UUID.fromString(scalar("SELECT id FROM permission WHERE code='workorder.order.field'"))
                context.getBean(RoleService::class.java).update(role,UpdateRoleCommand("Teknisi",null,setOf(field)))
            }
        }
        SecurityContextHolder.getContext().authentication=JwtAuthenticationConverter().convert(original)
        fixture.transaction {
            val current=context.getBean(com.duluin.ftth.iam.CurrentAuthorityApi::class.java).lockCurrent()
            if(mode=="AREA") assertThat(current.areaScope).isEqualTo(com.duluin.ftth.common.security.AuthorityScope.Restricted(emptySet()))
            else assertThat(current.permissions).contains("workorder.order.field").doesNotContain("workorder.order.update")
        }
        val before=fixture.transaction { scalar("SELECT status||':'||warehouse_revision FROM work_order WHERE id='$workOrder'") }
        assertThatThrownBy { fixture.transaction { context.getBean(WorkOrderService::class.java).start(workOrder) } }.isInstanceOf(AccessDeniedException::class.java)
        assertThat(fixture.transaction { scalar("SELECT status||':'||warehouse_revision FROM work_order WHERE id='$workOrder'") }).isEqualTo(before)
    }
}
