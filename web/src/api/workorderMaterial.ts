/**
 * Material satu work order: rencana (BOM), pengeluaran dari gudang, pemakaian di lapangan,
 * scan nomor seri, dan penarikan aset saat WO DISMANTLE.
 *
 * Terpisah dari `workorder.ts` karena pemiliknya memang modul lain: bentuk view-nya lahir di
 * `com.duluin.ftth.inventory.InventoryAllocationApi` dan `workorder` hanya meneruskannya.
 * Menyatukan keduanya membuat orang mengira `WorkOrderMaterialView` boleh diubah dari sisi
 * work order, padahal ia kontrak lintas modul.
 *
 * Barisnya SUDAH bernama lengkap (`itemName`, `technicianName`, `scannedByName`, …). JANGAN
 * gabungkan ulang `itemId`/`technicianId` ke `/item-master` atau `/api/users` di klien: dua
 * endpoint itu menuntut `inventory.item.view`/`iam.user.view` yang justru TIDAK dipegang
 * teknisi lapangan maupun petugas gudang biasa, dan penggabungan itulah yang dulu membuat
 * layar gudang memajang UUID telanjang.
 */

import { api } from './client'

/** GOOD = masih layak dipakai ulang, DAMAGED = rusak, masuk karantina saat WO disetujui. */
export type RecoveredAssetCondition = 'GOOD' | 'DAMAGED'

/**
 * Nasib satu unit berserial yang dibawa teknisi — WAJIB salah satu, tidak ada "tidak tahu".
 *
 * `INSTALLED` terpasang di pelanggan (satu-satunya yang memotong saldo saat WO disetujui),
 * `RETURNED` dibawa pulang utuh, `LOST` hilang ATAU rusak di lapangan. Sengaja TIDAK ada
 * `DAMAGED` terpisah: server hanya mengenal tiga, dan menawarkan pilihan keempat di layar
 * berarti teknisi memilih sesuatu yang dijawab 400 saat ia sudah berdiri di depan pelanggan.
 */
export type MaterialSerialOutcome = 'INSTALLED' | 'RETURNED' | 'LOST'

export interface WorkOrderMaterialSerialView {
  readonly id: string
  readonly assetId: string
  readonly serialNumber: string
  readonly macAddress: string | null
  readonly outcome: MaterialSerialOutcome
  readonly scannedAt: string
  readonly scannedBy: string
  readonly scannedByName: string
}

export interface WorkOrderMaterialView {
  readonly id: string
  readonly workOrderId: string
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly itemCategory: string
  readonly unit: string
  readonly serialized: boolean
  /** Jumlah menurut BOM saat rencana dibuat; `null` berarti item di luar template. */
  readonly templateQuantity: number | null
  readonly plannedQuantity: number
  readonly issuedQuantity: number
  readonly usedQuantity: number
  readonly returnedQuantity: number
  readonly lostQuantity: number
  /** Unit berserial yang sudah keluar gudang tapi belum di-scan nasibnya. */
  readonly unscannedQuantity: number
  readonly technicianId: string | null
  readonly technicianName: string | null
  readonly technicianLocationId: string | null
  readonly varianceReason: string | null
  readonly serials: readonly WorkOrderMaterialSerialView[]
}

export interface WorkOrderMaterialTemplateView {
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly itemCategory: string
  readonly unit: string
  readonly serialized: boolean
  readonly plannedQuantity: number
  readonly note: string | null
}

export interface WorkOrderRecoveredAssetView {
  readonly id: string
  readonly workOrderId: string
  readonly assetId: string
  readonly serialNumber: string
  readonly macAddress: string | null
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly itemCategory: string
  readonly customerId: string
  readonly technicianId: string
  readonly technicianName: string
  readonly technicianLocationId: string
  readonly condition: RecoveredAssetCondition
  readonly note: string | null
  readonly recoveredAt: string
  readonly recoveredBy: string
  readonly recoveredByName: string
  /** Terisi berarti barisnya DIBATALKAN — barisnya tetap ada, sengaja tidak dihapus. */
  readonly cancelledAt: string | null
  readonly cancelledBy: string | null
  readonly cancelledByName: string | null
  readonly cancelReason: string | null
}

export interface PlanMaterialLineBody {
  readonly itemId: string
  readonly quantity: number
}

export interface IssueMaterialLineBody {
  readonly itemId: string
  readonly quantity: number
  readonly serialNumbers?: readonly string[]
}

export interface IssueMaterialBody {
  readonly fromLocationId: string
  readonly custodianId: string
  readonly technicianId: string
  readonly technicianLocationId: string
  readonly lines: readonly IssueMaterialLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
}

export interface MaterialUsageBody {
  readonly itemId: string
  readonly usedQuantity: number
  readonly returnedQuantity?: number
  readonly lostQuantity?: number
  readonly varianceReason?: string | null
}

export interface MaterialSerialBody {
  readonly serialNumber: string
  readonly outcome: MaterialSerialOutcome
  readonly macAddress?: string | null
}

export interface RecoverAssetBody {
  readonly serialNumber: string
  readonly technicianId: string
  readonly technicianLocationId: string
  readonly condition: RecoveredAssetCondition
  readonly note?: string | null
}

const base = (workOrderId: string) => `/api/work-orders/${workOrderId}/materials`

export const listWorkOrderMaterials = (workOrderId: string) =>
  api.get<WorkOrderMaterialView[]>(base(workOrderId))

export const getWorkOrderMaterialTemplate = (workOrderId: string) =>
  api.get<WorkOrderMaterialTemplateView[]>(`${base(workOrderId)}/template`)

/**
 * Simpan rencana material. `lines: []` berarti "pakai BOM apa adanya" — itulah jalur pra-isi
 * yang dipakai dispatcher saat membuka WO baru, bukan cara mengosongkan rencana.
 */
export const planWorkOrderMaterial = (workOrderId: string, lines: readonly PlanMaterialLineBody[]) =>
  api.put<WorkOrderMaterialView[]>(base(workOrderId), { lines })

export const issueWorkOrderMaterial = (workOrderId: string, body: IssueMaterialBody) =>
  api.post<WorkOrderMaterialView[]>(`${base(workOrderId)}/issues`, body)

/** Pemakaian barang CURAH. Barang berserial ditolak server — jumlahnya datang dari scan. */
export const recordWorkOrderMaterialUsage = (workOrderId: string, body: MaterialUsageBody) =>
  api.post<WorkOrderMaterialView>(`${base(workOrderId)}/usage`, body)

export const scanWorkOrderMaterialSerial = (workOrderId: string, body: MaterialSerialBody) =>
  api.post<WorkOrderMaterialView>(`${base(workOrderId)}/serials`, body)

export const listRecoveredAssets = (workOrderId: string) =>
  api.get<WorkOrderRecoveredAssetView[]>(`${base(workOrderId)}/recovered-assets`)

export const recoverWorkOrderAsset = (workOrderId: string, body: RecoverAssetBody) =>
  api.post<WorkOrderRecoveredAssetView>(`${base(workOrderId)}/recovered-assets`, body)

/**
 * POST, bukan DELETE: barisnya TIDAK dihapus. Jejak "pernah tercatat ditarik lalu dibatalkan,
 * oleh siapa, dengan alasan apa" justru bagian yang paling perlu dibaca saat menyelisik selisih
 * stok. DELETE akan membuat pembacanya mengira barisnya lenyap.
 */
export const cancelRecoveredAsset = (workOrderId: string, recoveredId: string, reason: string | null) =>
  api.post<WorkOrderRecoveredAssetView>(`${base(workOrderId)}/recovered-assets/${recoveredId}/cancel`, { reason })
