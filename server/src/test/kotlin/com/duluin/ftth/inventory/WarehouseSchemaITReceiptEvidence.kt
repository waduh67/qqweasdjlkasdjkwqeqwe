package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

class WarehouseSchemaITReceiptEvidence {
    @Test fun `174_12 evidence is preserved unbound and only current intake can bind new evidence`() {
        WarehouseSchemaDatabase("174.12").use { database ->
            val tenant = UUID.randomUUID()
            val document = UUID.randomUUID()
            val source = UUID.randomUUID()
            val inspection = UUID.randomUUID()
            val evidence = UUID.randomUUID()
            database.ownerFixture { connection -> connection.createStatement().use { statement ->
                statement.execute("SET app.tenant_id='$tenant'")
                statement.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','evidence-$tenant','Evidence upgrade')")
                statement.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$source','$tenant','RECEIPT_SOURCE','TRANSIT'),('$inspection','$tenant','INSPECTION','QUARANTINE')")
                statement.execute("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','RECEIPT','RECEIPT','${UUID.randomUUID()}',0,0)")
                statement.execute("INSERT INTO inventory_receipt_intake(id,tenant_id,source_location_id,inspection_location_id,snapshot) VALUES ('$document','$tenant','$source','$inspection','{}')")
                statement.execute("INSERT INTO inventory_receipt_evidence(id,tenant_id,document_id,object_key,sha256,content_type,size_bytes,actor_id) VALUES ('$evidence','$tenant','$document','$tenant/warehouse/receipts/$document/$evidence','${"a".repeat(64)}','application/pdf',5,'${UUID.randomUUID()}')")
            } }
            database.migrate("174.13")
            database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute("SET app.tenant_id='$tenant'")
                statement.executeQuery("SELECT intake_content_revision,intake_hash,sha256 FROM inventory_receipt_evidence WHERE id='$evidence'").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1)).isNull()
                    assertThat(rows.getString(2)).isNull()
                    assertThat(rows.getString(3)).isEqualTo("a".repeat(64))
                }
                val before = statement.executeQuery("SELECT content_hash FROM inventory_receipt_intake").use { rows -> rows.next(); rows.getString(1) }
                statement.execute("UPDATE inventory_document SET revision=revision+1 WHERE id='$document'")
                statement.executeQuery("SELECT content_revision,content_hash FROM inventory_receipt_intake").use { rows ->
                    rows.next(); assertThat(rows.getLong(1)).isZero(); assertThat(rows.getString(2)).isEqualTo(before)
                }
                statement.execute("UPDATE inventory_receipt_intake SET snapshot='{\"changed\":true}' WHERE id='$document'")
                statement.executeQuery("SELECT content_revision,content_hash FROM inventory_receipt_intake").use { rows ->
                    rows.next(); assertThat(rows.getLong(1)).isEqualTo(1); assertThat(rows.getString(2)).isNotEqualTo(before)
                }
                val stale = UUID.randomUUID()
                assertThatThrownBy { statement.execute("INSERT INTO inventory_receipt_evidence(id,tenant_id,document_id,object_key,sha256,content_type,size_bytes,actor_id,intake_content_revision,intake_hash) VALUES ('$stale','$tenant','$document','$tenant/warehouse/receipts/$document/$stale','${"b".repeat(64)}','application/pdf',5,'${UUID.randomUUID()}',0,'$before')") }
                    .isInstanceOf(SQLException::class.java).hasMessageContaining("current intake binding")
            } }
            assertThat(database.migrate("174.13").migrationsExecuted).isZero()
        }
    }
}
