import { timestamp } from './approvals'
import { POLICY_OPERATIONS } from './approvalReads'
import { integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { policyChoice, policyDetails, type PolicyOperation } from './policy'
import { command, parameters, query } from './transport'

export const DELEGATION_STATES = ['ACTIVE', 'EXPIRED', 'REVOKED'] as const
export function delegation(value: unknown, path = 'delegation') {
  const r = record(value, path)
  const result = { id: uuid(r.id, path), approverId: uuid(r.approverId, path), delegateId: uuid(r.delegateId, path), sourceRoleId: nullable(r.sourceRoleId, uuid, path),
    locationId: uuid(r.locationId, path), operation: oneOf(r.operation, POLICY_OPERATIONS, path), validFrom: timestamp(r.validFrom, path), validUntil: timestamp(r.validUntil, path),
    revokedAt: nullable(r.revokedAt, timestamp, path), revision: integer(r.revision, path) }
  if (result.revision < 1 || result.approverId === result.delegateId || Date.parse(result.validUntil) <= Date.parse(result.validFrom)) throw new WarehouseDataError(path)
  return result
}
export type WarehouseDelegation = ReturnType<typeof delegation>
export function delegationView(value: unknown, path = 'delegationView') {
  const r = record(value, path), grant = delegation(r.delegation, path), location = record(r.location, path)
  const result = { delegation: grant, state: oneOf(r.state, DELEGATION_STATES, path), approver: nullable(r.approver, policyChoice, path), delegate: nullable(r.delegate, policyChoice, path), sourceRole: nullable(r.sourceRole, policyChoice, path),
    location: { id: uuid(location.id, path), code: text(location.code, path), name: nullable(location.name, text, path) } }
  if ((result.approver && result.approver.id !== grant.approverId) || (result.delegate && result.delegate.id !== grant.delegateId) || (result.sourceRole && result.sourceRole.id !== grant.sourceRoleId) || result.location.id !== grant.locationId || (result.state === 'REVOKED') !== (grant.revokedAt !== null)) throw new WarehouseDataError(path)
  return result
}
export interface DelegationInput { expectedRevision: 0; approverId: string; delegateId: string; sourceRoleId: string | null; locationId: string; operation: PolicyOperation; validUntil: string }
const root = '/api/v1/warehouse/settings'
export const listPolicyHistory = (page: number) => query(`${root}/workbench/policy-history${parameters({ page, size: 10 })}`, pageOf(policyDetails))
export const listDelegations = (filter: { page?: number; size?: number; locationId?: string; state?: typeof DELEGATION_STATES[number] } = {}) => query(`${root}/workbench/delegations${parameters(filter)}`, pageOf(delegationView))
export const createDelegation = (input: DelegationInput) => command(`${root}/delegations`, 'POST', input, delegation)
export const revokeDelegation = (value: WarehouseDelegation) => command(`${root}/delegations/${uuid(value.id)}/revoke`, 'POST', { expectedRevision: value.revision }, delegation)
export function delegationCandidates(filter: { locationId: string; operation: PolicyOperation; kind: 'APPROVER' | 'DELEGATE'; approverId?: string; sourceRoleId?: string; query?: string; page: number }) {
  uuid(filter.locationId); if (filter.approverId) uuid(filter.approverId); if (filter.sourceRoleId) uuid(filter.sourceRoleId)
  return query(`${root}/workbench/delegation-candidates${parameters({ ...filter, size: 25 })}`, pageOf(policyChoice))
}
