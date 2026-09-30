import type { WarehouseLocation } from '@/api/warehouse/models'
import { quantityFromInput } from '@/api/warehouse/quantity'
import type { ReplacementInput, ReturnDetails } from '@/api/warehouse/returns'
import { parseReceiptSerials } from './receiptDraft'

export function buildReplacement(details: ReturnDetails, source: WarehouseLocation | null, inspection: WarehouseLocation | null,
  serial: string, mac: string, externalReference: string, evidenceReference: string, cost: { totalMinor: string; currency: string } | null): ReplacementInput {
  const view = details.returnCase
  if (view.origin !== 'ASSET_REMOVAL' || view.state !== 'REPAIR' || !view.repair || view.repair.returnedRevision !== null ||
    details.references.item.tracking !== 'SERIAL' || view.quantityBase !== '1' || view.legalOwner === 'UNKNOWN') throw new Error('Pengganti hanya dapat disiapkan saat perangkat asli masih berada dalam kasus servis.')
  if (!source || source.state !== 'ACTIVE' || source.code !== 'RECEIPT_SOURCE' || source.kind !== 'TRANSIT' || source.issueEligible ||
    !inspection || inspection.state !== 'ACTIVE' || inspection.kind !== 'QUARANTINE' || inspection.issueEligible) throw new Error('Pilih batas penerimaan dan karantina aktif yang sesuai.')
  const serials = parseReceiptSerials(serial + (mac ? `,${mac}` : ''))
  if (serials.length !== 1 || serials[0].serial.toUpperCase() === details.references.item.serial?.trim().toUpperCase()) throw new Error('Isi satu serial perangkat pengganti yang berbeda dari perangkat lama.')
  if ([externalReference, evidenceReference].some(value => !value.trim() || value.trim().length > 500)) throw new Error('Lengkapi referensi surat dan bukti, maksimal 500 karakter.')
  const value = cost ? { totalMinor: quantityFromInput(cost.totalMinor, 'EA', true), currency: cost.currency.trim().toUpperCase() } : undefined
  if (value && !/^[A-Z]{3}$/.test(value.currency)) throw new Error('Isi kode mata uang tiga huruf, misalnya IDR.')
  return { expectedRevision: view.revision, externalReference: externalReference.trim(), sourceLocationId: source.id, inspectionLocationId: inspection.id,
    skuId: view.skuId, serial: serials[0].serial, mac: serials[0].mac, evidenceReference: evidenceReference.trim(), ...(value ? { cost: value } : {}) }
}
