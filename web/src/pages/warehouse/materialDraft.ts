import type { MaterialPlan, MaterialSummary } from '@/api/warehouse/materialModels'
import type { MaterialPlanInput, PlanLineInput } from '@/api/warehouse/materials'
import type { WarehouseSku } from '@/api/warehouse/models'
import { formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'

export type MaterialSkuChoice = Pick<WarehouseSku, 'id' | 'name' | 'code' | 'tracking' | 'baseUnit'>
export interface MaterialDraftRow {
  key: string; sku: MaterialSkuChoice | null; quantity: string; continuousCut: boolean;
  previous: MaterialPlan['lines'][number] | null; substitutionReason: string;
}
export const emptyMaterialRow = (): MaterialDraftRow => ({ key: crypto.randomUUID(), sku: null, quantity: '', continuousCut: false, previous: null, substitutionReason: '' })

/** A new substitution must refer to this plan, never to an older revision's source. */
export const rowsFromMaterialPlan = (lines: MaterialPlan['lines'], previous = true): MaterialDraftRow[] => lines.map(line => ({
  ...emptyMaterialRow(), sku: line.sku, quantity: formatBaseQuantity(line.quantityBase, line.sku.baseUnit), continuousCut: line.continuousCut, previous: previous ? line : null,
}))

export function buildMaterialPlan(summary: MaterialSummary, mode: MaterialPlanInput['materialMode'], reason: string, rows: MaterialDraftRow[], override: boolean): MaterialPlanInput {
  const revisions = { expectedRevision: summary.revisions.planRevision, workOrderRevision: summary.revisions.workOrderRevision }
  if (reason.trim().length > 1000) throw new Error('Alasan maksimal 1000 karakter.')
  if (mode === 'NONE') {
    if (!reason.trim()) throw new Error('Isi alasan pekerjaan tidak membutuhkan material.')
    return { ...revisions, materialMode: mode, reason: reason.trim(), lines: [] }
  }
  if (rows.length < 1 || rows.length > 100) throw new Error('Rencana membutuhkan 1–100 baris barang.')
  const seen = new Set<string>()
  const lines = rows.map((row, index): PlanLineInput => {
    if (!row.sku) throw new Error(`Pilih barang pada baris ${index + 1}.`)
    if (seen.has(row.sku.id)) throw new Error(`Barang ${row.sku.name} muncul lebih dari sekali.`)
    seen.add(row.sku.id)
    const line: PlanLineInput = { skuId: row.sku.id, quantityBase: quantityFromInput(row.quantity, row.sku.baseUnit), baseUnit: row.sku.baseUnit, continuousCut: row.sku.baseUnit === 'MM' && row.continuousCut }
    if (row.previous && row.previous.sku.id !== row.sku.id) {
      if (!override) throw new Error('Penggantian barang membutuhkan izin substitusi permintaan.')
      if (row.previous.sku.tracking !== row.sku.tracking || row.previous.sku.baseUnit !== row.sku.baseUnit) throw new Error('Barang pengganti harus memiliki cara pelacakan dan satuan yang sama.')
      if (!row.substitutionReason.trim() || row.substitutionReason.trim().length > 1000) throw new Error(`Isi alasan penggantian ${row.previous.sku.name}, maksimal 1000 karakter.`)
      line.substitution = { originalPlanLineId: row.previous.id, originalSkuId: row.previous.sku.id, reason: row.substitutionReason.trim() }
    }
    return line
  })
  return { ...revisions, materialMode: mode, reason: reason.trim() || null, lines }
}
