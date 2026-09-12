package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialLine
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialSerial
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialTemplateLine
import java.util.UUID

/**
 * Penyimpanan rencana + realisasi material work order.
 *
 * Baris material dan baris serialnya SENGAJA disimpan lewat dua operasi terpisah
 * ([save] dan [saveSerial]) alih-alih satu `save` yang menulis ulang seluruh anaknya.
 * Menulis ulang berarti menghapus lalu menyisipkan kembali baris serial dengan id BARU —
 * dan id itulah yang dipakai sebagai `targetId` alokasi, yaitu bagian dari kunci idempotensi
 * saga. Id yang berubah membuat saga memperlakukan unit yang SUDAH dikonsumsi sebagai unit
 * baru dan memotong saldo untuk kedua kalinya.
 */
interface WorkOrderMaterialRepository {
    fun findByWorkOrder(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialLine>

    fun findByWorkOrderAndItem(tenantId: UUID, workOrderId: UUID, itemId: UUID): WorkOrderMaterialLine?

    /**
     * Cari deklarasi TERPASANG untuk satu unit fisik, di WO mana pun.
     *
     * Dipakai untuk menolak scan ganda dengan pesan yang bisa dibaca teknisi. Penjaga
     * terakhirnya tetap indeks parsial `work_order_material_serial_installed_uq` — pencarian
     * ini bisa disalip request bersamaan, dan yang kalah memang harus gagal di basis data.
     */
    fun findInstalledSerialByAsset(tenantId: UUID, assetId: UUID): WorkOrderMaterialSerial?

    fun save(line: WorkOrderMaterialLine): WorkOrderMaterialLine

    fun saveSerial(serial: WorkOrderMaterialSerial): WorkOrderMaterialSerial

    fun deleteLine(tenantId: UUID, id: UUID)
}

interface WorkOrderMaterialTemplateRepository {
    fun findByType(tenantId: UUID, workOrderType: String): List<WorkOrderMaterialTemplateLine>

    fun findAll(tenantId: UUID): List<WorkOrderMaterialTemplateLine>

    fun replaceType(tenantId: UUID, workOrderType: String, lines: List<WorkOrderMaterialTemplateLine>): List<WorkOrderMaterialTemplateLine>
}
