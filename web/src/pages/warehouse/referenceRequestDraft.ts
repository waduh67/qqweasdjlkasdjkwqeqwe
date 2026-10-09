import type { RequestLineInput, ReferenceRequest } from '@/api/warehouse/referenceRequests'
import type { WarehouseSku } from '@/api/warehouse/models'
import { quantityFromInput, type BaseUnit } from '@/api/warehouse/quantity'

export interface RequestDraftRow { readonly key: string; readonly sku: WarehouseSku | null; readonly proposed: boolean; readonly proposedName: string; readonly unit: BaseUnit; readonly quantity: string }
export interface RequestReviewRow { readonly lineId: string; readonly quantity: string; readonly mapped: WarehouseSku | null }
export class RequestDraftError extends Error { readonly name = 'RequestDraftError' }
export const emptyRequestRow = (): RequestDraftRow => ({ key: crypto.randomUUID(), sku: null, proposed: false, proposedName: '', unit: 'EA', quantity: '' })
export function buildRequestLines(rows: readonly RequestDraftRow[], kind: ReferenceRequest['kind']): RequestLineInput[] {
  if (rows.length < 1 || rows.length > 100) throw new RequestDraftError('Isi 1–100 baris material.')
  return rows.map((row, index) => {
    if (row.proposed) {
      if (kind !== 'PROCUREMENT') throw new RequestDraftError('Material baru hanya dapat diajukan untuk pengadaan.')
      const name = row.proposedName.trim()
      if (!name || name.length > 200) throw new RequestDraftError('Isi nama material baru pada baris ' + (index + 1) + '.')
      return { baseUnit: row.unit, requestedBase: quantityFromInput(row.quantity, row.unit), proposedName: name }
    }
    if (!row.sku || row.sku.state !== 'ACTIVE') throw new RequestDraftError('Pilih barang aktif pada baris ' + (index + 1) + '.')
    return { baseUnit: row.sku.baseUnit, requestedBase: quantityFromInput(row.quantity, row.sku.baseUnit), skuId: row.sku.id }
  })
}
export function buildRequestReview(request: ReferenceRequest, rows: readonly RequestReviewRow[]) {
  if (rows.length !== request.lines.length || new Set(rows.map(row => row.lineId)).size !== rows.length) throw new RequestDraftError('Tinjau setiap baris permintaan.')
  const lines = request.lines.map(line => {
    const row = rows.find(row => row.lineId === line.id)
    if (!row) throw new RequestDraftError('Tinjau setiap baris permintaan.')
    const approvedBase = quantityFromInput(row.quantity, line.baseUnit, true)
    if (BigInt(approvedBase) > BigInt(line.requestedBase)) throw new RequestDraftError('Jumlah ' + line.name + ' melebihi permintaan.')
    if (row.mapped && (row.mapped.state !== 'ACTIVE' || row.mapped.baseUnit !== line.baseUnit || line.skuId && row.mapped.id !== line.skuId)) throw new RequestDraftError('Pilih barang aktif dengan satuan yang sama.')
    const skuId = line.skuId ?? row.mapped?.id
    if (approvedBase !== '0' && !skuId) throw new RequestDraftError('Hubungkan material yang disetujui ke barang katalog.')
    return { lineId: line.id, approvedBase, ...(skuId ? { skuId } : {}) }
  })
  if (lines.every(line => line.approvedBase === '0')) throw new RequestDraftError('Setujui minimal satu material atau gunakan Tolak permintaan.')
  return lines
}
