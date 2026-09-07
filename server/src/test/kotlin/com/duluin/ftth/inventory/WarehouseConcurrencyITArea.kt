package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.CreateAreaCommand
import com.duluin.ftth.iam.application.port.inbound.UpdateAreaCommand
import com.duluin.ftth.iam.application.service.AreaService
import com.duluin.ftth.common.infrastructure.security.FtthAuthenticationToken
import com.duluin.ftth.common.security.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder

class WarehouseConcurrencyITArea {
    @Test fun `area create update and delete use a single committed epoch`() {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val fixture = WarehousePostingFixture(context)
            SecurityContextHolder.getContext().authentication = FtthAuthenticationToken(
                AuthenticatedUser(fixture.actor, fixture.tenant, "actor@example.test", "Actor", false, emptySet(), emptySet()))
            try {
                val before = fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }
                fixture.transaction {
                    val areas = context.getBean(AreaService::class.java)
                    val area = areas.create(CreateAreaCommand("NORTH", "North", null))
                    areas.update(area.id, UpdateAreaCommand("North updated", null))
                    areas.delete(area.id)
                }
                assertThat(fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(before + 1)
            } finally { SecurityContextHolder.clearContext() }
        } }
    }
}
