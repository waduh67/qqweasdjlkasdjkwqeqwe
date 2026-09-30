package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

abstract class CustomerAssetAuthorizationFixture : CustomerAssetEpisodeFixture() {
    internal fun EpisodeCase.authorization(id: UUID, purpose: String = "INSTALL", targetAsset: UUID = asset): String {
        val issue = if (purpose == "REMOVE") "NULL" else "'$issueLine'"
        val issueRevision = if (purpose == "REMOVE") "NULL" else
            "(SELECT document.revision FROM inventory_issue_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.issue_id WHERE line.tenant_id=asset.tenant_id AND line.id='$issueLine')"
        return """
            INSERT INTO inventory_deployment_authorization(id,tenant_id,asset_id,issue_line_id,work_order_id,customer_id,
                actor_id,purpose,ownership_mode,operation_id,expected_asset_revision,expected_work_order_revision,
                expected_plan_revision,expected_issue_revision,authority_epoch,cutover_epoch,previous_assignment_id,expected_assignment_revision)
            SELECT '$id',asset.tenant_id,asset.id,$issue,'$workOrder','$customerA','$actor','$purpose','LOAN','${UUID.randomUUID()}',
                asset.revision,(SELECT warehouse_revision FROM work_order WHERE tenant_id=asset.tenant_id AND id='$workOrder'),
                1,$issueRevision,(SELECT epoch FROM iam_authorization_epoch WHERE tenant_id=asset.tenant_id),
                (SELECT epoch FROM inventory_tenant_cutover WHERE tenant_id=asset.tenant_id),NULL,NULL
            FROM inventory_serialized_asset asset WHERE asset.tenant_id='${stock.tenant}' AND asset.id='$targetAsset'
        """.trimIndent()
    }

    internal fun EpisodeCase.stageLegacy(state: String): UUID {
        val legacy = UUID.randomUUID()
        owner { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='${stock.tenant}'")
                statement.execute("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,status,location_id,
                    custody_owner_id,custody_owner_kind,warehouse_admission,canonical_serial_candidate)
                    SELECT '$legacy',tenant_id,sku_id,'$legacy','RETURNED',location_id,custody_owner_id,custody_owner_kind,
                        'LEGACY_UNRESOLVED',upper('$legacy') FROM inventory_serialized_asset WHERE id='$asset'""")
                statement.execute("""INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
                    VALUES ('${UUID.randomUUID()}','${stock.tenant}','SERIAL',upper('$legacy'),'$state')""")
            }
            connection.commit()
        }
        return legacy
    }

    internal fun owner(action: (java.sql.Connection) -> Unit) {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        DriverManager.getConnection(url, requireNotNull(System.getenv("SPRING_FLYWAY_USER")),
            requireNotNull(System.getenv("SPRING_FLYWAY_PASSWORD"))).use(action)
    }

    internal fun rejection(action: () -> Unit): SQLException = generateSequence<Throwable>(assertThrows<Exception> { action() }) { it.cause }
        .filterIsInstance<SQLException>().first().also { assertThat(it.sqlState).isEqualTo("23514") }
}
