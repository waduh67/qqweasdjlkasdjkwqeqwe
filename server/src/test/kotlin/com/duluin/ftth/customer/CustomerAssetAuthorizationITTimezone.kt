package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerAssetAuthorizationITTimezone : CustomerAssetAuthorizationTimezoneFixture() {
    @Test
    fun `New York authorization validates in UTC without changing stored history`() {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val snapshot = fixture.createTimed(id)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'America/New_York'")
            assertThat(scalar("SELECT (warehouse_read_deployment_authorization('$tenant','$id')).id::text")).isEqualTo(id.toString())
        }
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'UTC'")
            val values = scalar("""SELECT concat_ws('|',history.snapshot->>'created_at',to_jsonb(permit)->>'created_at',
                ((history.snapshot->>'created_at')::timestamptz=permit.created_at)::text,
                ((history.snapshot-'created_at')=(to_jsonb(permit)-'created_at'))::text)
                FROM inventory_deployment_authorization permit JOIN inventory_deployment_authorization_history history
                    ON history.tenant_id=permit.tenant_id AND history.authorization_id=permit.id WHERE permit.id='$id'""")
            println("T19-AV-02 read stored|current|sameInstant|otherFieldsEqual=$values")
            assertThat(values).isEqualTo("2026-07-20T10:00:00-04:00|2026-07-20T14:00:00+00:00|true|true")
            assertThat(scalar("SELECT (warehouse_read_deployment_authorization('$tenant','$id')).id::text")).isEqualTo(id.toString())
            assertThat(scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo(snapshot)
        }
    }

    @Test
    fun `harmless source revalidation is independent of the creator timezone`() {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val snapshot = fixture.createTimed(id)
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'America/New_York'")
            sql("UPDATE inventory_serialized_asset SET revision=revision+1 WHERE id='${fixture.asset}'")
        }
        fixture.stock.transaction {
            sql("SET LOCAL TIME ZONE 'UTC'")
            println("T19-AV-02 source update creator=America/New_York validator=UTC same physical asset")
            sql("UPDATE inventory_serialized_asset SET revision=revision+1 WHERE id='${fixture.asset}'")
            sql("SET CONSTRAINTS warehouse_authorization_source_final IMMEDIATE")
        }
        fixture.stock.transaction {
            assertThat(scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo(snapshot)
        }
    }
}
