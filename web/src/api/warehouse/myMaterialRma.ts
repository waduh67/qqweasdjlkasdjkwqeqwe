import { integer, pageOf, record, uuid, WarehouseDataError } from './codec'
import type { AssetTopology } from './customerAssets'
import type { MyMaterialContext } from './myMaterials'
import { rmaDetails, rmaHandover, type RmaDetails } from './returns'
import { sameSerialIdentity } from './serialIdentity'
import { captureCommandSession, command, parameters, query, type WarehouseCommand } from './transport'

const root = (work: string) => `/api/v1/warehouse/my-materials/${uuid(work)}/rmas`
function own(value: unknown, work: string, handover?: string) {
  const result = rmaDetails(value), row = result.handover
  if (row.workOrderId !== work || (handover && row.id !== handover) || row.state === 'DRAFT' ||
    row.locationId !== (row.state === 'DISPATCHED' ? row.transitLocationId : row.technicianLocationId)) throw new WarehouseDataError('rma')
  return result
}
export const getMyMaterialRmas = (work: string, page: number) => query(`${root(work)}${parameters({ page, size: 10 })}`, pageOf(value => own(value, work)))
export const getMyMaterialRma = (work: string, handover: string) => query(`${root(work)}/${uuid(handover)}`, value => own(value, work, handover))

function eligible(context: MyMaterialContext, row: RmaDetails, actor: string, observed: string) {
  if (!context.currentAssignee || !context.active || context.id !== row.handover.workOrderId || actor !== row.handover.technicianId)
    throw new Error('Perangkat servis hanya dapat diterima dan dipasang oleh teknisi yang ditugaskan pada WO aktif.')
  if (!sameSerialIdentity(observed, row.handover.serial)) throw new Error('Cocokkan serial fisik dengan perangkat servis ini.')
}
export function acknowledgeMyRma(context: MyMaterialContext, row: RmaDetails, actor: string, observed: string, reference: string) {
  eligible(context, row, actor, observed)
  const h = row.handover
  if (h.state !== 'DISPATCHED' || h.workOrderRevision !== context.workOrderRevision || !reference.trim() || reference.trim().length > 500)
    throw new Error('Penerimaan memerlukan pengiriman yang masih berlaku dan referensi bukti.')
  return command(`/api/v1/warehouse/rma-handovers/${uuid(h.id)}/acknowledge`, 'POST', {
    expectedRevision: h.revision, observedSerial: h.serial, evidenceReference: reference.trim(),
  }, value => {
    const result = rmaHandover(value)
    if (result.id !== h.id || result.customerId !== h.customerId || result.stockIdentityId !== h.stockIdentityId || result.state !== 'RECEIVED') throw new WarehouseDataError('rma')
    return result
  })
}

/** RMA keeps the original customer's title. Both canonical stages retain their key and bytes after response loss. */
export function reinstallMyRma(context: MyMaterialContext, row: RmaDetails, actor: string, observed: string, topology: AssetTopology | null): WarehouseCommand<unknown> {
  eligible(context, row, actor, observed)
  const h = structuredClone(row.handover), capturedTopology = structuredClone(topology)
  if (h.state !== 'RECEIVED') throw new Error('Terima perangkat servis sebelum memasangnya kembali.')
  const authorize = command(`/api/work-orders/${uuid(context.id)}/assets/authorize`, 'POST', {
    expectedRevision: context.workOrderRevision, assetId: h.stockIdentityId, issueLineId: null, purpose: 'RETURN_CUSTOMER_RMA',
    ownershipMode: 'SALE', previousAssignmentId: h.originalAssignmentId, repairCaseId: h.repairCaseId,
  }, value => { const r = record(value); return { authorizationId: uuid(r.authorizationId), revision: integer(r.revision), operationId: uuid(r.operationId) } })
  const path = `/api/customers/${uuid(h.customerId)}/assets/install`, installKey = crypto.randomUUID()
  const checkSession = captureCommandSession()
  let install: WarehouseCommand<unknown> | null = null
  return Object.freeze({ path, key: authorize.key, body: JSON.stringify({ authorization: JSON.parse(authorize.body), topology: capturedTopology }), async execute() {
    checkSession()
    if (!install) {
      const permit = await authorize.execute()
      checkSession()
      install = command(path, 'POST', { authorizationId: permit.authorizationId, expectedRevision: permit.revision, topology: capturedTopology }, value => {
        const r = record(value)
        if (r.customerId !== h.customerId || r.assetId !== h.stockIdentityId) throw new WarehouseDataError('episode')
        return { assignmentId: uuid(r.assignmentId), episodeId: uuid(r.episodeId) }
      }, installKey)
    }
    return install.execute()
  } })
}
