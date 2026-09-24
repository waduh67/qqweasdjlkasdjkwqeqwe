import { timestamp } from './approvals'
import { array, boolean, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { materialCustody } from './materialExecution'
import { materialSku } from './materialModels'
import { captureCommandSession, command, parameters, query, type WarehouseCommand } from './transport'

export const ASSET_PROVENANCE = ['RECEIPT', 'OPENING_BALANCE', 'UNKNOWN'] as const
export function assetWorkspace(value: unknown, path = 'customer') {
  const r = record(value, path)
  return { id: uuid(r.id, path), code: text(r.code, path), name: text(r.name, path), status: text(r.status, path), unresolvedDevices: integer(r.unresolvedDevices, path) }
}
export function assetJob(value: unknown, path = 'job') {
  const r = record(value, path)
  return { id: uuid(r.id, path), code: text(r.code, path), customerId: uuid(r.customerId, path), workType: oneOf(r.workType, ['PSB', 'MIGRATION', 'REPAIR', 'DISMANTLE'], path),
    status: oneOf(r.status, ['ASSIGNED', 'IN_PROGRESS', 'DONE'], path), revision: integer(r.revision, path), signature: nullable(r.signature, (value, path = 'signature') => {
      const s = record(value, path); return { id: uuid(s.id, path), signerName: text(s.signerName, path), recordedAt: timestamp(s.recordedAt, path) }
    }, path) }
}
export function assetSource(value: unknown, path = 'source') {
  const r = record(value, path), source = materialCustody(r.source, path)
  const result = { id: uuid(r.id, path), source, assetRevision: integer(r.assetRevision, path), provenance: oneOf(r.provenance, ASSET_PROVENANCE, path),
    ownershipModes: array(r.ownershipModes, (v, p) => oneOf(v, ['LOAN', 'SALE'], p), path, 2) }
  if (result.id !== source.id || source.sku.tracking !== 'SERIAL' || source.baseUnit !== 'EA' || source.quantityBase !== '1' || !source.serial || !source.initialUseSource ||
    result.provenance === 'UNKNOWN' || result.ownershipModes.length === 0 || new Set(result.ownershipModes).size !== result.ownershipModes.length) throw new WarehouseDataError(path)
  return result
}
export function assetHistory(value: unknown, path = 'history') {
  const r = record(value, path), a = record(r.asset, path)
  const asset = { id: uuid(a.id, path), customerId: uuid(a.customerId, path), assetId: uuid(a.assetId, path), serial: text(a.serial, path), sku: materialSku(a.sku, path),
    workOrderId: uuid(a.workOrderId, path), issueId: nullable(a.issueId, uuid, path), issueCode: nullable(a.issueCode, text, path), revision: integer(a.revision, path), titleRevision: integer(a.titleRevision, path),
    purpose: oneOf(a.purpose, ['INSTALL', 'REPLACE', 'REMOVE', 'RETURN_CUSTOMER_RMA'], path), provenance: oneOf(a.provenance, ASSET_PROVENANCE, path),
    ownershipMode: oneOf(a.ownershipMode, ['LOAN', 'SALE'], path), legalOwner: oneOf(a.legalOwner, ['ISP', 'CUSTOMER'], path), handoverState: oneOf(a.handoverState, ['PENDING', 'ACCEPTED'], path),
    startedAt: timestamp(a.startedAt, path), endedAt: nullable(a.endedAt, timestamp, path), previousAssignmentId: nullable(a.previousAssignmentId, uuid, path), positionStatus: text(a.positionStatus, path), recoveryRequired: boolean(a.recoveryRequired, path),
    origin: nullable(a.origin, (v, p = 'origin') => { const o = record(v, p); return { id: uuid(o.id, p), code: text(o.code, p), kind: text(o.kind, p) } }, path) }
  const episode = nullable(r.episode, (v, p = 'episode') => { const e = record(v, p); return { onuId: uuid(e.onuId, p), episodeRevision: integer(e.episodeRevision, p), odpId: nullable(e.odpId, uuid, p), portNumber: nullable(e.portNumber, integer, p) } }, path)
  if (asset.sku.tracking !== 'SERIAL' || asset.sku.baseUnit !== 'EA' || (asset.endedAt !== null && new Date(asset.endedAt) < new Date(asset.startedAt))) throw new WarehouseDataError(path)
  return { asset, episode }
}
export type AssetWorkspace = ReturnType<typeof assetWorkspace>
export type AssetJob = ReturnType<typeof assetJob>
export type AssetSource = ReturnType<typeof assetSource>
export type AssetHistory = ReturnType<typeof assetHistory>
export interface AssetTopology { odpId: string; portNumber: number; installRxPowerDbm: number | null }
export interface AssetObservation { id: string; serial: string }
const root = (id: string) => `/api/customers/${uuid(id)}/assets`
const workbench = (id: string) => `${root(id)}/workbench`
const bound = <T>(value: T, matches: boolean): T => { if (!matches) throw new WarehouseDataError('customer'); return value }
export const getAssetWorkspace = (id: string) => query(workbench(id), value => { const row = assetWorkspace(value); return bound(row, row.id === id) })
export const getAssetJobs = (id: string, page: number) => query(`${workbench(id)}/jobs${parameters({ page, size: 25 })}`, value => { const result = pageOf(assetJob)(value); return bound(result, result.items.every(row => row.customerId === id)) })
export const getAssetJob = (id: string, job: string) => query(`${workbench(id)}/jobs/${uuid(job)}`, value => { const row = assetJob(value); return bound(row, row.id === job && row.customerId === id) })
export const getAssetSources = (id: string, job: string, page: number) => query(`${workbench(id)}/jobs/${uuid(job)}/sources${parameters({ page, size: 25 })}`, pageOf(assetSource))
export const getAssetSource = (id: string, job: string, asset: string) => query(`${workbench(id)}/jobs/${uuid(job)}/sources/${uuid(asset)}`, value => { const row = assetSource(value); return bound(row, row.id === asset) })
export const getAssetHistory = (id: string, page: number) => query(`${workbench(id)}/history${parameters({ page, size: 10 })}`, value => { const result = pageOf(assetHistory)(value); return bound(result, result.items.every(row => row.asset.customerId === id)) })
export const getAssetAssignment = (id: string, assignment: string) => query(`${workbench(id)}/history/${uuid(assignment)}`, value => { const row = assetHistory(value); return bound(row, row.asset.customerId === id && row.asset.id === assignment) })

function authorization(value: unknown, path = 'authorization') {
  const r = record(value, path); return { authorizationId: uuid(r.authorizationId, path), revision: integer(r.revision, path), operationId: uuid(r.operationId, path) }
}
function episodeOutcome(customer: string, asset: string) {
  return (value: unknown, path = 'episode') => { const r = record(value, path); return bound({ assignmentId: uuid(r.assignmentId, path), episodeId: uuid(r.episodeId, path) }, r.customerId === customer && r.assetId === asset) }
}

/** One reviewed action, two canonical commands. Each stage retains its own exact bytes/key after response loss. */
export function deployCustomerAsset(job: AssetJob, source: AssetSource, mode: 'LOAN' | 'SALE', topology: AssetTopology | null,
  previous?: AssetHistory, observation?: AssetObservation): WarehouseCommand<unknown> {
  job = structuredClone(job); source = structuredClone(source); topology = structuredClone(topology)
  previous = structuredClone(previous); observation = structuredClone(observation)
  if (job.status === 'DONE' || job.workType !== (previous ? 'MIGRATION' : 'PSB') || !source.ownershipModes.includes(mode) ||
    (previous && (previous.asset.customerId !== job.customerId || previous.asset.endedAt !== null || !job.signature)) ||
    (observation && (previous || observation.serial !== source.source.serial))) throw new Error('Sumber perangkat atau WO tidak cocok dengan pemasangan.')
  const authorize = command(`/api/work-orders/${uuid(job.id)}/assets/authorize`, 'POST', {
    expectedRevision: job.revision, assetId: source.id, issueLineId: source.source.issueLineId, purpose: previous ? 'REPLACE' : 'INSTALL', ownershipMode: mode,
    ...(previous ? { previousAssignmentId: previous.asset.id } : {}),
  }, authorization)
  const consumeKey = crypto.randomUUID()
  const checkSession = captureCommandSession()
  let consume: WarehouseCommand<unknown> | null = null
  const body = JSON.stringify({ authorization: JSON.parse(authorize.body), topology, previous: previous ? { id: previous.asset.id, revision: previous.asset.revision, titleRevision: previous.asset.titleRevision, evidenceId: job.signature?.id } : null, observation })
  return Object.freeze({ key: authorize.key, body, path: `${root(job.customerId)}/${previous ? 'replace' : 'install'}`, async execute() {
    checkSession()
    if (!consume) {
      const permit = await authorize.execute()
      checkSession()
      const input = { authorizationId: permit.authorizationId, expectedRevision: permit.revision, topology }
      consume = observation ? command(`/api/monitoring/discovered-onus/${uuid(observation.id)}/provision`, 'POST', { customerId: job.customerId,
        authorizationId: permit.authorizationId, expectedRevision: permit.revision, odpId: topology?.odpId ?? null, portNumber: topology?.portNumber ?? null, installRxPowerDbm: topology?.installRxPowerDbm ?? null }, value => {
        const row = record(value); return bound({ id: uuid(row.id) }, row.id === observation.id && row.state === 'PROVISIONED' && row.serialNumber === observation.serial)
      }, consumeKey) : previous ? command(`${root(job.customerId)}/replace`, 'POST', { ...input, expectedAssignmentRevision: previous.asset.revision,
        expectedTitleRevision: previous.asset.titleRevision, evidenceId: job.signature!.id }, value => {
        const row = record(value), replaced = record(row.replacement); episodeOutcome(job.customerId, source.id)(replaced); return { operationId: uuid(row.operationId) }
      }, consumeKey) : command(`${root(job.customerId)}/install`, 'POST', input, episodeOutcome(job.customerId, source.id), consumeKey)
    }
    return consume.execute()
  } })
}
export function acceptCustomerAsset(row: AssetHistory, job: AssetJob) {
  if (row.asset.endedAt || row.asset.handoverState !== 'PENDING' || job.id !== row.asset.workOrderId || job.customerId !== row.asset.customerId || !job.signature) throw new Error('Penerimaan memerlukan WO asal dan tanda tangan pelanggan yang masih berlaku.')
  return command(`${root(job.customerId)}/handover`, 'POST', { assignmentId: row.asset.id, expectedRevision: row.asset.revision, expectedTitleRevision: row.asset.titleRevision, evidenceId: job.signature.id }, value => {
    const r = record(value); return bound({ assignmentId: uuid(r.assignmentId), revision: integer(r.revision) }, r.assignmentId === row.asset.id && r.customerId === job.customerId && r.handoverState === 'ACCEPTED')
  })
}
export function removeCustomerAsset(row: AssetHistory, job: AssetJob) {
  if (row.asset.endedAt || job.workType !== 'DISMANTLE' || job.status === 'DONE' || job.customerId !== row.asset.customerId || !job.signature) throw new Error('Pelepasan memerlukan WO bongkar aktif dan bukti tanda tangan.')
  return command(`${root(job.customerId)}/remove`, 'POST', { assignmentId: row.asset.id, workOrderId: job.id, expectedRevision: row.asset.revision, expectedTitleRevision: row.asset.titleRevision, evidenceId: job.signature.id }, value => {
    const r = record(value); return bound({ operationId: uuid(r.operationId) }, r.assignmentId === row.asset.id && r.customerId === job.customerId)
  })
}
export function relocateCustomerAsset(row: AssetHistory, job: AssetJob, topology: AssetTopology) {
  if (row.asset.endedAt || !row.episode || job.status === 'DONE' || job.workType !== 'MIGRATION' || job.customerId !== row.asset.customerId) throw new Error('Pindah ODP memerlukan episode terpasang dan WO migrasi aktif.')
  return command(`${root(job.customerId)}/${uuid(row.asset.id)}/relocate`, 'POST', { workOrderId: job.id, expectedWorkOrderRevision: job.revision, expectedRevision: row.episode.episodeRevision, topology }, value => {
    const r = record(value); return bound({ operationId: uuid(r.operationId), revision: integer(r.revision) }, r.onuId === row.episode!.onuId)
  })
}
