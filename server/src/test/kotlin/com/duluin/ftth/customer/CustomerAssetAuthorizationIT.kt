package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerAssetAuthorizationIT : CustomerAssetAuthorizationFixture() {
    @Test
    fun `INSTALL cannot bind another physical asset to an acknowledged issue line`() {
        val fixture = episodeCase()
        val other = fixture.stock.transaction {
            UUID.fromString(scalar("SELECT id FROM inventory_serialized_asset WHERE id<>'${fixture.asset}' LIMIT 1"))
        }
        val id = UUID.randomUUID()
        val failure = rejection { fixture.stock.transaction { sql(fixture.authorization(id, targetAsset = other)) } }
        assertThat(failure.message).contains("authorization issue identity")
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo("0")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CONFLICT", "LEGACY_RESERVED"])
    fun `ordinary VERIFIED REMOVE cannot authorize unresolved physical origin`(claimState: String) {
        val fixture = episodeCase()
        val legacy = fixture.stageLegacy(claimState)
        val id = UUID.randomUUID()
        val failure = rejection { fixture.stock.transaction { sql(fixture.authorization(id, "REMOVE", legacy)) } }
        assertThat(failure.message).contains("authorization requires VERIFIED physical asset")
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='$id'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo("0")
        }
    }

    @Test
    fun `valid real acknowledged issue authorization commits atomically with exact history`() {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.authorization(id)) }
        fixture.stock.transaction {
            assertThat(scalar("""SELECT count(*) FROM inventory_deployment_authorization permit
                JOIN inventory_deployment_authorization_history history ON history.tenant_id=permit.tenant_id
                    AND history.authorization_id=permit.id AND history.revision=permit.revision
                WHERE permit.id='$id' AND history.snapshot=to_jsonb(permit) AND NOT permit.consumed""")).isEqualTo("1")
        }
    }
}
