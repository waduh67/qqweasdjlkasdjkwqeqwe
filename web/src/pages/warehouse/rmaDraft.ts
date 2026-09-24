import type { WarehouseLocation } from '@/api/warehouse/models'
import type { ReturnDetails, RmaDispatch, RmaWorkOrder } from '@/api/warehouse/returns'
import { completedCustomerRepairInspection } from './returnDraft'

export function readyForRma(details: ReturnDetails) {
  return details.returnCase.origin === 'ASSET_REMOVAL' && details.returnCase.state === 'RECEIVED_IN_INSPECTION' &&
    details.references.item.tracking === 'SERIAL' && details.returnCase.quantityBase === '1' && details.returnCase.baseUnit === 'EA' &&
    completedCustomerRepairInspection(details) && !!details.references.assetOrigin && !details.references.rmaHandoverId
}
export function buildRmaDispatch(details: ReturnDetails, order: RmaWorkOrder, technicianId: string, actorId: string,
  transit: WarehouseLocation | null, field: WarehouseLocation | null, observedSerial: string, evidence: string): RmaDispatch {
  if (!readyForRma(details) || order.customerId !== details.references.assetOrigin?.customerId) throw new Error('Pilih WO perbaikan pelanggan asal setelah inspeksi dan reset servis selesai.')
  if (!order.technicians.some(row => row.id === technicianId) || technicianId === actorId) throw new Error('Pilih teknisi aktif yang ditugaskan dan berbeda dari pengirim.')
  if (!transit || transit.state !== 'ACTIVE' || transit.kind !== 'TRANSIT' || transit.issueEligible || transit.code === 'RECEIPT_SOURCE' ||
    !field || field.state !== 'ACTIVE' || field.kind !== 'TECHNICIAN' || field.custodianId !== technicianId ||
    new Set([details.returnCase.locationId, transit.id, field.id]).size !== 3) throw new Error('Pilih lokasi transit dan lokasi teknisi penerima yang sesuai.')
  if (!observedSerial || observedSerial !== details.references.item.serial) throw new Error('Pindai serial perangkat pelanggan yang sama.')
  if (!evidence.trim() || evidence.trim().length > 500) throw new Error('Isi referensi bukti serah-terima RMA, maksimal 500 karakter.')
  return { expectedRevision: details.returnCase.revision, workOrderId: order.id, workOrderRevision: order.revision, technicianId,
    transitLocationId: transit.id, technicianLocationId: field.id, observedSerial, evidenceReference: evidence.trim() }
}
