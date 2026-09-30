import type { MaterialCustody, MaterialFieldContext, MaterialUseDeltaInput, MaterialUseInput } from '@/api/warehouse/materialExecution'
import { quantityFromInput } from '@/api/warehouse/quantity'

export interface MaterialUseDraft { key: string; source: MaterialCustody | null; quantity: string }
export const emptyUseDraft = (): MaterialUseDraft => ({ key: crypto.randomUUID(), source: null, quantity: '' })
export function eligibleUseSource(context: MaterialFieldContext, source: MaterialCustody) {
  if (!context.hasMeasuredMaterials || source.sku.tracking === 'SERIAL') return false
  if (!context.latestUsageId) return source.initialUseSource && source.planId === context.plan?.id
  return !!context.latestUsageId && (!!context.reworkId || source.sourceUsageId === context.latestUsageId)
}
export function materialUseInput(context: MaterialFieldContext, rows: MaterialUseDraft[], evidence: string, reason: string): MaterialUseInput | MaterialUseDeltaInput {
  if (!context.plan || context.planState !== 'SUBMITTED') throw new Error('Ajukan rencana material sebelum mencatat pemakaian.')
  if (!evidence.trim() || evidence.trim().length > 500) throw new Error('Isi referensi bukti pemakaian, maksimal 500 karakter.')
  if (reason.trim().length > 1000) throw new Error('Alasan maksimal 1000 karakter.')
  if (context.plan.materialMode === 'NONE') {
    if (context.useRevision !== 0 || !reason.trim()) throw new Error('Deklarasi tanpa material memerlukan alasan dan hanya dicatat sekali.')
    return { expectedRevision: context.useRevision, workOrderRevision: context.workOrderRevision, planRevision: context.plan.planRevision, materialMode: 'NONE', evidenceReference: evidence.trim(), reason: reason.trim(), lines: [] }
  }
  if (!context.hasMeasuredMaterials) throw new Error('Pemasangan perangkat sudah dicatat melalui aset pelanggan. Rencana ini tidak memerlukan pemakaian material terukur.')
  if (!rows.length || rows.length > 100 || (context.latestUsageId && rows.length !== 1)) throw new Error('Pilih 1–100 sumber; tambahan pemakaian dicatat satu sumber per transaksi.')
  const seen = new Set<string>()
  const lines = rows.map(row => {
    if (!row.source || !eligibleUseSource(context, row.source)) throw new Error('Pilih barang diterima yang memenuhi syarat. Perangkat berserial dipasang melalui alur aset pelanggan.')
    if (seen.has(row.source.id)) throw new Error('Satu identitas barang hanya boleh dipilih sekali.')
    seen.add(row.source.id)
    const quantityBase = quantityFromInput(row.quantity, row.source.baseUnit)
    if (BigInt(quantityBase) > BigInt(row.source.quantityBase)) throw new Error('Jumlah dipakai melebihi barang yang masih di tangan Anda.')
    return { receiptId: row.source.receiptId, issueLineId: row.source.issueLineId, stockIdentityId: row.source.id, quantityBase, baseUnit: row.source.baseUnit }
  })
  if (!context.latestUsageId) return { expectedRevision: context.useRevision, workOrderRevision: context.workOrderRevision, planRevision: context.plan.planRevision, materialMode: 'MATERIAL_REQUIRED', evidenceReference: evidence.trim(), reason: reason.trim() || undefined, lines }
  if (!context.latestUsageId || !reason.trim()) throw new Error('Tambahan pemakaian memerlukan sumber riwayat dan alasan.')
  return { expectedRevision: context.useRevision, workOrderRevision: context.workOrderRevision, previousUsageId: context.latestUsageId, ...lines[0], evidenceReference: evidence.trim(), reason: reason.trim(),
    ...(context.reworkId ? { reworkId: context.reworkId, evidenceRevision: context.evidenceRevision ?? undefined } : {}) }
}
