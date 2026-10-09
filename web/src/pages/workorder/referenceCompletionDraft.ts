import { ownMaterials, type ReferencePhoto, type ReferencePosition, type ReferenceWorkDetail } from '@/api/warehouse/reference'
import { quantityFromInput } from '@/api/warehouse/quantity'

export type ReferenceMaterialDraft = { readonly key: string; readonly source: ReferencePosition | null; readonly quantity: string }
export class ReferenceDraftError extends Error {}

export function completionMaterials(rows: readonly ReferenceMaterialDraft[], required: boolean) {
  if (rows.length > 100 || (required && rows.length === 0)) throw new ReferenceDraftError('Pilih material yang dipakai untuk pekerjaan ini.')
  const seen = new Set<string>()
  return rows.map(row => {
    const source = row.source
    if (!source) throw new ReferenceDraftError('Pilih barang untuk setiap baris material.')
    if (seen.has(source.stockIdentityId)) throw new ReferenceDraftError('Satu barang hanya boleh dipilih sekali.')
    seen.add(source.stockIdentityId)
    let quantityBase: string
    try { quantityBase = quantityFromInput(row.quantity, source.baseUnit) }
    catch (error) {
      if (error instanceof Error) throw new ReferenceDraftError(error.message)
      throw error
    }
    if (BigInt(quantityBase) > BigInt(source.quantityBase)) throw new ReferenceDraftError('Jumlah terpakai melebihi stok di tangan Anda.')
    if (source.tracking === 'SERIAL' && quantityBase !== '1') throw new ReferenceDraftError('Perangkat berserial harus dicatat satu unit.')
    return { stockIdentityId: source.stockIdentityId, quantityBase }
  })
}

export function assertCompletionEvidence(detail: ReferenceWorkDetail, photos: readonly ReferencePhoto[]) {
  const missing = detail.workOrder.type.photoSlots.filter(slot => !photos.some(photo => photo.current && photo.slot === slot && photo.assignmentGeneration === detail.workOrder.assignmentGeneration))
  if (missing.length) throw new ReferenceDraftError('Lengkapi foto wajib: ' + missing.join(', ') + '.')
}

export async function freshOwnPosition(source: ReferencePosition) {
  let page = 0
  while (true) {
    const data = await ownMaterials(source.skuCode, page, 100)
    const position = data.items.find(row => row.stockIdentityId === source.stockIdentityId)
    if (position) return position
    if ((page + 1) * data.size >= data.totalElements) throw new ReferenceDraftError('Barang ' + source.skuName + ' sudah tidak tersedia di tangan Anda. Pilih ulang material.')
    page++
  }
}
