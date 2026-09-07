package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

internal fun WarehousePostingFixture.legacyAuthority() {
    transaction {
        sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash,platform_admin) VALUES ('$actor','$tenant','$actor@example.test','Actor','unused',true)")
        sql("INSERT INTO work_order(id,tenant_id,code,type,title,status,customer_id,created_by) VALUES ('$workOrder','$tenant','LEGACY-WO','REPAIR','Legacy','IN_PROGRESS','$customer','$actor')")
        sql("INSERT INTO work_order_assignee(id,tenant_id,work_order_id,technician_id,assigned_at) VALUES ('${UUID.randomUUID()}','$tenant','$workOrder','$actor',now())")
    }
    authenticateLegacy()
}

internal fun WarehousePostingFixture.authenticateLegacy(user: UUID = actor) {
    if (user != actor) transaction {
        sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash,platform_admin) VALUES ('$user','$tenant','$user@example.test','Admin','unused',true) ON CONFLICT (id) DO NOTHING")
    }
    SecurityContextHolder.getContext().authentication = JwtAuthenticationConverter().convert(
        Jwt.withTokenValue("original-jwt").header("alg", "RS256").subject(user.toString()).claim("tid", tenant.toString())
            .claim("email", "$user@example.test").claim("name", "Actor").claim("padm", true).claim("perms", listOf("workorder.order.field")).build())
}
