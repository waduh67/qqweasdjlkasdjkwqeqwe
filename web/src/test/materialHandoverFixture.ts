import type { MaterialHandoverGrant, MaterialHandoverSource, MaterialHandoverTarget } from '@/api/warehouse/materialHandover'
import { custodyFixture } from './warehouseExecutionFixture'
import { materialIds as id } from './warehouseMaterialFixture'

export const handoverSourceFixture = (): MaterialHandoverSource => ({ id: id.piece, sender: { id: id.inspection, name: 'Budi pengirim' }, source: { ...custodyFixture(), quantityBase: '17500', sourceUsageId: id.evidence, initialUseSource: false } })
export const handoverTargetFixture = (): MaterialHandoverTarget => ({ id: id.allocation, code: 'FIELD-ANI', name: 'Barang Ani', receiver: { id: id.demandLine, name: 'Ani penerima' } })
export const handoverGrantFixture = (): MaterialHandoverGrant => ({ id: id.plan, workOrderId: id.source, request: { workOrderRevision: 9, receiptId: id.document, issueLineId: id.line, stockIdentityId: id.piece,
  quantityBase: '7500', baseUnit: 'MM', targetLocationId: id.allocation, reason: 'Pergantian teknisi', evidenceReference: 'Instruksi dispatcher 12', usageId: id.evidence,
  expectedSenderId: id.inspection, expectedReceiverId: id.demandLine },
  sender: handoverSourceFixture().sender, receiver: handoverTargetFixture().receiver, dispatcher: { id: id.supplier, name: 'Sari dispatcher' },
  location: { id: id.allocation, code: 'FIELD-ANI', name: 'Barang Ani' }, sku: custodyFixture().sku, serial: null, lotCode: 'REEL-1', currentQuantityBase: '17500', stockRevision: 4, recordedAt: '2026-09-25T01:00:00Z' })
