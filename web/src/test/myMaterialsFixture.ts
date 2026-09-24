import type { MyMaterialContext, MyMaterialIssue, MyMaterialResidual } from '@/api/warehouse/myMaterials'
import { fieldContextFixture } from './warehouseExecutionFixture'
import { materialIds as id, materialSku } from './warehouseMaterialFixture'

export const myContext = (): MyMaterialContext => ({ id: id.source, code: 'WO-MATERIAL', workOrderRevision: 5, technicalState: 'ASSIGNED', qaState: null, currentAssignee: true, active: true, field: fieldContextFixture() })
export const myIssue = (): MyMaterialIssue => ({ id: id.issue, code: 'ISS-MATERIAL', workOrderId: id.source, workOrderRevision: 5, revision: 2, state: 'DISPATCHED',
  sender: { id: id.supplier, name: 'Sari Gudang' }, receiver: { id: id.inspection, name: 'Budi Teknisi' },
  lines: [{ id: id.line, stockIdentityId: id.piece, sku: materialSku, baseUnit: 'MM', dispatchedBase: '100000', acceptedBase: '0', remainingBase: '100000', serial: null, lotCode: 'REEL-1' }] })
export const myResidual = (): MyMaterialResidual => ({ id: id.document, code: 'RESIDUAL-WO', workOrderId: id.source, revision: 1, state: 'DISPATCHED', purpose: 'RETURN',
  sender: { id: id.inspection, name: 'Budi Teknisi' }, receiver: null, location: { id: id.allocation, code: 'RETURN', name: 'Karantina retur' }, sku: materialSku, quantityBase: '17500', baseUnit: 'MM', serial: null, lotCode: 'REEL-1', recordedAt: '2026-09-25T01:00:00Z' })
