package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerAssetOwnershipIT : CustomerAssetOwnershipIntegrityCases() {
    @Test
    fun `public inventory owner accepts a signed loan without transferring title`() {
        val case = ownershipCase()

        val accepted = authenticated(case.installation) {
            context.getBean(com.duluin.ftth.inventory.InventoryDeploymentApi::class.java).acceptHandover(
                com.duluin.ftth.inventory.AcceptAssetHandoverRequest(case.installation.operation, 0, case.signature),
                com.duluin.ftth.inventory.WarehouseMutationMetadata("owner-accept"))
        }

        assertThat(accepted.legalOwner).isEqualTo(com.duluin.ftth.inventory.AssetLegalOwner.ISP)
        assertThat(accepted.handoverState).isEqualTo(com.duluin.ftth.inventory.AssetHandoverState.ACCEPTED)
    }

    @Test
    fun `default loan stays ISP without handover when installation commits`() {
        val case = ownershipCase()

        val actual = title(case)

        assertThat(actual).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }

    @Test
    fun `sale stays ISP without handover when installation commits`() {
        val case = ownershipCase("SALE")

        val actual = title(case)

        assertThat(actual).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }

    @Test
    fun `loan retains ISP title and one recovery obligation when handover is accepted`() {
        val case = ownershipCase()

        val accepted = accept(case)

        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
        fixture(case.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_recovery_obligation WHERE assignment_id='${case.installation.operation}'"))
                .isEqualTo("1")
        }
    }

    @Test
    fun `sale transfers existing installed asset to CUSTOMER only when handover is accepted`() {
        val case = ownershipCase("SALE")
        assertThat(title(case)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|0|0")

        val accepted = accept(case)

        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
        fixture(case.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_recovery_obligation WHERE assignment_id='${case.installation.operation}'"))
                .isEqualTo("0")
            assertThat(scalar("""SELECT count(*) FROM inventory_movement_leg leg JOIN inventory_movement movement
                ON movement.id=leg.movement_id WHERE movement.kind='TITLE_TRANSFER'
                AND leg.stock_identity_id='${case.installation.receipt.input.lines.single().stockIdentityId}'
                AND leg.quantity_base=1 AND leg.base_unit='EA' AND leg.status='CUSTOMER_INSTALLED'""")).isEqualTo("2")
        }
    }

    @Test
    fun `duplicate handover returns the exact original response when the key and payload match`() {
        val case = ownershipCase("SALE")
        val original = accept(case)
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        val before = title(case)

        val replay = accept(case)

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(original.contentAsString)
        assertThat(title(case)).isEqualTo(before)
    }

    @Test
    fun `retroactive ownership mode edit rejects when assignment already exists`() {
        val case = ownershipCase()

        assertThrows<Exception> {
            fixture(case.installation.receipt.stock.token).transaction {
                sql("UPDATE inventory_asset_assignment SET ownership_mode='SALE',revision=revision+1 WHERE id='${case.installation.operation}'")
            }
        }

        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }

    @ParameterizedTest
    @ValueSource(strings = ["OWNER", "DIGEST", "OBLIGATION_DELETE", "OBLIGATION_SUBSTITUTE", "EXTRA_POSTING"])
    fun `application role rejects tampering with accepted title and exact recovery linkage`(change: String) {
        val case = ownershipCase()
        val accepted = accept(case)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val handover = mapper.readTree(accepted.contentAsString).path("acceptedHandover").path("handoverId").asString()

        assertThrows<Exception> {
            fixture(case.installation.receipt.stock.token).transaction {
                when (change) {
                    "OWNER" -> sql("UPDATE inventory_serialized_asset SET legal_owner='CUSTOMER',revision=revision+1 WHERE id='${case.installation.receipt.input.lines.single().stockIdentityId}'")
                    "DIGEST" -> sql("UPDATE inventory_asset_acceptance SET evidence_digest=repeat('0',64) WHERE handover_id='$handover'")
                    "OBLIGATION_DELETE" -> sql("DELETE FROM inventory_asset_recovery_obligation WHERE handover_id='$handover'")
                    "OBLIGATION_SUBSTITUTE" -> sql("UPDATE inventory_asset_recovery_obligation SET customer_id='${UUID.randomUUID()}' WHERE handover_id='$handover'")
                    "EXTRA_POSTING" -> sql("""INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,
                        reason,server_received_at,kind,state,document_id,document_revision,operation_id)
                        SELECT '${UUID.randomUUID()}',tenant_id,namespace,operation_key,payload_hash,actor_id,'Fabricated title posting',
                        created_at,'TITLE_TRANSFER','APPLIED',document_id,document_revision,id FROM inventory_operation
                        WHERE id=(SELECT operation_id FROM inventory_asset_acceptance WHERE handover_id='$handover')""")
                    else -> error("Unknown change")
                }
            }
        }

        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `sale acceptance writes no commercial rows and creates no additional physical asset`() {
        val case = ownershipCase("SALE")
        val stock = fixture(case.installation.receipt.stock.token)
        val before = stock.transaction { scalar("SELECT count(*) FROM inventory_serialized_asset") }

        val accepted = accept(case)

        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo(before)
            assertThat(scalar("SELECT count(*) FROM invoice")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM payment")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM refund")).isEqualTo("0")
            assertThat(scalar("""SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id=
                '${case.installation.receipt.input.lines.single().stockIdentityId}' AND status='AVAILABLE' AND quantity_base>0""")).isEqualTo("0")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["actorId", "tenantId", "customerId", "legalOwner", "ownershipMode", "acceptedAt"])
    fun `authority fields reject before replay lookup`(field: String) {
        val case = ownershipCase("SALE")
        assertThat(accept(case).status).isEqualTo(200)

        val rejected = request("POST", "/api/customers/${case.installation.customer}/assets/handover", case.installation.receipt.receiver.first,
            """{"assignmentId":"${case.installation.operation}","expectedRevision":0,"evidenceId":"${case.signature}","$field":"SALE"}""", "accept-title")

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(400)
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `revoked actor cannot retrieve an accepted private response`() {
        val case = ownershipCase("SALE")
        assertThat(accept(case).status).isEqualTo(200)
        val revoked = request("PUT", "/api/users/${case.installation.receipt.receiver.second}/access", case.installation.receipt.stock.token,
            """{"roleIds":[],"areaIds":[]}""")
        assertThat(revoked.status).isEqualTo(200)

        val replay = accept(case)

        assertThat(replay.status).isEqualTo(403)
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `changed replay payload and a second business key reject after acceptance`() {
        val case = ownershipCase("SALE")
        assertThat(accept(case).status).isEqualTo(200)

        val changed = accept(case.copy(signature = UUID.randomUUID()))
        val repeated = accept(case, "new-business-key")

        assertThat(changed.status).isEqualTo(409)
        assertThat(repeated.status).isEqualTo(409)
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }
}
