import { timestamp } from './approvals'
import { POLICY_OPERATIONS } from './approvalReads'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { command, query } from './transport'

export type PolicyOperation = typeof POLICY_OPERATIONS[number]
export interface PolicyTier { minimumMinor: string; userIds: string[]; roleIds: string[] }
export interface PolicyRule { operation: PolicyOperation; tiers: PolicyTier[] }
export interface PolicyInput { expectedRevision: number; currency: string; expiryHours: number; warehouseIds: string[]; rules: PolicyRule[] }
function tier(value: unknown, path = 'tier'): PolicyTier {
  const row = record(value, path)
  const result = { minimumMinor: decimal(row.minimumMinor, path), userIds: array(row.userIds, uuid, path, 100), roleIds: array(row.roleIds, uuid, path, 100) }
  if (BigInt(result.minimumMinor) <= 0n || result.minimumMinor.length > 38 || result.userIds.length + result.roleIds.length < 1 || result.userIds.length + result.roleIds.length > 100 ||
    new Set(result.userIds).size !== result.userIds.length || new Set(result.roleIds).size !== result.roleIds.length) throw new WarehouseDataError(path)
  return result
}
export function policyVersion(value: unknown, path = 'policy') {
  const row = record(value, path), rules = array(row.rules, (value, path = 'rule'): PolicyRule => {
    const rule = record(value, path), tiers = array(rule.tiers, tier, path, 10)
    if (!tiers.length || tiers.some((row, index) => index > 0 && BigInt(row.minimumMinor) <= BigInt(tiers[index - 1].minimumMinor))) throw new WarehouseDataError(path)
    return { operation: oneOf(rule.operation, POLICY_OPERATIONS, path), tiers }
  }, path, 9)
  const currency = text(row.currency, path), expiryHours = integer(row.expiryHours, path), warehouseIds = array(row.warehouseIds, uuid, path, 100), revision = integer(row.revision, path)
  if (!/^[A-Z]{3}$/.test(currency) || expiryHours < 1 || expiryHours > 720 || revision < 1 || !warehouseIds.length || new Set(warehouseIds).size !== warehouseIds.length || !rules.length || new Set(rules.map(row => row.operation)).size !== rules.length) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), revision, currency, expiryHours, warehouseIds, rules, actorId: uuid(row.actorId, path), createdAt: timestamp(row.createdAt, path) }
}
export type PolicyVersion = ReturnType<typeof policyVersion>
export function policyChoice(value: unknown, path = 'choice') {
  const row = record(value, path)
  return { id: uuid(row.id, path), name: text(row.name, path) }
}
export type PolicyChoice = ReturnType<typeof policyChoice>
export function policyDetails(value: unknown, path = 'settings') {
  const row = record(value, path), refs = record(row.references, path), current = nullable(row.current, policyVersion, path), configured = boolean(row.configured, path)
  const references = { users: array(refs.users, policyChoice, path, 9000), roles: array(refs.roles, policyChoice, path, 9000), locations: array(refs.locations, (value, path = 'location') => {
    const row = record(value, path)
    return { id: uuid(row.id, path), code: text(row.code, path), name: nullable(row.name, text, path) }
  }, path, 100) }
  if (configured !== (current !== null) || (current && (references.locations.length !== current.warehouseIds.length || current.warehouseIds.some(id => !references.locations.some(row => row.id === id))))) throw new WarehouseDataError(path)
  return { configured, current, references }
}
export type PolicyDetails = ReturnType<typeof policyDetails>
export const getPolicyDetails = () => query('/api/v1/warehouse/settings/policy/details', policyDetails)
export const savePolicy = (input: PolicyInput) => command('/api/v1/warehouse/settings/policy', 'PUT', input, policyVersion)
export function policyApprovers(locations: string[], kind: 'USER' | 'ROLE', search: string, page: number) {
  if (!locations.length) return Promise.resolve({ items: [], page: 0, size: 25, totalElements: 0 })
  const params = new URLSearchParams({ kind, page: String(integer(page)), size: '25' })
  locations.forEach(id => params.append('locationId', uuid(id)))
  if (search) params.set('query', search)
  return query(`/api/v1/warehouse/settings/policy/approvers?${params}`, pageOf(policyChoice))
}
