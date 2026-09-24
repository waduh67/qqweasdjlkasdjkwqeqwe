import type { AssetHistory, AssetJob, AssetSource, AssetWorkspace } from '@/api/warehouse/customerAssets'
import { custodyFixture } from './warehouseExecutionFixture'
import { materialIds } from './warehouseMaterialFixture'

export const assetIds = { ...materialIds, customer: '11111111-1111-4111-8111-111111111111', assignment: '22222222-2222-4222-8222-222222222222' }
const id = assetIds

export const assetWorkspaceFixture = (): AssetWorkspace => ({ id: id.customer, code: 'C-001', name: 'Pelanggan Satu', status: 'ACTIVE', unresolvedDevices: 0 })
export const assetJobFixture = (): AssetJob => ({ id: id.source, code: 'WO-PSB-01', customerId: id.customer, workType: 'PSB', status: 'IN_PROGRESS', revision: 7,
  signature: { id: id.evidence, signerName: 'Pelanggan Satu', recordedAt: '2026-09-25T01:00:00Z' } })
export const assetSourceFixture = (): AssetSource => ({ id: id.piece, source: { ...custodyFixture(), sku: { ...custodyFixture().sku, name: 'ONU Rumah', tracking: 'SERIAL', baseUnit: 'EA' },
  serial: 'ONU-A1', lotCode: null, quantityBase: '1', baseUnit: 'EA', initialUseSource: true, sourceUsageId: null }, assetRevision: 4, provenance: 'RECEIPT', ownershipModes: ['LOAN', 'SALE'] })
export const assetHistoryFixture = (): AssetHistory => ({ asset: { id: id.assignment, customerId: id.customer, assetId: id.piece, serial: 'ONU-A1', sku: assetSourceFixture().source.sku,
  workOrderId: id.source, issueId: id.issue, issueCode: 'ISS-01', revision: 0, titleRevision: 0, purpose: 'INSTALL', provenance: 'RECEIPT', ownershipMode: 'LOAN', legalOwner: 'ISP', handoverState: 'PENDING',
  startedAt: '2026-09-25T01:00:00Z', endedAt: null, previousAssignmentId: null, origin: { id: id.document, code: 'RCV-01', kind: 'RECEIPT' }, positionStatus: 'CUSTOMER_INSTALLED', recoveryRequired: true },
  episode: { onuId: id.assignment, episodeRevision: 3, odpId: null, portNumber: null } })
