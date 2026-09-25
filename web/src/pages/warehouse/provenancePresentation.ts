import type { MigrationCase } from '@/api/warehouse/provenanceModels'

export const migrationSourceLabels = {
  inventory_serialized_asset: 'Perangkat lama', inventory_balance_projection: 'Saldo lama', onu: 'Instalasi ONU',
  inventory_serial_tombstone: 'Serial yang dihentikan', inventory_movement: 'Pergerakan lama', inventory_movement_leg: 'Rincian pergerakan',
  inventory_fulfillment_effect: 'Efek material', inventory_customer_material_fact: 'Riwayat material pelanggan',
  fulfillment_checkpoint: 'Penyelesaian pekerjaan', fulfillment_outbox: 'Antrean pekerjaan',
} as const
export const resolutionLabels = { BASELINE_STOCK: 'Calon saldo awal', PROVENANCE_ONLY: 'Riwayat saja', DUPLICATE: 'Catatan ganda', CANCEL_PENDING: 'Batalkan efek tertunda' } as const
export const cutoverLabels = { LEGACY: 'Data lama belum diperiksa', VALIDATING: 'Pemeriksaan berlangsung', ENFORCED: 'Operasi gudang aktif' } as const
export const claimLabels = { LEGACY_RESERVED: 'Dicadangkan untuk data lama', CONFLICT: 'Identitas berbenturan', ADMITTED: 'Terikat aset terverifikasi', RETIRED: 'Identitas dihentikan' } as const
export function migrationCaseLabel(row: MigrationCase) {
  const identity = row.source.serial === '' ? 'Serial kosong' : row.source.serial ?? row.source.model
  return identity ?? (migrationSourceLabels[row.sourceTable] + (row.location ? ' · ' + (row.location.name || row.location.code) : ''))
}
export function migrationPending(row: MigrationCase) {
  return row.sourceTable === 'inventory_movement' ? row.source.state !== 'APPLIED'
    : ['fulfillment_checkpoint', 'fulfillment_outbox'].includes(row.sourceTable) && !['APPLIED', 'FAILED_PERMANENT', 'MANUAL_RESOLVED'].includes(row.source.state ?? '')
}
export function baselineCandidate(row: MigrationCase) {
  return ['inventory_serialized_asset', 'inventory_balance_projection'].includes(row.sourceTable) && row.source.state === 'AVAILABLE' &&
    row.location !== null && row.source.installedOnuId === null && row.source.legalOwner !== 'CUSTOMER' &&
    row.source.custodyOwnerKind === 'WAREHOUSE' &&
    (row.sourceTable !== 'inventory_serialized_asset' || (!!row.source.serial?.trim() && !row.claims.some(claim =>
      claim.identityType === 'MAC' && claim.rawValue === row.source.mac && claim.canonicalValue === null))) &&
    (row.source.condition === null || row.source.condition === 'SERVICEABLE')
}
export function baselinePreview(row: MigrationCase, sourceUnit: string) {
  if (!['EA', 'MM', 'M'].includes(sourceUnit) || (row.source.baseUnit !== null && row.source.baseUnit !== sourceUnit)) return null
  const raw = row.sourceTable === 'inventory_serialized_asset' ? '1' : row.source.baseUnit ? row.source.quantityBase : row.source.legacyQuantity
  if (raw === null || !/^[0-9]+$/.test(raw)) return null
  const amount = BigInt(raw) * (sourceUnit === 'M' ? 1000n : 1n)
  if (amount < 1n || amount > 9223372036854775807n) return null
  return { quantityBase: amount.toString(), baseUnit: sourceUnit === 'EA' ? 'EA' as const : 'MM' as const }
}
const issueLabels: Record<string, string> = {
  RESOLUTION_REQUIRED: 'Kasus ini masih memerlukan keputusan berbukti.',
  STOCK_REVISION_CHANGED: 'SKU atau lokasi berubah. Periksa ulang usulan stok.',
  STOCK_NO_LONGER_ELIGIBLE: 'Sumber tidak lagi memenuhi syarat untuk saldo awal.',
  SOURCE_CHANGED_AFTER_CUTOFF: 'Sumber berubah sejak pemeriksaan dimulai.',
  IDENTITY_CONFLICT_REQUIRES_REVIEW: 'Tentukan catatan asli untuk identitas yang berbenturan.',
  ASSET_BALANCE_REQUIRES_RECONCILIATION: 'Rekonsiliasi saldo yang menunjuk perangkat ini agar tidak dihitung dua kali.',
  DUPLICATE_WINNER_CHANGED: 'Keputusan pada catatan asli berubah. Periksa ulang tautan catatan ganda.',
  APPROVED_OPENING_REQUIRED: 'Saldo awal memerlukan persetujuan independen sampai seluruh tingkat selesai.',
  OPENING_PROOF_INVALID: 'Bukti pembukuan saldo awal perlu diperiksa.',
  APPROVED_REVIEW_CHANGED: 'Pemeriksaan yang disetujui tidak lagi cocok.',
  OPENING_EFFECT_RECEIPT_REQUIRED: 'Pencatatan persetujuan saldo awal belum lengkap.',
  LEGACY_CANCELLATION_REQUIRED: 'Masih ada efek lama yang belum dibatalkan melalui persetujuan.',
  CURRENT_IDENTITY_RESERVATION_REQUIRED: 'Reservasi identitas lama belum lengkap.',
  BASELINE_STOCK_CHANGED: 'Stok saat ini tidak cocok dengan saldo awal yang disetujui.',
  ADMITTED_IDENTITY_CHANGED: 'Identitas aset yang dibukukan perlu diperiksa.',
  CUTOVER_NOT_VALIDATING: 'Tenant sudah berada di luar tahap pemeriksaan.',
}
export const migrationIssueLabel = (code: string) => issueLabels[code] ?? 'Pemeriksaan ini perlu ditinjau kembali sebelum dilanjutkan.'
export function migrationTextInvalid(value: string) {
  return Array.from(value).some(character => {
    const code = character.charCodeAt(0)
    return code < 32 || (code >= 127 && code <= 159)
  })
}
