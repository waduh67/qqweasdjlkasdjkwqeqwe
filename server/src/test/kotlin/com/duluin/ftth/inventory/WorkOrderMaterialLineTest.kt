package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.domain.model.InventoryItem
import com.duluin.ftth.inventory.domain.model.InventoryItemCategory
import com.duluin.ftth.inventory.domain.model.InventoryUnit
import com.duluin.ftth.inventory.domain.model.MaterialOutcome
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialLine
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialSerial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Invariant kuantitas material dijaga di sini, BUKAN hanya di CHECK database.
 *
 * Alasannya praktis: kalau satu-satunya penjaga adalah `work_order_material_realized_ck`,
 * pelanggarannya baru terlihat sebagai `ConstraintViolationException` saat flush Hibernate —
 * jauh dari tempat kejadian, tanpa menyebut item apa, dan sering di tengah transaksi yang
 * sudah terlanjur menggerakkan ledger.
 */
class WorkOrderMaterialLineTest {

    private val tenant = UuidV7.generate()
    private val workOrder = UuidV7.generate()
    private val customer = UuidV7.generate()
    private val technician = UuidV7.generate()
    private val van = UuidV7.generate()

    private fun item(serialized: Boolean) = InventoryItem.create(
        tenantId = tenant,
        code = if (serialized) "ONT-TEST" else "DC-TEST",
        name = if (serialized) "ONT uji" else "Dropcore uji",
        category = if (serialized) InventoryItemCategory.ONT else InventoryItemCategory.DROPCORE,
        unit = if (serialized) InventoryUnit.PCS else InventoryUnit.METER,
        serialized = serialized,
    )

    private fun line(serialized: Boolean, planned: Int, template: Int? = null) = WorkOrderMaterialLine.plan(
        tenantId = tenant,
        workOrderId = workOrder,
        workOrderType = "PSB",
        item = item(serialized),
        customerId = customer,
        quantity = planned,
        templateQuantity = template,
    )

    private fun serial(line: WorkOrderMaterialLine, sn: String, outcome: MaterialOutcome) = WorkOrderMaterialSerial(
        id = UuidV7.generate(),
        tenantId = tenant,
        materialId = line.id,
        workOrderId = workOrder,
        assetId = UUID.nameUUIDFromBytes(sn.toByteArray()),
        serialNumber = sn,
        macAddress = null,
        outcome = outcome,
        scannedAt = Instant.now(),
        scannedBy = technician,
    )

    @Test
    fun `rencana menyimpan jumlah template supaya selisihnya bisa dilihat`() {
        val planned = line(serialized = false, planned = 80, template = 60)

        assertThat(planned.plannedQuantity).isEqualTo(80)
        assertThat(planned.templateQuantity).isEqualTo(60)
        assertThat(planned.issuedQuantity).isZero()
    }

    @Test
    fun `rencana tidak bisa dikecilkan di bawah yang sudah keluar gudang`() {
        val issued = line(serialized = false, planned = 80).markIssued(80, technician, van)

        assertThatThrownBy { issued.replan(40, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("80")
    }

    @Test
    fun `pengeluaran ke teknisi kedua ditolak karena saldo akan mendarat di orang yang salah`() {
        val issued = line(serialized = false, planned = 80).markIssued(50, technician, van)

        assertThatThrownBy { issued.markIssued(30, UuidV7.generate(), van) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `item berserial menolak pemakaian yang diketik`() {
        val issued = line(serialized = true, planned = 1).markIssued(1, technician, van)

        assertThatThrownBy { issued.recordBulkUsage(1, 0, 0, null) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("scan")
    }

    @Test
    fun `realisasi curah tidak boleh melebihi yang dikeluarkan gudang`() {
        val issued = line(serialized = false, planned = 80).markIssued(80, technician, van)

        assertThatThrownBy { issued.recordBulkUsage(70, 20, 0, "lebih") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("80")
    }

    @Test
    fun `scan menurunkan kuantitas dari daftar serial, bukan menambah inkremental`() {
        val issued = line(serialized = true, planned = 2).markIssued(2, technician, van)

        val afterFirst = issued.attachSerial(serial(issued, "SN-1", MaterialOutcome.INSTALLED))
        val afterSecond = afterFirst.attachSerial(serial(afterFirst, "SN-2", MaterialOutcome.RETURNED))

        assertThat(afterSecond.usedQuantity).isEqualTo(1)
        assertThat(afterSecond.returnedQuantity).isEqualTo(1)
        assertThat(afterSecond.unscannedQuantity).isZero()
    }

    @Test
    fun `koreksi scan pada unit yang sama menulis ulang kuantitas, tidak menggandakannya`() {
        val issued = line(serialized = true, planned = 1).markIssued(1, technician, van)
        val installed = issued.attachSerial(serial(issued, "SN-1", MaterialOutcome.INSTALLED))

        val corrected = installed.attachSerial(serial(installed, "SN-1", MaterialOutcome.LOST))

        assertThat(corrected.serials).hasSize(1)
        assertThat(corrected.usedQuantity).isZero()
        assertThat(corrected.lostQuantity).isEqualTo(1)
    }

    @Test
    fun `scan unit ketiga ditolak kalau gudang hanya mengeluarkan dua`() {
        val issued = line(serialized = true, planned = 2).markIssued(2, technician, van)
        val full = issued
            .attachSerial(serial(issued, "SN-1", MaterialOutcome.INSTALLED))
            .let { it.attachSerial(serial(it, "SN-2", MaterialOutcome.INSTALLED)) }

        assertThatThrownBy { full.attachSerial(serial(full, "SN-3", MaterialOutcome.INSTALLED)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `penyelesaian ditolak selama masih ada unit berserial yang belum di-scan`() {
        val issued = line(serialized = true, planned = 2).markIssued(2, technician, van)
        val half = issued.attachSerial(serial(issued, "SN-1", MaterialOutcome.INSTALLED))

        assertThatThrownBy { half.assertReadyForCompletion("ONT uji") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("belum di-scan")
    }

    @Test
    fun `penyelesaian ditolak selama barang curah belum dilaporkan nasibnya`() {
        val issued = line(serialized = false, planned = 80).markIssued(80, technician, van)
        val partial = issued.recordBulkUsage(60, 0, 0, "sisa masih di haspel")

        assertThatThrownBy { partial.assertReadyForCompletion("Dropcore uji") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("20")
    }

    @Test
    fun `selisih terhadap rencana wajib beralasan, tapi tidak dilarang`() {
        val issued = line(serialized = false, planned = 80).markIssued(80, technician, van)
        val tanpaAlasan = issued.recordBulkUsage(60, 20, 0, null)

        assertThatThrownBy { tanpaAlasan.assertReadyForCompletion("Dropcore uji") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("alasan selisih")

        // BOM adalah pra-isi, bukan pagar: begitu alasannya ada, penyimpangan DITERIMA.
        tanpaAlasan.explainVariance("rumah pelanggan lebih dekat dari perkiraan")
            .assertReadyForCompletion("Dropcore uji")
    }

    @Test
    fun `baris yang pas rencana lolos tanpa alasan selisih`() {
        line(serialized = false, planned = 80)
            .markIssued(80, technician, van)
            .recordBulkUsage(80, 0, 0, null)
            .assertReadyForCompletion("Dropcore uji")
    }

    @Test
    fun `baris yang belum pernah keluar gudang tidak boleh punya pemegang kosong sekaligus stok`() {
        assertThatThrownBy {
            WorkOrderMaterialLine(
                id = UuidV7.generate(),
                tenantId = tenant,
                workOrderId = workOrder,
                workOrderType = "PSB",
                itemId = UuidV7.generate(),
                customerId = customer,
                itemCategory = "DROPCORE",
                serialized = false,
                templateQuantity = null,
                plannedQuantity = 10,
                issuedQuantity = 10,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
