/**
 * Permukaan gudang (module `inventory`): master data, mutasi stok, ledger, dan approval.
 *
 * Bentuk tipe di sini diturunkan dari controller Kotlin apa adanya. Satu hal yang WAJIB
 * diingat pemakainya: read model gudang (`StockBalanceView`, `MovementLegView`, van stock,
 * laporan selisih, dan antrean persetujuan) MEMBAWA SENDIRI nama item, jenis lokasi, dan nama
 * orangnya. JANGAN menggabungkannya lagi di klien terhadap `/item-master` atau `/api/users`:
 * dua endpoint itu menuntut `inventory.item.view` dan `iam.user.view`, izin yang petugas gudang
 * biasa TIDAK punya — gabungan di klien membuat permintaannya kena 403, direktorinya kosong,
 * dan layarnya kembali mencetak kode barang serta UUID orang telanjang. Server meresolusinya
 * in-process tanpa pemeriksaan izin itu.
 *
 * `locationKind` nullable SENGAJA: lokasi yang sudah terhapus tidak dipalsukan jadi jenis
 * tertentu hanya supaya selnya terisi.
 */

import { api } from './client'
import type { PageResponse, User } from './types'

export type LocationKind =
  | 'WAREHOUSE' | 'BIN' | 'VEHICLE' | 'TECHNICIAN' | 'CUSTOMER_SITE'
  | 'QUARANTINE' | 'LOST' | 'DISPOSED' | 'TRANSIT'

export type InventoryItemCategory =
  | 'ONT' | 'DROPCORE' | 'FEEDER' | 'PATCHCORD' | 'ADAPTER'
  | 'CONNECTOR' | 'ACCESSORY' | 'TOOL' | 'OTHER'

export type InventoryUnit = 'PCS' | 'METER' | 'ROLL' | 'SET'

export type InventoryStatus =
  | 'AVAILABLE' | 'RESERVED' | 'ISSUED' | 'IN_TRANSIT' | 'CONSUMED'
  | 'RETURNED' | 'QUARANTINE' | 'LOST' | 'DISPOSED' | 'AWAITING_RECEIPT'

export type OwnerKind =
  | 'WAREHOUSE' | 'VEHICLE' | 'TECHNICIAN' | 'CUSTOMER' | 'REPAIR' | 'TRANSIT' | 'LOST' | 'DISPOSED'

export type MovementKind =
  | 'RESTOCK' | 'RECEIVE' | 'RESERVE' | 'RELEASE' | 'ISSUE' | 'ISSUE_EXCEPTION'
  | 'TRANSFER' | 'TRANSFER_RECEIPT' | 'RETURN' | 'REPAIR' | 'QUARANTINE'
  | 'ADJUSTMENT' | 'LOSS' | 'SCRAP' | 'WRITE_OFF' | 'COUNT_VARIANCE'
  | 'DISPOSAL' | 'CONSUME' | 'REVERSAL'

export type MovementState = 'APPLIED' | 'PENDING_APPROVAL' | 'FAILED_PERMANENT' | 'REQUIRES_MANUAL_REPAIR'
export type LegDirection = 'IN' | 'OUT'

/**
 * Jenis penyesuaian yang boleh DIMINTA manusia. Sengaja bukan `MovementKind` penuh:
 * REVERSAL/CONSUME/TRANSFER_RECEIPT adalah jenis internal dan menawarkannya di formulir
 * berarti petugas bisa membukukan pembalik mutasi tanpa mutasi yang dibalik.
 */
export type AdjustmentKind = 'CORRECTION' | 'LOSS' | 'SCRAP' | 'WRITE_OFF'

export type InventoryApprovalType =
  | 'RESTOCK' | 'ISSUE_EXCEPTION' | 'ADJUSTMENT' | 'LOSS' | 'SCRAP' | 'WRITE_OFF' | 'COUNT_VARIANCE'

export type InventoryApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'REWORK_REQUIRED'
export type InventoryApprovalDecision = 'APPROVE' | 'REJECT'
export type DiscrepancyState = 'OPEN' | 'PENDING_APPROVAL' | 'RESOLVED' | 'REWORK_REQUIRED'

// ————————————————————————————— master data —————————————————————————————

export interface LocationView {
  readonly id: string
  readonly code: string
  readonly kind: LocationKind
  readonly parentId: string | null
}

export interface InventoryItemMasterView {
  readonly id: string
  readonly code: string
  readonly name: string
  readonly category: InventoryItemCategory
  readonly unit: InventoryUnit
  readonly serialized: boolean
  readonly trackMac: boolean
  readonly reorderPoint: number | null
  readonly active: boolean
}

export interface CreateItemBody {
  readonly code: string
  readonly name: string
  readonly category: InventoryItemCategory
  readonly unit: InventoryUnit
  readonly serialized: boolean
  readonly trackMac: boolean
  readonly reorderPoint: number | null
}

/**
 * Perubahan item SENGAJA tidak memuat `code`, `unit`, dan `serialized`: server memang tidak
 * menerimanya di `UpdateItemBody`. Ketiganya sudah menempel di ledger dan aset yang terlanjur
 * tercatat — mengubah `serialized` pada item yang sudah punya 400 unit ONT berarti saldo dan
 * daftar aset langsung bercerita berbeda tanpa satu pun mutasi yang menjelaskannya.
 */
export interface UpdateItemBody {
  readonly name: string
  readonly category: InventoryItemCategory
  readonly reorderPoint: number | null
  readonly trackMac: boolean
}

// ————————————————————————————— baca stok & ledger —————————————————————————————

export interface StockBalanceView {
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly locationId: string
  readonly locationCode: string
  readonly locationKind: LocationKind | null
  readonly custodyOwnerId: string
  readonly custodyOwnerName: string
  readonly custodyOwnerKind: OwnerKind
  readonly status: InventoryStatus
  readonly quantity: number
}

export interface MovementLegView {
  readonly direction: LegDirection
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly locationId: string
  readonly locationCode: string
  readonly locationKind: LocationKind | null
  readonly quantity: number
  readonly status: InventoryStatus
  readonly custodyOwnerId: string
  readonly custodyOwnerName: string
  readonly custodyOwnerKind: OwnerKind
  readonly assetId: string | null
  readonly serialNumber: string | null
}

export interface MovementEntryView {
  readonly movementId: string
  readonly kind: MovementKind
  readonly state: MovementState
  readonly reason: string
  readonly actorId: string
  readonly actorName: string
  readonly occurredAt: string
  readonly operationKey: string
  readonly compensatesMovementId: string | null
  readonly legs: readonly MovementLegView[]
}

export interface VanStockLineView {
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly locationId: string
  readonly locationCode: string
  readonly locationKind: LocationKind | null
  readonly status: InventoryStatus
  readonly quantity: number
  readonly serialNumbers: readonly string[]
}

export interface VanStockView {
  readonly technicianId: string
  readonly technicianName: string
  readonly lines: readonly VanStockLineView[]
}

export interface OpenCountView {
  readonly countId: string
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly locationId: string
  readonly locationCode: string
  readonly locationKind: LocationKind | null
  readonly priorQuantity: number
  readonly observedQuantity: number
  readonly delta: number
  readonly custodianId: string
  readonly custodianName: string
  /** Keterangan petugas opname — penyetuju menilai angka DAN alasannya, bukan angkanya saja. */
  readonly reason: string
  readonly state: DiscrepancyState
  readonly countedAt: string
}

export interface BalanceAnomalyView {
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly locationId: string
  readonly locationCode: string
  readonly locationKind: LocationKind | null
  readonly status: InventoryStatus
  readonly projectedQuantity: number
  readonly serializedAssetCount: number
  readonly issue: string
}

export interface VarianceReportView {
  readonly openCounts: readonly OpenCountView[]
  readonly anomalies: readonly BalanceAnomalyView[]
}

export interface LedgerFilter {
  readonly kind?: MovementKind | ''
  readonly state?: MovementState | ''
  readonly itemId?: string
  readonly locationId?: string
}

// ————————————————————————————— mutasi (tulis) —————————————————————————————

export interface StockLineBody {
  readonly itemId: string
  readonly quantity: number
  /** WAJIB sebanyak `quantity` kalau itemnya berserial; kosong untuk barang curah. */
  readonly serialNumbers: readonly string[]
}

export interface MovementLeg {
  readonly direction: LegDirection
  readonly itemId: string
  readonly skuId: string
  readonly locationId: string
  readonly quantity: number
  readonly serialized: boolean
  readonly custodyOwnerId: string
  readonly custodyOwnerKind: OwnerKind
  readonly status: InventoryStatus
  readonly assetId: string | null
  readonly serialNumber: string | null
}

export interface InventoryMovement {
  readonly movementId: string
  readonly tenantId: string
  readonly namespace: string
  readonly operationKey: string
  readonly payloadHash: string
  readonly actorId: string
  readonly reason: string
  readonly serverReceivedAt: string
  readonly kind: MovementKind
  readonly legs: readonly MovementLeg[]
  readonly state: MovementState
  readonly compensatesMovementId: string | null
}

/** `approval` null berarti operasi ini replay dari permintaan yang sudah diproses. */
export interface InventoryOperationResult {
  readonly movement: InventoryMovement
  readonly approval: InventoryApprovalRequest | null
}

export interface GoodsReceiptBody {
  readonly locationId: string
  readonly custodianId: string
  readonly lines: readonly StockLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
  readonly emergencyReason: string | null
}

export interface TransferBody {
  readonly fromLocationId: string
  readonly fromCustodianId: string
  readonly toLocationId: string
  readonly toCustodianId: string
  readonly lines: readonly StockLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
}

export interface IssueBody {
  readonly fromLocationId: string
  readonly custodianId: string
  readonly technicianId: string
  readonly technicianLocationId: string
  readonly lines: readonly StockLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
}

export interface ReturnBody {
  readonly fromLocationId: string
  readonly technicianId: string
  readonly toLocationId: string
  readonly custodianId: string
  readonly quarantine: boolean
  readonly lines: readonly StockLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
}

export interface AdjustmentBody {
  readonly locationId: string
  readonly custodianId: string
  readonly kind: AdjustmentKind
  /** Hanya berlaku untuk `CORRECTION`; jenis lain selalu mengurangi. */
  readonly increase: boolean
  readonly lines: readonly StockLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
  readonly emergencyReason: string | null
}

export interface SerialLineBody {
  readonly serialNumber: string
  readonly macAddress: string | null
}

export interface BulkSerialBody {
  readonly itemId: string
  readonly locationId: string
  readonly custodianId: string
  readonly serials: readonly SerialLineBody[]
  readonly reason: string
  readonly operationKey: string
  readonly payloadHash: string
  readonly emergencyReason: string | null
}

export interface RegisteredSerial {
  readonly assetId: string
  readonly serialNumber: string
}

export interface BulkSerialRegistrationResult {
  readonly movementId: string
  readonly registered: readonly RegisteredSerial[]
  readonly replayed: boolean
  readonly approval: InventoryApprovalRequest | null
}

export interface CycleCountBody {
  readonly locationId: string
  readonly itemId: string
  readonly observedQuantity: number
  readonly custodianId: string
  readonly reason: string
  readonly evidenceReference: string
  readonly operationKey: string
  readonly payloadHash: string
}

export interface CycleCount {
  readonly countId: string
  readonly tenantId: string
  readonly locationId: string
  readonly itemId: string
  readonly skuId: string
  readonly priorQuantity: number
  readonly observedQuantity: number
  readonly reason: string
  readonly evidenceReference: string
  readonly operationKey: string
  readonly operationHash: string
  readonly custodianId: string
  readonly createdAt: string
  readonly discrepancy: DiscrepancyState
  readonly approverId: string | null
  readonly closedAt: string | null
}

// ————————————————————————————— approval —————————————————————————————

export interface InventoryApprovalDecisionSnapshot {
  readonly decisionId: string
  readonly tier: number
  readonly approverId: string
  readonly approverName: string
  readonly delegatedFrom: string | null
  readonly delegatedFromName: string | null
  readonly decision: InventoryApprovalDecision
  readonly reason: string | null
  readonly decidedAt: string
  readonly revision: number
  readonly operationKey: string
  readonly operationHash: string
}

export interface InventoryApprovalPolicy {
  readonly version: number
  readonly tiers: readonly { readonly number: number; readonly minimumAmount: number; readonly approverIds: readonly string[] }[]
  /** ISO-8601 Duration (`PT24H`), bukan angka jam — Jackson menuliskan `java.time.Duration` apa adanya. */
  readonly expiry: string
  readonly emergencyAllowed: boolean
}

export interface InventoryApprovalRequest {
  readonly approvalId: string
  readonly tenantId: string
  readonly type: InventoryApprovalType
  readonly amount: number
  readonly requesterId: string
  readonly requesterName: string
  readonly custodianId: string | null
  readonly custodianName: string | null
  readonly movementId: string | null
  readonly policy: InventoryApprovalPolicy
  readonly policySnapshotHash: string
  readonly operationKey: string
  readonly operationHash: string
  readonly emergencyReason: string | null
  readonly requestedAt: string
  readonly expiresAt: string
  readonly status: InventoryApprovalStatus
  readonly revision: number
  readonly decisions: readonly InventoryApprovalDecisionSnapshot[]
}

/**
 * Satu tier kebijakan. `approverIds` dan `roleHolderIds` datang TERPISAH dan harus tetap
 * terpisah sampai ke layar: yang pertama ditunjuk administrator dan boleh ia hapus di sana,
 * yang kedua hasil resolusi `approverRole` dari modul iam dan hanya berubah lewat penugasan
 * peran di pengaturan pengguna. Menggabungkannya jadi satu daftar akan memasang tombol hapus
 * di sebelah nama yang tidak bisa dihapus dari layar itu — administrator menekannya, menyimpan,
 * namanya kembali lagi, dan ia menyimpulkan penyimpanannya gagal.
 */
export interface ApprovalTierView {
  readonly number: number
  readonly minimumAmount: number
  readonly approverRole: string
  readonly approverIds: readonly string[]
  readonly roleHolderIds: readonly string[]
}

export interface ApprovalPolicyView {
  readonly type: InventoryApprovalType
  readonly expiryHours: number
  readonly emergencyAllowed: boolean
  /** False = permintaan bertipe ini akan DITOLAK sampai ada tier yang punya penyetuju efektif. */
  readonly configured: boolean
  readonly tiers: readonly ApprovalTierView[]
}

export interface ApprovalTierBody {
  readonly number: number
  readonly minimumAmount: number
  readonly approverRole: string
  readonly approverIds: readonly string[]
}

export interface ApprovalPolicyBody {
  readonly tiers: readonly ApprovalTierBody[]
  readonly expiryHours: number
  readonly emergencyAllowed: boolean
}

export interface EmergencyOverrideView {
  readonly approvalId: string
  readonly type: InventoryApprovalType
  readonly amount: number
  readonly requesterId: string
  readonly requesterName: string
  readonly custodianId: string | null
  readonly custodianName: string | null
  readonly reason: string
  readonly bypassedTiers: readonly number[]
  readonly occurredAt: string
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

export interface MaterialTemplateLineBody {
  readonly itemId: string
  readonly plannedQuantity: number
  readonly note: string | null
}

// ————————————————————————————— permukaan lama (dipertahankan) —————————————————————————————

export interface InventoryLocationView {
  readonly id: string
  readonly code: string
  readonly kind: string
}

export interface InventoryItemView {
  readonly id: string
  readonly skuId: string
  readonly serialNumber: string
  readonly macAddress: string | null
  readonly status: string
}

export interface InventoryStockView {
  readonly skuId: string
  readonly locationId: string
  readonly quantities: Readonly<Record<string, number>>
}

export interface InventoryReservationView {
  readonly assetId: string
  readonly skuId: string
  readonly locationId: string
  readonly custodianId: string
}

export interface InventoryCustodyView {
  readonly assetId: string
  readonly skuId: string
  readonly status: string
  readonly ownerKind: string
  readonly ownerId: string
  readonly locationId: string
}

export interface InventoryAssetRef {
  readonly assetId: string
  readonly tenantId: string
  readonly skuId: string
  readonly serialNumber: string
  readonly macAddress: string | null
  readonly status: InventoryStatus
  readonly locationId: string
  readonly custodyOwnerId: string
  readonly installedOnuId: string | null
}

// ————————————————————————————— identitas operasi —————————————————————————————

export interface OperationEnvelope {
  readonly operationKey: string
  readonly payloadHash: string
}

/**
 * Kunci operasi + sidik payload untuk setiap aksi tulis gudang.
 *
 * Bukan formalitas: petugas gudang bekerja di jaringan yang putus-nyambung dan akan menekan
 * "Simpan" dua kali setiap kali layarnya diam. Tanpa kunci ini tekanan kedua menjadi
 * pengeluaran barang KEDUA, dan selisihnya baru ketahuan saat stok fisik diadu dengan sistem.
 *
 * Kuncinya dibuat SEKALI per percobaan simpan lalu dipakai ulang saat pengguna mencoba lagi —
 * karena itu fungsinya menerima kunci lama lewat [reuseKey]. Kalau setiap retry memakai kunci
 * baru, justru retry yang sebenarnya duplikatlah yang lolos.
 */
export async function operationEnvelope(payload: unknown, reuseKey?: string): Promise<OperationEnvelope> {
  return { operationKey: reuseKey ?? newOperationKey(), payloadHash: await hashPayload(payload) }
}

function newOperationKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `op-${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`
}

/**
 * SHA-256 bila tersedia, FNV-1a bila tidak. Cadangannya ada karena `crypto.subtle` HANYA
 * hidup di secure context: konsol yang dibuka lewat `http://ip-lokal` saat instalasi di
 * lapangan tidak punya `subtle` sama sekali, dan tanpa cadangan setiap tombol simpan gudang
 * melempar TypeError sebelum satu request pun berangkat. Sidik ini dibandingkan server hanya
 * untuk mendeteksi payload yang BERBEDA di kunci operasi yang sama, jadi kekuatan kriptografis
 * bukan syaratnya — yang penting payload berbeda menghasilkan sidik berbeda.
 */
async function hashPayload(payload: unknown): Promise<string> {
  const text = JSON.stringify(payload ?? null)
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
  }
  let hash = 0x811c9dc5
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return `fnv1a-${hash.toString(16).padStart(8, '0')}`
}

// ————————————————————————————— master data —————————————————————————————

export const listInventoryLocations = (kind?: LocationKind) =>
  api.get<LocationView[]>(`/api/inventory/locations${kind ? `?kind=${kind}` : ''}`)

export const createInventoryLocation = (body: { code: string; kind: LocationKind; parentId: string | null }) =>
  api.post<LocationView>('/api/inventory/locations', body)

export const updateInventoryLocation = (id: string, body: { code: string; parentId: string | null }) =>
  api.put<LocationView>(`/api/inventory/locations/${id}`, body)

export const listItemMaster = (includeInactive = false) =>
  api.get<InventoryItemMasterView[]>(`/api/inventory/item-master?includeInactive=${includeInactive}`)

export const getItemMaster = (id: string) => api.get<InventoryItemMasterView>(`/api/inventory/item-master/${id}`)

export const createItemMaster = (body: CreateItemBody) =>
  api.post<InventoryItemMasterView>('/api/inventory/item-master', body)

export const updateItemMaster = (id: string, body: UpdateItemBody) =>
  api.put<InventoryItemMasterView>(`/api/inventory/item-master/${id}`, body)

/** Nonaktifkan/aktifkan — server memang tidak menyediakan DELETE; ledger lama tetap menunjuk item ini. */
export const setItemMasterActive = (id: string, active: boolean) =>
  api.post<InventoryItemMasterView>(`/api/inventory/item-master/${id}/active`, { active })

// ————————————————————————————— aset berserial —————————————————————————————

export const registerSerialsBulk = (body: BulkSerialBody) =>
  api.post<BulkSerialRegistrationResult>('/api/inventory/serialized/bulk', body)

export const requestSerialRestock = (body: BulkSerialBody) =>
  api.post<BulkSerialRegistrationResult>('/api/inventory/serialized/restock-requests', body)

export const getSerializedAsset = (id: string) => api.get<InventoryAssetRef>(`/api/inventory/serialized/${id}`)

export const linkInstalledOnu = (id: string, onuId: string, operationKey: string) =>
  api.post<InventoryAssetRef>(`/api/inventory/serialized/${id}/installed-onu`, { onuId, operationKey })

// ————————————————————————————— mutasi —————————————————————————————

export const receiveGoods = (body: GoodsReceiptBody) => api.post<InventoryMovement>('/api/inventory/receipts', body)

export const requestRestock = (body: GoodsReceiptBody) =>
  api.post<InventoryOperationResult>('/api/inventory/restock-requests', body)

export const transferStock = (body: TransferBody) => api.post<InventoryMovement>('/api/inventory/transfers', body)

export const issueStock = (body: IssueBody) => api.post<InventoryMovement>('/api/inventory/issues', body)

export const returnStock = (body: ReturnBody) => api.post<InventoryMovement>('/api/inventory/returns', body)

export const adjustStock = (body: AdjustmentBody) =>
  api.post<InventoryOperationResult>('/api/inventory/adjustments', body)

export const createCycleCount = (body: CycleCountBody) => api.post<CycleCount>('/api/inventory/counts', body)

/**
 * Read model, bukan agregat `CycleCount`. Barisnya sudah bernama lengkap — JANGAN gabungkan
 * lagi `itemId`/`custodianId`-nya ke `/item-master` atau `/api/users`: dua endpoint itu minta
 * izin yang tidak dipegang petugas gudang biasa, dan justru itu yang dulu membuat tabel ini
 * memajang UUID. Yang menulis (`createCycleCount`/`approveCycleCount`) tetap memulangkan
 * agregatnya karena pemanggilnya cuma perlu tahu operasinya jadi, lalu memuat ulang daftar ini.
 */
export const listOpenCounts = () => api.get<OpenCountView[]>('/api/inventory/counts/open')

export const approveCycleCount = (id: string, envelope: OperationEnvelope) =>
  api.post<CycleCount>(`/api/inventory/counts/${id}/approval`, envelope)

// ————————————————————————————— baca —————————————————————————————

export function listLedger(filter: LedgerFilter, page: number, size: number) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filter.kind) params.set('kind', filter.kind)
  if (filter.state) params.set('state', filter.state)
  if (filter.itemId) params.set('itemId', filter.itemId)
  if (filter.locationId) params.set('locationId', filter.locationId)
  return api.get<PageResponse<MovementEntryView>>(`/api/inventory/ledger?${params}`)
}

export function listStockBalances(itemId?: string, locationId?: string) {
  const params = new URLSearchParams()
  if (itemId) params.set('itemId', itemId)
  if (locationId) params.set('locationId', locationId)
  const query = params.toString()
  return api.get<StockBalanceView[]>(`/api/inventory/balances${query ? `?${query}` : ''}`)
}

export const listVanStock = (technicianId?: string) =>
  api.get<VanStockView[]>(`/api/inventory/van-stock${technicianId ? `?technicianId=${technicianId}` : ''}`)

export const getVarianceReport = () => api.get<VarianceReportView>('/api/inventory/variance-report')

export const listWarehouses = () => api.get<InventoryLocationView[]>('/api/inventory/warehouses')
export const listInventoryItems = () => api.get<InventoryItemView[]>('/api/inventory/items')
export const listInventoryStock = () => api.get<InventoryStockView[]>('/api/inventory/stock')
export const listReservations = () => api.get<InventoryReservationView[]>('/api/inventory/reservations')
export const listCustody = () => api.get<InventoryCustodyView[]>('/api/inventory/custody')

// ————————————————————————————— approval —————————————————————————————

export const listPendingApprovals = () => api.get<InventoryApprovalRequest[]>('/api/inventory/approvals/pending')

export const getInventoryApproval = (id: string) =>
  api.get<InventoryApprovalRequest>(`/api/inventory/approvals/${id}`)

export const decideInventoryApproval = (
  id: string,
  decision: InventoryApprovalDecision,
  reason: string | null,
  envelope: OperationEnvelope,
) =>
  api.post<InventoryApprovalRequest>(`/api/inventory/approvals/${id}/decision`, {
    decision,
    operationKey: envelope.operationKey,
    // Server menamainya `operationHash` di jalur keputusan (bukan `payloadHash` seperti mutasi).
    operationHash: envelope.payloadHash,
    reason,
  })

export const listApprovalPolicies = () => api.get<ApprovalPolicyView[]>('/api/inventory/approvals/policies')

export const saveApprovalPolicy = (type: InventoryApprovalType, body: ApprovalPolicyBody) =>
  api.put<ApprovalPolicyView>(`/api/inventory/approvals/policies/${type}`, body)

export const listEmergencyOverrides = () =>
  api.get<EmergencyOverrideView[]>('/api/inventory/approvals/emergency-overrides')

// ————————————————————————————— template material WO —————————————————————————————

export const getMaterialTemplate = (workOrderType: string) =>
  api.get<WorkOrderMaterialTemplateView[]>(`/api/inventory/material-templates/${workOrderType}`)

export const saveMaterialTemplate = (workOrderType: string, lines: readonly MaterialTemplateLineBody[]) =>
  api.put<WorkOrderMaterialTemplateView[]>(`/api/inventory/material-templates/${workOrderType}`, { lines })

// ————————————————————————————— direktori pengguna —————————————————————————————

/**
 * Daftar pengguna dipakai layar gudang untuk MENERJEMAHKAN id: penyetuju di kebijakan
 * approval, pemegang custody, dan teknisi tujuan pengeluaran semuanya tiba sebagai UUID.
 * Endpoint-nya milik iam, tapi dibungkus di sini supaya layar gudang tidak perlu tahu
 * bentuk paging iam — dan supaya jelas bahwa kegagalannya TIDAK BOLEH mematikan halaman:
 * tanpa direktori, layar tetap harus tampil dengan UUID apa adanya, bukan kosong.
 */
export const listUserDirectory = () =>
  api.get<PageResponse<User>>('/api/users?size=200').then((page) => page.content)
