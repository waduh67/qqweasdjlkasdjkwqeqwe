import type { WarehouseLocation, WarehouseSupplier } from '@/api/warehouse/models'
import { quantityFromInput } from '@/api/warehouse/quantity'
import { sameSerialIdentity } from '@/api/warehouse/serialIdentity'
import type { RepairDispatch, RepairReceipt, ReturnDetails, ReturnInspection, ReturnIntake, ReturnSource } from '@/api/warehouse/returns'

function evidence(value: string) {
  if (!value.trim() || value.trim().length > 500) throw new Error('Isi referensi bukti, maksimal 500 karakter.')
  return value.trim()
}
function quarantine(location: WarehouseLocation | null): WarehouseLocation {
  if (!location || location.state !== 'ACTIVE' || location.kind !== 'QUARANTINE' || location.issueEligible) throw new Error('Pilih lokasi karantina aktif yang tidak tersedia untuk pengeluaran.')
  return location
}
function serial(details: ReturnDetails, observed: string) {
  if (details.references.item.tracking !== 'SERIAL' || !sameSerialIdentity(observed, details.references.item.serial)) throw new Error('Pindai serial fisik yang sama dengan dokumen retur.')
  return observed
}
export function needsPostRepairInspection(details: ReturnDetails) {
  const { repair, inspection } = details.returnCase
  return repair?.returnedRevision != null && (!inspection || inspection.expectedRevision < repair.returnedRevision)
}
export function completedCustomerRepairInspection(details: ReturnDetails) {
  const view = details.returnCase
  return view.legalOwner === 'CUSTOMER' && view.condition === 'SERVICEABLE' && view.inspection?.resetConfirmed === true &&
    view.repair?.returnedRevision != null && !needsPostRepairInspection(details)
}
export function buildReturnIntake(source: ReturnSource | null, destination: WarehouseLocation | null, proof: string): ReturnIntake {
  if (!source) throw new Error('Pilih sumber retur yang masih memenuhi syarat.')
  const target = quarantine(destination)
  if (source.origin === 'MATERIAL_RESIDUAL' && target.id !== source.quarantineLocationId) throw new Error('Sisa material harus diperiksa di karantina penerimaan aslinya.')
  return { origin: source.origin, sourceDocumentId: source.sourceDocumentId, quarantineLocationId: target.id, evidenceReference: evidence(proof) }
}
export function buildReturnInspection(details: ReturnDetails, destination: WarehouseLocation | null, measured: string,
  condition: ReturnInspection['condition'], proof: string, observedSerial: string, resetConfirmed: boolean, resetProof: string): ReturnInspection {
  const view = details.returnCase
  if (view.state !== 'RECEIVED_IN_INSPECTION' || details.references.rmaHandoverId) throw new Error('Retur ini tidak sedang menunggu inspeksi gudang. Muat ulang dokumen.')
  const measuredQuantityBase = quantityFromInput(measured, view.baseUnit)
  if (measuredQuantityBase !== view.quantityBase) throw new Error('Hasil ukur harus sama dengan seluruh potongan atau unit yang diterima. Selisih perlu ditangani terpisah; jangan menggabungkan atau menambah stok.')
  if (condition === 'SCRAP') throw new Error('Penghapusan barang memerlukan keputusan independen.')
  if (condition === 'SERVICEABLE' && view.legalOwner === 'UNKNOWN') throw new Error('Kepemilikan barang harus dipastikan sebelum dinyatakan layak pakai.')
  if (condition === 'SERVICEABLE' && view.legalOwner === 'ISP') {
    if (!destination || destination.state !== 'ACTIVE' || destination.kind !== 'BIN' || !destination.issueEligible) throw new Error('Barang ISP yang layak pakai harus masuk rak aktif yang dapat dikeluarkan.')
  } else quarantine(destination)
  const input: ReturnInspection = { expectedRevision: view.revision, measuredQuantityBase, condition, destinationLocationId: destination!.id,
    evidenceReference: evidence(proof), resetConfirmed: false }
  if (details.references.item.tracking === 'SERIAL') {
    input.observedSerial = serial(details, observedSerial)
    if (condition === 'SERVICEABLE' && !resetConfirmed) throw new Error('Konfirmasikan reset perangkat dan penghapusan konfigurasi sebelum dinyatakan layak pakai.')
    input.resetConfirmed = resetConfirmed
    if (resetConfirmed) input.resetEvidenceReference = evidence(resetProof)
  } else if (observedSerial || resetConfirmed || resetProof) throw new Error('Sisa kabel atau barang tanpa serial tidak memakai checklist reset perangkat.')
  return input
}
export function buildRepairDispatch(details: ReturnDetails, vendor: WarehouseSupplier | null, destination: WarehouseLocation | null,
  observedSerial: string, vendorReference: string, proof: string): RepairDispatch {
  const view = details.returnCase
  if (view.state !== 'RECEIVED_IN_INSPECTION' || view.origin !== 'ASSET_REMOVAL' || !view.inspection || view.repair || details.references.rmaHandoverId) throw new Error('Servis memerlukan retur perangkat yang sudah diperiksa dan belum memiliki kasus servis.')
  if (!vendor || vendor.state !== 'ACTIVE' || !destination || destination.state !== 'ACTIVE' || destination.kind !== 'TRANSIT' || destination.issueEligible) throw new Error('Pilih penyedia servis aktif dan lokasi transit servis yang sesuai.')
  return { expectedRevision: view.revision, vendorId: vendor.id, repairLocationId: destination.id, observedSerial: serial(details, observedSerial),
    vendorReference: evidence(vendorReference), evidenceReference: evidence(proof) }
}
export function buildRepairReceipt(details: ReturnDetails, destination: WarehouseLocation | null, observedSerial: string,
  result: RepairReceipt['result'], vendorReference: string, proof: string): RepairReceipt {
  const view = details.returnCase
  if (view.state !== 'REPAIR' || !view.repair || view.repair.returnedRevision !== null) throw new Error('Kasus servis ini tidak sedang menunggu pengembalian perangkat.')
  return { expectedRevision: view.revision, observedSerial: serial(details, observedSerial), quarantineLocationId: quarantine(destination).id,
    result, vendorReference: evidence(vendorReference), evidenceReference: evidence(proof) }
}
